package kotnb

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.nio.file.Path
import kotlin.system.exitProcess

private const val USAGE = """Usage:
  kotlin-notebook                 start an interactive Kotlin REPL
  kotlin-notebook serve [options] start the notebook server

Options for serve:
  --port       HTTP port to listen on (default 8080)
  --host       Address to bind (default 127.0.0.1). Cells run arbitrary code, so only expose this to people you trust.
  --notebooks  Directory holding .kot notebooks (default ./notebooks)"""

fun main(args: Array<String>) {
    useOperatingSystemTrustStore()
    when (args.firstOrNull()) {
        null -> repl()
        "serve" -> serve(args.drop(1))
        "-h", "--help", "help" -> println(USAGE)
        else -> usageError("Unknown command '${args.first()}'")
    }
}

private fun repl() {
    // Log only real failures, so library chatter does not interleave with the REPL.
    (LoggerFactory.getILoggerFactory() as LoggerContext).getLogger(Logger.ROOT_LOGGER_NAME).level = Level.ERROR
    val terminal = TerminalBuilder.builder()
        .system(true)
        .dumb(true) // Piped input gets a plain terminal without a warning.
        .nativeSignals(true)
        .signalHandler(Terminal.SignalHandler.SIG_IGN) // Ctrl+C must not kill the JVM; the REPL handles it.
        // Consoles get Unicode from JLine directly; pipes and files would otherwise get the legacy code page.
        .apply {
            if (System.console() == null) {
                encoding(Charsets.UTF_8)
                // These default to the stdin/stdout.encoding properties, which win over encoding() otherwise.
                stdinEncoding(Charsets.UTF_8)
                stdoutEncoding(Charsets.UTF_8)
                stderrEncoding(Charsets.UTF_8)
            }
        }
        .build()
    terminal.use { Cli(it).run() }
    exitProcess(0) // Snippets may have started non-daemon threads.
}

private fun serve(args: List<String>) {
    var port = 8080
    var host = "127.0.0.1"
    var notebooks = "notebooks"
    val remaining = args.iterator()
    while (remaining.hasNext()) {
        when (val arg = remaining.next()) {
            "--port" -> port = remaining.valueOf(arg).toIntOrNull() ?: usageError("--port needs a number")
            "--host" -> host = remaining.valueOf(arg)
            "--notebooks" -> notebooks = remaining.valueOf(arg)
            "-h", "--help" -> {
                println(USAGE)
                return
            }
            else -> usageError("Unknown argument '$arg'")
        }
    }

    val store = NotebookStore(Path.of(notebooks))
    val sessions = SessionManager()
    val loopback = InetAddress.getByName(host).isLoopbackAddress
    val server = embeddedServer(Netty, port = port, host = host) {
        notebookModule(store, sessions, loopbackOnly = loopback)
    }
    val shownHost = if (loopback) "localhost" else host
    println("Kotlin Notebook ${KotlinVersion.CURRENT} at http://$shownHost:$port, notebooks in ${store.root}")
    server.start(wait = true)
}

/**
 * On Windows, trust the certificates Windows trusts, so `@file:DependsOn` works behind TLS-inspecting proxies that
 * browsers already accept. An explicit `-Djavax.net.ssl.trustStoreType` wins.
 */
internal fun useOperatingSystemTrustStore() {
    val windows = System.getProperty("os.name").startsWith("Windows")
    if (windows && System.getProperty("javax.net.ssl.trustStoreType") == null) {
        System.setProperty("javax.net.ssl.trustStoreType", "Windows-ROOT")
    }
}

private fun Iterator<String>.valueOf(flag: String): String = if (hasNext()) next() else usageError("$flag needs a value")

private fun usageError(message: String): Nothing {
    System.err.println("$message\n\n$USAGE")
    exitProcess(2)
}
