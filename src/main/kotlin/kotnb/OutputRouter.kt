package kotnb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Serializable
enum class OutputStreamKind {
    @SerialName("stdout")
    STDOUT,

    @SerialName("stderr")
    STDERR,
}

/** Receives text a cell prints to stdout or stderr. */
fun interface OutputSink {
    fun write(stream: OutputStreamKind, text: String)
}

/**
 * Replaces [System.out] and [System.err] with streams that send each write to the [OutputSink] bound to the calling
 * thread. The binding is inherited by threads a cell starts, so their output reaches the cell too. Threads with no
 * sink, such as server threads, write to the original streams.
 */
object OutputRouter {
    private val current = InheritableThreadLocal<OutputSink?>()
    private val originalOut: PrintStream = System.out
    private val originalErr: PrintStream = System.err

    @Volatile
    private var installed = false

    @Synchronized
    fun install() {
        if (installed) return
        System.setOut(PrintStream(RoutingOutputStream(OutputStreamKind.STDOUT, originalOut), true, Charsets.UTF_8))
        System.setErr(PrintStream(RoutingOutputStream(OutputStreamKind.STDERR, originalErr), true, Charsets.UTF_8))
        // Nothing can type into a notebook's stdin, so give reads an immediate EOF instead of a forever block.
        System.setIn(InputStream.nullInputStream())
        installed = true
    }

    fun <T> withSink(sink: OutputSink, block: () -> T): T {
        val previous = current.get()
        current.set(sink)
        try {
            return block()
        } finally {
            System.out.flush()
            System.err.flush()
            current.set(previous)
        }
    }

    private class RoutingOutputStream(private val kind: OutputStreamKind, private val fallback: PrintStream) :
        OutputStream() {
        private class DecoderState {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)!!

            /** Bytes of a multi-byte character split across writes. */
            var leftover = ByteArray(0)
        }

        private val decoders = ThreadLocal.withInitial { DecoderState() }

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len == 0) return
            val state = decoders.get()
            val input =
                if (state.leftover.isEmpty()) ByteBuffer.wrap(b, off, len)
                else ByteBuffer.allocate(state.leftover.size + len).put(state.leftover).put(b, off, len).flip()
            val chars = CharBuffer.allocate(input.remaining())
            state.decoder.decode(input, chars, false)
            state.leftover = ByteArray(input.remaining()).also { input.get(it) }
            chars.flip()
            val text = chars.toString()
            if (text.isEmpty()) return
            val sink = current.get()
            if (sink != null) sink.write(kind, text) else fallback.print(text)
        }

        override fun flush() {
            if (current.get() == null) fallback.flush()
        }
    }
}

/**
 * Buffers a cell's output and forwards it in batches, at most every [FLUSH_INTERVAL_MS], so a cell printing in a
 * tight loop does not flood the browser. Output past [MAX_CHARS] is dropped with a single notice.
 */
class BufferedCellOutput(private val emit: (OutputStreamKind, String) -> Unit) : OutputSink {
    private val pending = StringBuilder()
    private var pendingKind: OutputStreamKind? = null
    private var total = 0
    private var truncated = false
    private var flushScheduled = false

    @Synchronized
    override fun write(stream: OutputStreamKind, text: String) {
        if (truncated) return
        if (pendingKind != null && pendingKind != stream) flushLocked()
        val room = MAX_CHARS - total
        if (text.length > room) {
            pending.append(text, 0, room)
            pending.append("\n… output truncated after ${MAX_CHARS / 1_000_000} million characters\n")
            truncated = true
        } else {
            pending.append(text)
        }
        total += text.length
        pendingKind = stream
        if (!flushScheduled) {
            flushScheduled = true
            scheduler.schedule({ flush() }, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized
    fun flush() {
        flushScheduled = false
        flushLocked()
    }

    private fun flushLocked() {
        val kind = pendingKind ?: return
        // println ends lines with \r\n on Windows; the browser only needs \n.
        if (pending.isNotEmpty()) emit(kind, pending.toString().replace("\r\n", "\n"))
        pending.setLength(0)
        pendingKind = null
    }

    companion object {
        const val FLUSH_INTERVAL_MS = 50L
        const val MAX_CHARS = 2_000_000

        private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "kotnb-output-flusher").apply { isDaemon = true }
        }
    }
}
