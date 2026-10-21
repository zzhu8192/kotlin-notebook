package kotnb

import org.jetbrains.kotlin.lexer.KotlinLexer
import org.jetbrains.kotlin.lexer.KtTokens
import org.jline.reader.Candidate
import org.jline.reader.Completer
import org.jline.reader.CompletingParsedLine
import org.jline.reader.EOFError
import org.jline.reader.EndOfFileException
import org.jline.reader.Highlighter
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.ParsedLine
import org.jline.reader.Parser
import org.jline.reader.UserInterruptException
import org.jline.terminal.Terminal
import org.jline.utils.AttributedString
import org.jline.utils.AttributedStringBuilder
import org.jline.utils.AttributedStyle
import org.jline.utils.WriterOutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * An interactive Kotlin REPL on the command line, modeled on the Scala REPL: multi-line input continues while the
 * code is incomplete, Tab completes, input is highlighted, and results print as `res1: Int = 42`.
 */
class Cli(
    private val terminal: Terminal,
    private val historyFile: Path? = Path(System.getProperty("user.home"), ".kotlin_notebook_history"),
    private val replFactory: () -> KotlinRepl = ::KotlinRepl,
) {
    @Volatile
    private var kernelThread: Thread? = null

    // Snippets run on their own thread, so Ctrl+C can interrupt them, with a large stack like notebook kernels.
    private val kernel = Executors.newSingleThreadExecutor { runnable ->
        Thread(null, runnable, "kotnb-cli-kernel", 64L * 1024 * 1024).apply {
            isDaemon = true
            kernelThread = this
        }
    }

    // Start the compiler while the user reads the banner; it takes a few seconds.
    private var repl: CompletableFuture<KotlinRepl> = CompletableFuture.supplyAsync(replFactory, kernel)

    private val out get() = terminal.writer()

    /** Reads and runs input until `:quit` or end of input. */
    fun run() {
        val originalOut = System.out
        val originalErr = System.err
        // Send what cells print through the terminal, so it is encoded correctly and interleaves with results.
        val cellOutput = PrintStream(WriterOutputStream(terminal.writer(), Charsets.UTF_8), true, Charsets.UTF_8)
        System.setOut(cellOutput)
        System.setErr(cellOutput)
        try {
            loop()
        } finally {
            cellOutput.flush()
            System.setOut(originalOut)
            System.setErr(originalErr)
            repl.thenAcceptAsync({ it.close() }, kernel)
            kernel.shutdown()
        }
    }

    private fun loop() {
        out.println("Welcome to Kotlin ${KotlinVersion.CURRENT} (Java ${Runtime.version()}, ${System.getProperty("java.vm.name")}).")
        out.println("Type in expressions for evaluation. Or try :help.")
        out.println()
        out.flush()

        val reader = LineReaderBuilder.builder()
            .terminal(terminal)
            .appName("kotlin-notebook")
            .parser(InputParser())
            .completer(KotlinCompleter())
            .highlighter(KotlinHighlighter)
            .variable(LineReader.SECONDARY_PROMPT_PATTERN, CONTINUATION_PROMPT)
            .variable(LineReader.INDENTATION, 4)
            .apply { if (historyFile != null) variable(LineReader.HISTORY_FILE, historyFile) }
            .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true) // `!` is an operator, not a history reference.
            .option(LineReader.Option.INSERT_TAB, true) // Tab indents at the start of a line.
            .option(LineReader.Option.AUTO_FRESH_LINE, true)
            .build()

        while (true) {
            val input = try {
                reader.readLine(PROMPT)
            } catch (e: UserInterruptException) {
                continue // Ctrl+C discards the input, as in the Scala REPL.
            } catch (e: EndOfFileException) {
                return
            }
            if (input.isBlank()) continue
            if (input.trimStart().startsWith(":")) {
                if (!command(input.trim())) return
            } else {
                evaluate(input)
            }
        }
    }

    /** Runs a `:command`; returns false to quit. */
    private fun command(input: String): Boolean {
        val name = input.substringBefore(' ')
        val argument = input.substringAfter(' ', "").trim()
        when (name) {
            ":q", ":quit", ":exit" -> return false
            ":h", ":help", ":?" -> out.println(HELP)
            ":reset" -> {
                val old = repl
                old.thenAcceptAsync({ it.close() }, kernel)
                repl = CompletableFuture.supplyAsync(replFactory, kernel)
                out.println("Resetting the REPL state.")
            }
            ":load" -> load(argument)
            else -> printError("Unknown command $name. Try :help.")
        }
        out.flush()
        return true
    }

    private fun load(argument: String) {
        if (argument.isEmpty()) return printError(":load needs a file, e.g. :load script.kts")
        val path = Path(argument.removeSurrounding("\""))
        if (!path.exists()) return printError("No such file: $path")
        val snippets =
            if (path.toString().endsWith(NotebookStore.EXTENSION)) {
                NotebookFormat.parse(path.readText()).filter { it.type == CellType.KOTLIN }.map { it.source }
            } else {
                listOf(path.readText())
            }
        out.println("Loading $path…")
        for (snippet in snippets.filter { it.isNotBlank() }) {
            if (!evaluate(snippet)) return
        }
    }

    /** Runs [code] and prints its result; returns whether it succeeded. */
    private fun evaluate(code: String): Boolean {
        val engine = try {
            repl.get()
        } catch (e: ExecutionException) {
            printError("Kotlin failed to start: ${e.cause}")
            return false
        }
        val running = kernel.submit<ExecutionResult> { engine.execute(code) }
        val previousHandler = terminal.handle(Terminal.Signal.INT) { kernelThread?.interrupt() }
        val result = try {
            running.get()
        } catch (e: ExecutionException) {
            ExecutionResult.RuntimeError(KotlinRepl.renderThrowable(e.cause ?: e, null), false, emptyList())
        } finally {
            terminal.handle(Terminal.Signal.INT, previousHandler)
        }
        System.out.flush()
        show(result, code)
        out.flush()
        return result !is ExecutionResult.CompileError && result !is ExecutionResult.RuntimeError
    }

    private fun show(result: ExecutionResult, code: String) {
        val diagnostics = if (result is ExecutionResult.CompileError) result.diagnostics else result.warnings
        diagnostics.forEach { printDiagnostic(it, code) }
        when (result) {
            is ExecutionResult.Value -> printLine(
                AttributedStringBuilder()
                    .append(result.name)
                    .append(": ")
                    .styled(TYPE_STYLE, result.type)
                    .append(" = ")
                    .append(result.text),
            )
            is ExecutionResult.RuntimeError -> printError(if (result.interrupted) "Interrupted." else result.text)
            is ExecutionResult.NoValue, is ExecutionResult.CompileError -> Unit
        }
    }

    /** Prints a diagnostic like a compiler, with the offending line and a caret under the problem. */
    private fun printDiagnostic(diagnostic: Diagnostic, code: String) {
        val error = diagnostic.severity == Severity.ERROR
        val header = AttributedStringBuilder()
            .styled(if (error) ERROR_STYLE else WARNING_STYLE, if (error) "error: " else "warning: ")
            .append(diagnostic.message)
        val lines = code.lines()
        val line = diagnostic.line
        if (line != null && line in 1..lines.size) {
            if (lines.size > 1) header.styled(LOCATION_STYLE, " (line $line)")
            printLine(header)
            val text = lines[line - 1]
            val column = (diagnostic.column ?: 1).coerceIn(1, text.length + 1)
            val width = if (diagnostic.endLine == line && diagnostic.endColumn != null) {
                (diagnostic.endColumn - column).coerceAtLeast(1)
            } else {
                1
            }
            printLine(AttributedStringBuilder().append("  ").append(KotlinHighlighter.highlight(text)))
            printLine(
                AttributedStringBuilder()
                    .append("  " + " ".repeat(column - 1))
                    .styled(if (error) ERROR_STYLE else WARNING_STYLE, "^".repeat(width)),
            )
        } else {
            printLine(header)
        }
    }

    private fun printError(message: String) = printLine(AttributedStringBuilder().styled(ERROR_STYLE, message))

    private fun printLine(text: AttributedStringBuilder) {
        out.println(text.toAttributedString().toAnsi(terminal))
    }

    /** Keeps reading lines while the input is incomplete; two empty lines submit it anyway, to see the errors. */
    private inner class InputParser : Parser {
        override fun parse(line: String, cursor: Int, context: Parser.ParseContext): ParsedLine {
            if (context == Parser.ParseContext.ACCEPT_LINE && line.isNotBlank() && !line.trimStart().startsWith(":") &&
                !line.endsWith("\n\n") && isIncomplete(line)
            ) {
                throw EOFError(-1, -1, "Incomplete Kotlin input")
            }
            return KotlinParsedLine(line, cursor)
        }

        private fun isIncomplete(line: String): Boolean = try {
            repl.get().isIncomplete(line)
        } catch (e: ExecutionException) {
            false
        }
    }

    private inner class KotlinCompleter : Completer {
        override fun complete(reader: LineReader, line: ParsedLine, candidates: MutableList<Candidate>) {
            val engine = repl.getNow(null) ?: return
            for (item in engine.complete(null, line.line(), line.cursor())) {
                // A candidate that opens a call or a lambda is unfinished, so no space is added after it.
                val finished = !item.text.endsWith("(") && !item.text.endsWith(" ")
                candidates += Candidate(item.text, item.displayText, null, item.tail.ifEmpty { null }, null, null, finished)
            }
        }
    }

    /** Treats the identifier before the cursor as the word to complete. */
    private class KotlinParsedLine(private val line: String, private val cursor: Int) : CompletingParsedLine {
        private val wordStart = run {
            var start = cursor
            while (start > 0 && Character.isJavaIdentifierPart(line[start - 1])) start--
            start
        }
        private val word = line.substring(wordStart, cursor)

        override fun word() = word
        override fun wordCursor() = word.length
        override fun wordIndex() = 0
        override fun words() = listOf(word)
        override fun line() = line
        override fun cursor() = cursor
        override fun escape(candidate: CharSequence, complete: Boolean) = candidate
        override fun rawWordCursor() = word.length
        override fun rawWordLength() = word.length
    }

    companion object {
        const val PROMPT = "kotlin> "
        const val CONTINUATION_PROMPT = "      | "

        private val TYPE_STYLE = AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN)
        private val ERROR_STYLE = AttributedStyle.BOLD.foreground(AttributedStyle.RED)
        private val WARNING_STYLE = AttributedStyle.BOLD.foreground(AttributedStyle.YELLOW)
        private val LOCATION_STYLE = AttributedStyle.DEFAULT.foreground(AttributedStyle.BLACK + AttributedStyle.BRIGHT)

        private val HELP = """
            |The REPL has several commands available:
            |
            |:help                    print this summary
            |:load <path>             run a .kts script, or the Kotlin cells of a .kot notebook
            |:reset                   forget everything defined so far
            |:quit                    exit the REPL
            |
            |Tab completes, Ctrl+C interrupts a running snippet or discards the input, and Ctrl+D exits.
            |Input continues on the next line while it is incomplete; press Enter on two empty lines to submit it anyway.
            |Use Maven libraries with @file:DependsOn("group:artifact:version").
            """.trimMargin()
    }
}

