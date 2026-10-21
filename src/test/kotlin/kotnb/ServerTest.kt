package kotnb

import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class ServerTest {
    private val root = Files.createTempDirectory("kotnb-server")

    private fun withServer(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val sessions = SessionManager()
        application { notebookModule(NotebookStore(root), sessions, loopbackOnly = true) }
        try {
            block()
        } finally {
            sessions.close()
        }
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ContentNegotiation) { json(protocolJson) }
        install(WebSockets) { contentConverter = KotlinxWebsocketSerializationConverter(protocolJson) }
    }

    @Test
    fun `creates, saves, lists, renames and deletes notebooks`() = withServer {
        val client = jsonClient()
        val created = client.post("/api/notebook") {
            contentType(ContentType.Application.Json)
            setBody(CreateNotebookRequest("abc", listOf(Cell(CellType.KOTLIN, "1 + 1"))))
        }
        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals(HttpStatusCode.Conflict, client.post("/api/notebook") {
            contentType(ContentType.Application.Json)
            setBody(CreateNotebookRequest("abc"))
        }.status)

        val cells = listOf(Cell(CellType.MARKDOWN, "# Hi"), Cell(CellType.KOTLIN, "val x = 1"))
        client.put("/api/notebook?name=abc") {
            contentType(ContentType.Application.Json)
            setBody(SaveNotebookRequest(cells))
        }
        assertEquals(NotebookFormat.serialize(cells), root.resolve("abc.kot").readText())
        assertEquals("""{"name":"abc","cells":[{"type":"markdown","source":"# Hi"},{"type":"kotlin","source":"val x = 1"}]}""",
            client.get("/api/notebook?name=abc").bodyAsText())

        client.post("/api/notebook/rename") {
            contentType(ContentType.Application.Json)
            setBody(RenameNotebookRequest("abc", "team/xyz"))
        }
        assertEquals("""[{"name":"team/xyz"}]""", client.get("/api/notebooks").bodyAsText())
        assertEquals(HttpStatusCode.NotFound, client.get("/api/notebook?name=abc").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/notebook?name=../secret").status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/notebook?name=team/xyz").status)
        assertEquals("[]", client.get("/api/notebooks").bodyAsText())
    }

    @Test
    fun `rejects requests from other origins`() = withServer {
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/notebooks") { header(HttpHeaders.Origin, "https://evil.example") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/notebooks") { header(HttpHeaders.Host, "evil.example") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/notebooks") { header(HttpHeaders.Origin, "http://localhost") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/").status)
    }

    @Test
    fun `runs cells over the websocket and completes code`() = withServer {
        val client = jsonClient()
        client.webSocket("/ws?name=demo") {
            suspend fun next(): ServerMessage = withTimeout(60.seconds) {
                protocolJson.decodeFromString<ServerMessage>((incoming.receive() as Frame.Text).readText())
            }
            suspend fun awaitIdle() {
                while (true) if ((next() as? KernelStatus)?.state == KernelState.IDLE) return
            }
            suspend fun collectUntilFinished(cellId: String): List<ServerMessage> {
                val messages = mutableListOf<ServerMessage>()
                while (true) {
                    val message = next()
                    messages += message
                    if (message is CellFinished && message.cellId == cellId) return messages
                }
            }

            awaitIdle()
            sendSerialized(RunCells(listOf(CellSource("a", "println(\"hi\")\nval answer = 42\nanswer"))) as ClientMessage)
            val first = collectUntilFinished("a")
            assertEquals(CellStream("a", OutputStreamKind.STDOUT, "hi\n"), first.filterIsInstance<CellStream>().single())
            assertEquals(CellResult("a", "42", "Int", html = false), first.filterIsInstance<CellResult>().single())
            assertEquals(CellStatus.OK, first.filterIsInstance<CellFinished>().single().status)
            assertEquals(1, first.filterIsInstance<CellStarted>().single().executionCount)

            // A failing cell cancels the cells queued after it.
            sendSerialized(RunCells(listOf(CellSource("b", "undefinedThing"), CellSource("c", "answer"))) as ClientMessage)
            val second = collectUntilFinished("c")
            val finished = second.filterIsInstance<CellFinished>().associateBy { it.cellId }
            assertEquals(CellStatus.ERROR, finished.getValue("b").status)
            assertContains(finished.getValue("b").diagnostics.single().message, "Unresolved reference")
            assertEquals(CellStatus.CANCELLED, finished.getValue("c").status)

            val completion = client.post("/api/complete") {
                contentType(ContentType.Application.Json)
                setBody(CompletionRequest("demo", "d", "answer.toSt", 11))
            }
            assertContains(completion.bodyAsText(), "\"text\":\"toString()\"")
        }
    }
}
