package kotnb

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotnb.display.Html
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KotlinLexer
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.scripting.compiler.plugin.impl.K2ReplCompiler
import org.jetbrains.kotlin.scripting.compiler.plugin.impl.K2ReplEvaluator
import org.jetbrains.kotlin.scripting.compiler.plugin.impl.ScriptDiagnosticsMessageCollector
import org.jetbrains.kotlin.scripting.ide_services.compiler.KJvmReplCompilerWithIdeServices
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.script.experimental.api.*
import kotlin.script.experimental.dependencies.CompoundDependenciesResolver
import kotlin.script.experimental.dependencies.DependsOn
import kotlin.script.experimental.dependencies.FileSystemDependenciesResolver
import kotlin.script.experimental.dependencies.Repository
import kotlin.script.experimental.dependencies.maven.MavenDependenciesResolver
import kotlin.script.experimental.dependencies.resolveFromScriptSourceAnnotations
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.*

@Serializable
enum class Severity { ERROR, WARNING }

/** A compiler message; positions are 1-based and refer to the cell's own source. */
@Serializable
data class Diagnostic(
    val severity: Severity,
    val message: String,
    val line: Int? = null,
    val column: Int? = null,
    val endLine: Int? = null,
    val endColumn: Int? = null,
)

@Serializable
data class CompletionItem(val text: String, val displayText: String, val tail: String, val icon: String, val deprecated: Boolean)

sealed interface ExecutionResult {
    val warnings: List<Diagnostic>

    /** [name] is the variable holding the value, e.g. `res3`, which later snippets can refer to. */
    data class Value(
        val name: String,
        val text: String,
        val type: String,
        val isHtml: Boolean,
        override val warnings: List<Diagnostic>,
    ) :
        ExecutionResult

    data class NoValue(override val warnings: List<Diagnostic>) : ExecutionResult

    data class CompileError(val diagnostics: List<Diagnostic>) : ExecutionResult {
        override val warnings get() = diagnostics.filter { it.severity == Severity.WARNING }
    }

    data class RuntimeError(val text: String, val interrupted: Boolean, override val warnings: List<Diagnostic>) :
        ExecutionResult
}

/**
 * An in-process Kotlin interpreter whose snippets share state, like a REPL.
 *
 * Snippets are compiled and run by the K2 REPL, so cells get the full language of the bundled compiler. Completion
 * comes from the K1-based IDE services, the only REPL completion engine Kotlin ships. To keep it in step, every
 * snippet that ran is also compiled (but not run) by the K1 completer in the background, which gives completion the
 * same declarations and shadowing rules. Snippets K1 cannot compile, such as ones using K2-only syntax, are instead
 * prepended to the code being completed; K1's analysis tolerates their errors, so their declarations still resolve.
 *
 * [execute] blocks until the snippet finishes and must only be called from one thread at a time. [complete] may be
 * called from any thread, also while a snippet runs.
 */
class KotlinRepl : AutoCloseable {
    private val resolver = CompoundDependenciesResolver(FileSystemDependenciesResolver(), MavenDependenciesResolver())

    private val baseConfiguration = ScriptCompilationConfiguration {
        jvm {
            updateClasspath(ScriptClasspath.files)
            jvmTarget(Runtime.version().feature().toString())
        }
        defaultImports(DependsOn::class, Repository::class)
        defaultImports("kotnb.display.*")
    }

    private val executionConfiguration = ScriptCompilationConfiguration(baseConfiguration) {
        refineConfiguration {
            onAnnotations(DependsOn::class, Repository::class, handler = ::resolveDependencies)
        }
    }

    private val evaluationConfiguration = ScriptEvaluationConfiguration {
        jvm {
            baseClassLoader(KotlinRepl::class.java.classLoader)
        }
    }

    private val disposable = Disposer.newDisposable("kotnb-repl")
    private val messages = ScriptDiagnosticsMessageCollector(null)
    private val state = K2ReplCompiler.createCompilationState(messages, disposable, executionConfiguration)
    private val compiler = K2ReplCompiler(state)
    private val evaluator = K2ReplEvaluator()
    private var snippetCounter = 0

