package kotnb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val protocolJson = Json {
    classDiscriminator = "type"
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}

// ---- REST payloads ----

@Serializable
data class ServerInfo(val kotlinVersion: String, val javaVersion: String, val notebookDirectory: String)

@Serializable
data class NotebookSummary(val name: String)

@Serializable
data class NotebookDocument(val name: String, val cells: List<Cell>)

@Serializable
data class SaveNotebookRequest(val cells: List<Cell>)

@Serializable
data class CreateNotebookRequest(val name: String, val cells: List<Cell> = emptyList())

@Serializable
data class RenameNotebookRequest(val from: String, val to: String)

@Serializable
data class CompletionRequest(val notebook: String, val cellId: String, val code: String, val cursor: Int)

@Serializable
data class CompletionResponse(val items: List<CompletionItem>)

@Serializable
data class ErrorResponse(val error: String)

// ---- WebSocket messages from the browser ----

@Serializable
sealed interface ClientMessage

@Serializable
data class CellSource(val cellId: String, val code: String)

/** Queues [cells] to run in order. */
@Serializable
@SerialName("run")
data class RunCells(val cells: List<CellSource>) : ClientMessage

@Serializable
@SerialName("interrupt")
data object InterruptKernel : ClientMessage

@Serializable
@SerialName("restart")
data object RestartKernel : ClientMessage

// ---- WebSocket messages to the browser ----

@Serializable
enum class KernelState {
    @SerialName("starting")
    STARTING,

    @SerialName("idle")
    IDLE,

    @SerialName("busy")
    BUSY,

    @SerialName("failed")
    FAILED,
}

@Serializable
enum class CellStatus {
    @SerialName("ok")
    OK,

    @SerialName("error")
    ERROR,

    @SerialName("interrupted")
    INTERRUPTED,

    @SerialName("cancelled")
    CANCELLED,
}

@Serializable
sealed interface ServerMessage

@Serializable
@SerialName("kernel")
data class KernelStatus(val state: KernelState, val message: String? = null) : ServerMessage

@Serializable
@SerialName("queued")
data class CellQueued(val cellId: String) : ServerMessage

@Serializable
@SerialName("started")
data class CellStarted(val cellId: String, val executionCount: Int) : ServerMessage

@Serializable
@SerialName("stream")
data class CellStream(val cellId: String, val stream: OutputStreamKind, val text: String) : ServerMessage

@Serializable
@SerialName("result")
data class CellResult(val cellId: String, val text: String, val valueType: String, val html: Boolean) : ServerMessage

@Serializable
@SerialName("error")
data class CellError(val cellId: String, val text: String) : ServerMessage

@Serializable
@SerialName("finished")
data class CellFinished(
    val cellId: String,
    val status: CellStatus,
    val durationMs: Long,
    val diagnostics: List<Diagnostic> = emptyList(),
) : ServerMessage