/** Highlights Kotlin with the compiler's own lexer. */
object KotlinHighlighter : Highlighter {
    private val KEYWORD = AttributedStyle.BOLD.foreground(AttributedStyle.BLUE + AttributedStyle.BRIGHT)
    private val STRING = AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN)
    private val ESCAPE = AttributedStyle.BOLD.foreground(AttributedStyle.GREEN)
    private val NUMBER = AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN)
    private val COMMENT = AttributedStyle.DEFAULT.italic().foreground(AttributedStyle.BLACK + AttributedStyle.BRIGHT)
    private val ANNOTATION = AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW)
    private val COMMAND = AttributedStyle.BOLD

    // The lexer reports modifiers as identifiers; these are the ones that are almost never used as names.
    private val MODIFIERS = setOf(
        "abstract", "actual", "by", "catch", "companion", "const", "constructor", "crossinline", "data", "enum",
        "expect", "external", "final", "finally", "import", "infix", "init", "inline", "inner", "internal",
        "lateinit", "noinline", "open", "operator", "out", "override", "private", "protected", "public", "reified",
        "sealed", "suspend", "tailrec", "vararg", "where",
    )
    private val STRING_TOKENS = setOf(KtTokens.OPEN_QUOTE, KtTokens.CLOSING_QUOTE, KtTokens.REGULAR_STRING_PART, KtTokens.CHARACTER_LITERAL)
    private val TEMPLATE_TOKENS = setOf(
        KtTokens.ESCAPE_SEQUENCE, KtTokens.SHORT_TEMPLATE_ENTRY_START, KtTokens.LONG_TEMPLATE_ENTRY_START,
        KtTokens.LONG_TEMPLATE_ENTRY_END,
    )

    override fun highlight(reader: LineReader, buffer: String): AttributedString =
        if (buffer.trimStart().startsWith(":")) AttributedString(buffer, COMMAND) else highlight(buffer).toAttributedString()

    fun highlight(code: String): AttributedStringBuilder {
        val result = AttributedStringBuilder()
        val lexer = KotlinLexer()
        lexer.start(code)
        var inAnnotation = false
        while (lexer.tokenType != null) {
            val type = lexer.tokenType
            val text = lexer.tokenText
            // `@file:DependsOn` and `@Suppress` are colored as a whole, up to the arguments.
            inAnnotation = type == KtTokens.AT ||
                (inAnnotation && (type == KtTokens.IDENTIFIER || type == KtTokens.COLON || type == KtTokens.DOT))
            val style = when {
                inAnnotation -> ANNOTATION
                type in KtTokens.KEYWORDS || (type == KtTokens.IDENTIFIER && text in MODIFIERS) -> KEYWORD
                type in TEMPLATE_TOKENS -> ESCAPE
                type in STRING_TOKENS -> STRING
                type == KtTokens.INTEGER_LITERAL || type == KtTokens.FLOAT_LITERAL -> NUMBER
                type in KtTokens.COMMENTS -> COMMENT
                else -> AttributedStyle.DEFAULT
            }
            result.styled(style, text)
            lexer.advance()
        }
        return result
    }
}
