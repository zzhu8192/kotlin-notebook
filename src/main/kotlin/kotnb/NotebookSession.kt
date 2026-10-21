package kotnb

import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The browser connections viewing one notebook. It outlives kernel restarts and follows the notebook on rename. */
class NotebookChannel {
    private val listeners = CopyOnWriteArraySet<(ServerMessage) -> Unit>()

    fun subscribe(listener: (ServerMessage) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    fun broadcast(message: ServerMessage) = listeners.forEach { it(message) }
}

/**
 * The kernel for one notebook: a [KotlinRepl] plus a queue of cells to run on a dedicated thread.
 *
 * As in Jupyter, a failing or interrupted cell cancels the cells queued after it.
 */
class NotebookSession(private val channel: NotebookChannel) : AutoCloseable {
    private class Job(val cellId: String, val code: String)

    private val lock = Any()
    private val pending = ArrayDeque<Job>()
    private var running: Job? = null
    private var runningThread: Thread? = null
    private var executionCount = 0
    private var closed = false

    @Volatile
    var state: KernelState = KernelState.STARTING
        private set
    private var failure: String? = null

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        // Deep recursion in user code and in the compiler needs more than the default stack.
        Thread(null, runnable, "kotnb-kernel", 64L * 1024 * 1024).apply { isDaemon = true }
    }
    private val repl = CompletableFuture<KotlinRepl>()

    init {
        // Announce the new kernel, so browsers stop showing the state of the one it replaces.
        emit(status())
        executor.execute {
            try {
                repl.complete(KotlinRepl())
                setState(KernelState.IDLE)
            } catch (e: Throwable) {
                log.error("Kotlin REPL failed to start", e)
                repl.completeExceptionally(e)
                failure = e.toString()
                setState(KernelState.FAILED)
            }
        }
    }

    fun status(): KernelStatus = KernelStatus(state, failure)

    fun run(cells: List<CellSource>) {
        synchronized(lock) {
            if (closed) return
            for (cell in cells) {
                val job = Job(cell.cellId, cell.code)
                pending += job
                emit(CellQueued(cell.cellId))
                executor.execute { runNext() }
            }
        }
    }

    /** Cancels queued cells and interrupts the running one. A busy loop that never checks for interrupts keeps running. */
    fun interrupt() {
        synchronized(lock) {
            cancelPendingLocked()
            runningThread?.interrupt()
        }
    }

    /** Completes code in cell [cellId]. While the kernel is starting, this waits for it rather than offering nothing. */
    fun complete(cellId: String, code: String, cursor: Int): List<CompletionItem> {
        val engine = try {
            repl.get(KERNEL_START_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: Exception) {
            return emptyList()
        }
        return engine.complete(cellId, code, cursor)
    }

    /**
     * Stops the kernel. The REPL is disposed once the running cell returns; a cell that never returns keeps its
     * thread, since the JVM can no longer kill threads.
     */
    override fun close() {
        synchronized(lock) {
            if (closed) return
            cancelPendingLocked()
            running?.let { emit(CellFinished(it.cellId, CellStatus.INTERRUPTED, 0)) }
            runningThread?.interrupt()
            closed = true
        }
        executor.execute { repl.getNow(null)?.close() }
        executor.shutdown()
    }

    private fun runNext() {
        val job = synchronized(lock) {
            if (closed) return
            val next = pending.removeFirstOrNull() ?: return
            running = next
            runningThread = Thread.currentThread()
            executionCount++
            emit(CellStarted(next.cellId, executionCount))
            next
        }
        setState(KernelState.BUSY)
        val startedAt = System.nanoTime()
        val output = BufferedCellOutput { stream, text -> emit(CellStream(job.cellId, stream, text)) }
        val result = try {
            val engine = repl.get()
            OutputRouter.withSink(output) { engine.execute(job.code, job.cellId) }
        } catch (e: Throwable) {
            log.error("Kernel failed to run a cell", e)
            ExecutionResult.RuntimeError("Kernel error: ${KotlinRepl.renderThrowable(e, null)}", false, emptyList())
        } finally {
            output.flush()
        }
        val durationMs = (System.nanoTime() - startedAt) / 1_000_000

        synchronized(lock) {
            running = null
            runningThread = null
            Thread.interrupted() // An interrupt that arrived as the cell finished must not leak into the next one.
            if (closed) return
            val status = when (result) {
                is ExecutionResult.Value -> {
                    emit(CellResult(job.cellId, result.text, result.type, result.isHtml))
                    CellStatus.OK
                }
                is ExecutionResult.NoValue -> CellStatus.OK
                is ExecutionResult.CompileError -> CellStatus.ERROR
                is ExecutionResult.RuntimeError -> {
                    emit(CellError(job.cellId, result.text))
                    if (result.interrupted) CellStatus.INTERRUPTED else CellStatus.ERROR
                }
            }
            val diagnostics = if (result is ExecutionResult.CompileError) result.diagnostics else result.warnings
            emit(CellFinished(job.cellId, status, durationMs, diagnostics))
            if (status != CellStatus.OK) cancelPendingLocked()
            if (pending.isEmpty()) setState(KernelState.IDLE)
        }
    }

    private fun cancelPendingLocked() {
        while (true) {
            val job = pending.removeFirstOrNull() ?: break
            emit(CellFinished(job.cellId, CellStatus.CANCELLED, 0))
        }
    }

    private fun setState(newState: KernelState) {
        state = newState
        emit(status())
    }

    private fun emit(message: ServerMessage) {
        if (closed) return
        try {
            channel.broadcast(message)
        } catch (e: Exception) {
            log.warn("Failed to deliver a kernel message", e)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(NotebookSession::class.java)
        private const val KERNEL_START_TIMEOUT_SECONDS = 60L
    }
}

/** Owns one kernel per open notebook. */
class SessionManager : AutoCloseable {
    private val channels = ConcurrentHashMap<String, NotebookChannel>()
    private val sessions = ConcurrentHashMap<String, NotebookSession>()

    init {
        OutputRouter.install() // Kernels rely on it to capture what cells print.
    }

    fun channel(notebook: String): NotebookChannel = channels.computeIfAbsent(notebook) { NotebookChannel() }

    fun session(notebook: String): NotebookSession =
        sessions.computeIfAbsent(notebook) { NotebookSession(channel(notebook)) }

    fun restart(notebook: String): NotebookSession {
        sessions.remove(notebook)?.close()
        return session(notebook)
    }

    fun rename(from: String, to: String) {
        channels.remove(from)?.let { channels[to] = it }
        sessions.remove(from)?.let { sessions[to] = it }
    }

    fun delete(notebook: String) {
        sessions.remove(notebook)?.close()
    }

    override fun close() {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }
}