    // The completer is only used on this thread, which handles history updates and completion requests in order, so
    // a completion always sees the snippets that ran before it was requested.
    private val completionThread = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "kotnb-completion").apply { isDaemon = true }
    }
    // Kotlin 2.5 deprecates the K1 REPL completer without a replacement yet; it still works, so keep using it.
    @Suppress("DEPRECATION_ERROR", "DEPRECATION")
    private val completer by lazy { KJvmReplCompilerWithIdeServices() }
    private var completerSnippetCounter = 0

    /** Snippets the completer could not compile, by key, in run order. */
    private val uncompiledHistory = LinkedHashMap<String, String>()

    init {
        // The K2 REPL emits its shared state holder class with the first snippet it compiles; if that snippet fails
        // to compile, no later snippet can run. Starting with a trivial snippet avoids that.
        val warmUp = compileAndRun("Unit")
        check(warmUp is ExecutionResult.NoValue) { "Kotlin REPL failed to start: $warmUp" }
        // Initializing the completer takes seconds too. Doing it now, rather than on the first completion, keeps typing
        // responsive; doing it after the kernel is ready, rather than in parallel, lets cells run sooner.
        completionThread.execute { addToCompletionHistory("warm-up", "Unit") }
    }

    /**
     * Runs [code]. [cellId] identifies the notebook cell it came from, so completion in that cell does not see the
     * cell's previous version twice.
     */
    fun execute(code: String, cellId: String? = null): ExecutionResult {
        val result = compileAndRun(code)
        if (result !is ExecutionResult.CompileError) {
            val key = cellId ?: "snippet-$snippetCounter"
            completionThread.execute { addToCompletionHistory(key, code) }
        }
        return result
    }

    private fun compileAndRun(code: String): ExecutionResult {
        syntaxErrors(code).takeIf { it.isNotEmpty() }?.let { return ExecutionResult.CompileError(it) }

        val source = code.toScriptSource("Line_${++snippetCounter}.kts")
        val historyBefore = state.lastCompiledSnippet
        val compiled = try {
            runBlocking { compiler.compile(source) }
        } catch (e: Throwable) {
            state.lastCompiledSnippet = historyBefore
            log.warn("Kotlin compiler failed on a snippet", e)
            val reported = messages.diagnostics.mapNotNull { it.toDiagnostic() }.filter { it.severity == Severity.ERROR }
            return ExecutionResult.CompileError(reported.ifEmpty { listOf(internalError(e)) })
        }
        val diagnostics = compiled.reports.mapNotNull { it.toDiagnostic() }
        val warnings = diagnostics.filter { it.severity == Severity.WARNING }
        if (compiled !is ResultWithDiagnostics.Success || diagnostics.any { it.severity == Severity.ERROR }) {
            // Roll back so the evaluator never sees a snippet that did not compile cleanly.
            state.lastCompiledSnippet = historyBefore
            return ExecutionResult.CompileError(diagnostics.ifEmpty { listOf(Diagnostic(Severity.ERROR, "Compilation failed")) })
        }

        Thread.interrupted() // Clear a stale interrupt aimed at a previous snippet.
        val evaluated = runBlocking { evaluator.eval(compiled.value, evaluationConfiguration) }
        if (evaluated !is ResultWithDiagnostics.Success) {
            state.lastCompiledSnippet = historyBefore
            val message = evaluated.reports.joinToString("\n") { it.render() }.ifEmpty { "Evaluation failed" }
            return ExecutionResult.RuntimeError(message, interrupted = false, warnings = warnings)
        }

        return when (val result = evaluated.value.get().result) {
            is ResultValue.Value -> {
                val value = result.value
                ExecutionResult.Value(result.name, renderValue(value), shortTypeName(result.type), value is Html, warnings)
            }
            is ResultValue.Error -> {
                val error = result.error
                ExecutionResult.RuntimeError(renderThrowable(error, source.name), isInterruption(error), warnings)
            }
            is ResultValue.Unit, is ResultValue.NotEvaluated -> ExecutionResult.NoValue(warnings)
        }
    }

    /** Completes [code], the current text of cell [cellId], at offset [cursor]. */
    fun complete(cellId: String?, code: String, cursor: Int): List<CompletionItem> =
        completionThread.submit<List<CompletionItem>> { completeNow(cellId, code, cursor) }
            .get(COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    private fun completeNow(cellId: String?, code: String, cursor: Int): List<CompletionItem> {
        val prefix = uncompiledHistory.filterKeys { it != cellId }.values
            .joinToString("") { blankFileAnnotations(it) + HISTORY_SEPARATOR }
        val text = prefix + blankFileAnnotations(code)
        val absolute = prefix.length + cursor.coerceIn(0, code.length)
        val (line, column) = lineAndColumn(text, absolute)
        // Every request needs a fresh name; the completer returns nothing for a name it has already analyzed.
        val source = text.toScriptSource("Completion_${++completerSnippetCounter}.kts")
        val result = runBlocking { completer.complete(source, SourceCode.Position(line, column, absolute), baseConfiguration) }
        val afterDot = text.take(absolute).trimEnd { it.isLetterOrDigit() || it == '_' }.endsWith('.')
        return result.valueOrNull().orEmpty().toList()
            .filterNot { afterDot && it.icon == "keyword" } // Like IntelliJ, offer only members after a dot.
            .distinctBy { it.text to it.displayText }
            .take(MAX_COMPLETIONS)
            .map { CompletionItem(it.text, it.displayText, it.tail, it.icon, it.deprecationLevel != null) }
    }

    private fun addToCompletionHistory(key: String, code: String) {
        uncompiledHistory.remove(key)
        val source = code.toScriptSource("Line_${++completerSnippetCounter}.kts")
        val compiled = try {
            runBlocking { completer.compile(source, executionConfiguration) }
        } catch (e: Throwable) {
            log.debug("Completion compiler failed on a snippet", e)
            null
        }
        if (compiled !is ResultWithDiagnostics.Success) uncompiledHistory[key] = code
    }

    override fun close() {
        completionThread.execute { completer.state.dispose() }
        completionThread.shutdown()
        Disposer.dispose(disposable)
    }

    private fun resolveDependencies(context: ScriptConfigurationRefinementContext): ResultWithDiagnostics<ScriptCompilationConfiguration> {
        val annotations = context.collectedData?.get(ScriptCollectedData.collectedAnnotations).orEmpty()
        if (annotations.isEmpty()) return context.compilationConfiguration.asSuccess()
        return runBlocking { resolver.resolveFromScriptSourceAnnotations(annotations) }.onSuccess { files ->
            context.compilationConfiguration.with { dependencies.append(JvmDependency(files)) }.asSuccess()
        }
    }

    /**
     * Whether [code] is the start of a snippet rather than a mistake: it has syntax errors, and all of them are at the
     * end, e.g. an unclosed brace or a trailing operator. Command line REPLs keep reading lines while this holds.
     */
    fun isIncomplete(code: String): Boolean {
        val errors = syntaxErrorElements(code)
        if (errors.isEmpty()) return false
        // Find where the code really ends: trailing comments and whitespace do not count.
        val lexer = KotlinLexer()
        lexer.start(code)
        var end = 0
        var inSingleLineString = false
        while (lexer.tokenType != null) {
            val type = lexer.tokenType
            if (type == KtTokens.OPEN_QUOTE) inSingleLineString = lexer.tokenText == "\""
            if (type == KtTokens.CLOSING_QUOTE) inSingleLineString = false
            if (type != KtTokens.WHITE_SPACE && type !in KtTokens.COMMENTS) end = lexer.tokenEnd
            lexer.advance()
        }
        // More lines cannot finish a "..." string, so report it rather than wait.
        if (inSingleLineString) return false
        return errors.all { it.textRange.startOffset >= end }
    }

    private fun syntaxErrorElements(code: String): Collection<PsiErrorElement> {
        val file = KtPsiFactory(state.project, markGenerated = false).createFile("Syntax.kts", code)
        return PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java)
    }

    /**
     * The K2 REPL does not stop on syntax errors; it generates a broken class instead. Parse first so they are
     * reported as ordinary diagnostics.
     */
    private fun syntaxErrors(code: String): List<Diagnostic> {
        return syntaxErrorElements(code).map { error ->
            val start = error.textRange.startOffset
            val end = maxOf(error.textRange.endOffset, start)
            val (line, column) = lineAndColumn(code, start)
            val (endLine, endColumn) = lineAndColumn(code, end)
            Diagnostic(Severity.ERROR, error.errorDescription, line, column, endLine, endColumn)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(KotlinRepl::class.java)
        private const val MAX_COMPLETIONS = 500
        private const val COMPLETION_TIMEOUT_SECONDS = 30L
        private const val MAX_VALUE_CHARS = 1_000_000

        /** Separates history cells, and stops a cell starting with `.` from continuing the previous expression. */
        private const val HISTORY_SEPARATOR = "\n;\n"

        private val FILE_ANNOTATION = Regex("""(?m)^[ \t]*@file:.*$""")
        private val SNIPPET_CLASS = Regex("""\bLine_\d+\.""")
        private val PACKAGE_QUALIFIER = Regex("""\b(?:[a-z_][\w]*\.)+(?=[A-Za-z_])""")

        /** File annotations are only legal at the top of a file, so replace them with spaces in completion input. */
        private fun blankFileAnnotations(code: String) = FILE_ANNOTATION.replace(code) { " ".repeat(it.value.length) }

        internal fun lineAndColumn(text: String, offset: Int): Pair<Int, Int> {
            var line = 1
            var lineStart = 0
            for (i in 0 until offset.coerceAtMost(text.length)) {
                if (text[i] == '\n') {
                    line++
                    lineStart = i + 1
                }
            }
            return line to (offset - lineStart + 1)
        }

        internal fun shortTypeName(type: String): String =
            PACKAGE_QUALIFIER.replace(SNIPPET_CLASS.replace(type, ""), "")

        private fun renderValue(value: Any?): String {
            val text = try {
                when (value) {
                    is Array<*> -> value.contentDeepToString()
                    is IntArray -> value.contentToString()
                    is LongArray -> value.contentToString()
                    is DoubleArray -> value.contentToString()
                    is FloatArray -> value.contentToString()
                    is ShortArray -> value.contentToString()
                    is ByteArray -> value.contentToString()
                    is CharArray -> value.contentToString()
                    is BooleanArray -> value.contentToString()
                    else -> value.toString()
                }
            } catch (e: Throwable) {
                "<toString() threw ${e.javaClass.name}: ${e.message}>"
            }
            return if (text.length > MAX_VALUE_CHARS) text.take(MAX_VALUE_CHARS) + "\n… value truncated" else text
        }

        private fun isInterruption(error: Throwable): Boolean =
            generateSequence(error) { it.cause }.any { it is InterruptedException }

        /** Renders a stack trace without the interpreter's own frames, which only add noise. */
        internal fun renderThrowable(error: Throwable, cellFileName: String?): String = buildString {
            var current: Throwable? = error
            val seen = mutableSetOf<Throwable>()
            var first = true
            while (current != null && seen.add(current)) {
                if (!first) append("Caused by: ")
                append(current.toString()).append('\n')
                for (frame in current.stackTrace) {
                    if (isInterpreterFrame(frame)) break
                    append("    at ").append(renderFrame(frame, cellFileName)).append('\n')
                }
                first = false
                current = current.cause
            }
        }.trimEnd()

        private fun isInterpreterFrame(frame: StackTraceElement): Boolean {
            val className = frame.className
            return className.startsWith("jdk.internal.reflect.") ||
                className.startsWith("java.lang.reflect.") ||
                className.startsWith("org.jetbrains.kotlin.scripting.") ||
                className.startsWith("kotnb.KotlinRepl")
        }

        private fun renderFrame(frame: StackTraceElement, cellFileName: String?): String {
            if (cellFileName == null || frame.fileName != cellFileName) return frame.toString()
            val method = frame.methodName.takeUnless { it == "\$\$eval" }?.let { "$it, " }.orEmpty()
            return "${method}cell line ${frame.lineNumber}"
        }

        private fun ScriptDiagnostic.toDiagnostic(): Diagnostic? {
            val severity = when (severity) {
                ScriptDiagnostic.Severity.FATAL, ScriptDiagnostic.Severity.ERROR -> Severity.ERROR
                ScriptDiagnostic.Severity.WARNING -> Severity.WARNING
                else -> return null
            }
            val start = location?.start
            val end = location?.end
            val text = exception?.let { "$message: ${it.message ?: it.javaClass.name}" } ?: message
            return Diagnostic(severity, text, start?.line, start?.col, end?.line, end?.col)
        }

        private fun internalError(e: Throwable) =
            Diagnostic(Severity.ERROR, "Internal compiler error: ${e.message?.lineSequence()?.firstOrNull() ?: e.javaClass.name}")
    }
}
