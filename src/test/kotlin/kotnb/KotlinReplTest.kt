package kotnb

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KotlinReplTest {
    private val repl = KotlinRepl()

    @AfterTest
    fun tearDown() = repl.close()

    private fun value(code: String): ExecutionResult.Value = assertIs<ExecutionResult.Value>(repl.execute(code))

    @Test
    fun `state is shared between snippets`() {
        assertIs<ExecutionResult.NoValue>(repl.execute("val x = 41\nfun inc(n: Int) = n + 1"))
        val result = value("inc(x)")
        assertEquals("42", result.text)
        assertEquals("Int", result.type)
    }

    @Test
    fun `classes declared in one snippet work in the next`() {
        value("data class Point(val x: Int, val y: Int)\nPoint(1, 2)")
        val result = value("Point(1, 2).copy(y = 5)")
        assertEquals("Point(x=1, y=5)", result.text)
        assertEquals("Point", result.type)
    }

    @Test
    fun `supports Kotlin 2 language features`() {
        assertEquals("big", value("val v: Any = 5\nwhen (v) { is Int if v > 3 -> \"big\"; else -> \"small\" }").text)
        assertEquals("\$x", value("\$\$\"\$x\"").text)
        val version = value("KotlinVersion.CURRENT").text
        val (major, minor) = version.split('.').map(String::toInt)
        assertTrue(major > 2 || (major == 2 && minor >= 4), "compiler $version is older than 2.4")
    }

    @Test
    fun `syntax errors are reported with positions and do not break later snippets`() {
        for (bad in listOf("1 +", "val = 3", "fun f( {", "println(\"a\"")) {
            val error = assertIs<ExecutionResult.CompileError>(repl.execute(bad), bad)
            assertTrue(error.diagnostics.all { it.severity == Severity.ERROR && it.line != null }, "$bad: $error")
        }
        assertEquals("3", value("1 + 2").text)
    }

    @Test
    fun `type errors are reported and do not break later snippets`() {
        val error = assertIs<ExecutionResult.CompileError>(repl.execute("val y: Int = \"s\""))
        val diagnostic = error.diagnostics.single()
        assertContains(diagnostic.message, "type mismatch", ignoreCase = true)
        assertEquals(1, diagnostic.line)
        assertTrue(diagnostic.column!! in 1..16, "column ${diagnostic.column}")
        assertEquals("2", value("1 + 1").text)
    }

    @Test
    fun `runtime errors show the exception and the cell line`() {
        val error = assertIs<ExecutionResult.RuntimeError>(repl.execute("val a = 1\nerror(\"boom\")"))
        assertTrue(error.text.startsWith("java.lang.IllegalStateException: boom"), error.text)
        assertContains(error.text, "cell line 2")
        assertTrue("org.jetbrains.kotlin" !in error.text, error.text)
        assertEquals("ok", value("\"ok\"").text)
    }

    @Test
    fun `arrays and html render specially`() {
        assertEquals("[1, 2, 3]", value("intArrayOf(1, 2, 3)").text)
        val html = value("html(\"<b>hi</b>\")")
        assertTrue(html.isHtml)
        assertEquals("<b>hi</b>", html.text)
    }

    @Test
    fun `interrupting stops a sleeping cell`() {
        val kernelThread = Thread.currentThread()
        Thread {
            Thread.sleep(1500)
            kernelThread.interrupt()
        }.start()
        val error = assertIs<ExecutionResult.RuntimeError>(repl.execute("Thread.sleep(60_000)"))
        assertTrue(error.interrupted)
        assertEquals("1", value("1").text)
    }

    private fun completions(code: String, cellId: String = "editing") =
        repl.complete(cellId, code, code.length).map { it.text }

    @Test
    fun `completes declarations from earlier cells`() {
        repl.execute("val greeting = \"hello\"\nfun shout(s: String) = s.uppercase()", "a")
        assertContains(repl.complete("b", "greeting.subs", 13).map { it.displayText }, "substring(Int)")
        assertContains(completions("sho"), "shout(")
        val members = repl.complete("b", "greeting.", 9)
        assertTrue(members.isNotEmpty() && members.none { it.icon == "keyword" }, "keywords offered after a dot")
    }

    @Test
    fun `completion follows shadowing between cells`() {
        repl.execute("val x = 1", "a")
        repl.execute("val x = \"now a string\"", "b")
        assertContains(completions("x.leng"), "length")
        // Re-running a cell with new code replaces its declarations.
        repl.execute("val y = 1", "c")
        repl.execute("val y = listOf(1)", "c")
        assertContains(completions("y.firstOrN"), "firstOrNull()")
    }

    @Test
    fun `completion sees declarations written with Kotlin 2 syntax`() {
        repl.execute("val v: Any = 5\nval label = when (v) { is Int if v > 3 -> \"big\"; else -> \"small\" }", "a")
        assertContains(completions("lab"), "label")
    }

    @Test
    fun `cells do not see the server's own libraries`() {
        val error = assertIs<ExecutionResult.CompileError>(repl.execute("io.ktor.server.application.Application::class"))
        assertContains(error.diagnostics.single().message, "Unresolved reference")
        assertIs<ExecutionResult.CompileError>(repl.execute("kotnb.KotlinRepl::class"))
        assertEquals("[1, 2]", value("kotlinx.coroutines.runBlocking { listOf(1, 2) }").text)
    }

    @Test
    fun `resolves maven dependencies`() {
        useOperatingSystemTrustStore()
        val result = value(
            """
            @file:DependsOn("org.apache.commons:commons-lang3:3.17.0")
            import org.apache.commons.lang3.StringUtils
            StringUtils.reverse("abc")
            """.trimIndent(),
        )
        assertEquals("cba", result.text)
        assertEquals("olleh", value("org.apache.commons.lang3.StringUtils.reverse(\"hello\")").text)
        assertContains(completions("org.apache.commons.lang3.StringUtils.revers"), "reverse(")
    }
}
