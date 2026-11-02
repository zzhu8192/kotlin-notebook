package kotnb

import org.jline.terminal.Size
import org.jline.terminal.TerminalBuilder
import org.jline.utils.AttributedString
import org.jline.utils.AttributedStyle
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class CliTest {
    private val keyboard = PipedOutputStream()
    private val screen = ByteArrayOutputStream()
    private val terminal = TerminalBuilder.builder()
        .system(false)
        .streams(PipedInputStream(keyboard), screen)
        .type("xterm-256color")
        .size(Size.of(160, 50))
        .encoding(Charsets.UTF_8)
        .stdinEncoding(Charsets.UTF_8)
        .stdoutEncoding(Charsets.UTF_8)
        .build()
    private val cli = thread(name = "cli") { Cli(terminal, historyFile = null).run() }

    /** How much of the screen earlier assertions consumed, so each one only looks at new output. */
    private var seen = 0

    @AfterTest
    fun tearDown() {
        if (cli.isAlive) type(":quit\r")
        cli.join(30_000)
        terminal.close()
    }

    private fun type(keys: String) {
        keyboard.write(keys.toByteArray())
        keyboard.flush()
    }

    private fun plainScreen() = AttributedString.fromAnsi(screen.toString(Charsets.UTF_8)).toString()

    private fun expect(text: String, timeoutMs: Long = 60_000): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val output = plainScreen()
            val index = output.indexOf(text, seen)
            if (index >= 0) {
                val consumed = output.substring(seen, index + text.length)
                seen = index + text.length
                return consumed
            }
            Thread.sleep(20)
        }
        fail("Expected '$text' in new output:\n${plainScreen().substring(seen)}")
    }

    @Test
    fun `evaluates expressions and multi-line definitions`() {
        expect("Type in expressions for evaluation. Or try :help.")
        type("1 + 1\r")
        expect("res1: Int = 2")
        type("fun twice(n: Int) =\r")
        expect(Cli.CONTINUATION_PROMPT)
        type("n * 2\r")
        type("twice(res1 * 10 + 1)\r")
        expect(": Int = 42")
        type("println(\"São Paulo\")\r")
        expect("São Paulo")
    }

    @Test
    fun `completes with Tab`() {
        expect("Or try :help.")
        type("val numbers = listOf(3, 1, 2)\r")
        type("numbers.sorte\t") // Completes the common prefix and lists the candidates.
        expect("sortedDescending()")
        type("Desc\t\r") // Now unique, so Tab completes the whole call.
        expect(": List<Int> = [3, 2, 1]")
        type("numbers.firstOrN\t") // Two overloads share the name, so Tab stops there and lists both.
        expect("firstOrNull((Int) -> Boolean)")
        type("()\r")
        expect(" = 3")
    }

    @Test
    fun `reports errors with a caret`() {
        expect("Or try :help.")
        type("val ok = 1\rok + missing\r")
        val output = expect("^^^^^^^")
        assertTrue("error: Unresolved reference 'missing'." in output, output)
        assertTrue("  ok + missing" in output, output)
        type("error(\"boom\")\r")
        expect("java.lang.IllegalStateException: boom")
        type("ok\r")
        expect("Int = 1")
    }

    @Test
    fun `Ctrl+C interrupts a running snippet and discards input at the prompt`() {
        expect("Or try :help.")
        // Warm up the REPL compiler so subsequent snippets evaluate immediately.
        type("1 + 1\r")
        expect("res1: Int = 2")
        type("Thread.sleep(60_000)\r")
        Thread.sleep(1500)
        // Simulate OS interrupt signal to the terminal while the snippet is sleeping.
        terminal.raise(org.jline.terminal.Terminal.Signal.INT)
        expect("Interrupted.")
        type("val discarded = 1\u0003")
        type("\"still running\"\r")
        expect("String = still running")
        type("discarded\r")
        expect("Unresolved reference 'discarded'")
    }

    @Test
    fun `commands load notebooks, reset state and quit`() {
        val notebook = Files.createTempFile("cli", ".kot")
        notebook.writeText(
            NotebookFormat.serialize(
                listOf(Cell(CellType.MARKDOWN, "# Setup"), Cell(CellType.KOTLIN, "val loaded = 7"), Cell(CellType.KOTLIN, "loaded * 6")),
            ),
        )
        expect("Or try :help.")
        type(":load $notebook\r")
        expect(": Int = 42")
        type(":reset\r")
        expect("Resetting the REPL state.")
        type("loaded\r")
        expect("Unresolved reference 'loaded'")
        type(":nope\r")
        expect("Unknown command :nope. Try :help.")
        type(":quit\r")
        cli.join(30_000)
        assertFalse(cli.isAlive)
    }

    @Test
    fun `highlights Kotlin like the compiler sees it`() {
        val code = "@file:DependsOn(\"a:b:1\")\nval x = 42 // note\n\"hi \$x\""
        val highlighted = KotlinHighlighter.highlight(code).toAttributedString()
        fun styleOf(token: String) = highlighted.styleAt(code.indexOf(token))
        assertEquals(styleOf("val"), styleOf("val").also { assertTrue(it.style and BOLD != 0L) })
        assertTrue(styleOf("val") != AttributedStyle.DEFAULT)
        assertTrue(styleOf("DependsOn") == styleOf("@file"))
        assertTrue(styleOf("42") != AttributedStyle.DEFAULT)
        assertTrue(styleOf("// note") != AttributedStyle.DEFAULT)
        assertTrue(styleOf("\"hi") != styleOf("\$x"))
        assertEquals(AttributedStyle.DEFAULT, styleOf("x ="))
    }

    private companion object {
        const val BOLD = 0x00000001L
    }
}
