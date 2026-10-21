package kotnb

import io.ktor.http.*
import io.ktor.serialization.kotlinx.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import java.net.URI
import kotlin.time.Duration.Companion.seconds

private val serverLog = LoggerFactory.getLogger("kotnb.Server")

/**
 * @param loopbackOnly when the server listens on a loopback address, also reject requests whose `Host` is not a
 *   loopback name, which defeats DNS rebinding attacks from web pages.
 */
fun Application.notebookModule(store: NotebookStore, sessions: SessionManager, loopbackOnly: Boolean) {
    install(ContentNegotiation) { json(protocolJson) }
    install(WebSockets) {
        pingPeriod = 20.seconds
        timeout = 60.seconds
        contentConverter = KotlinxWebsocketSerializationConverter(protocolJson)
    }
    install(StatusPages) {
        exception<NotebookNotFoundException> { call, e -> call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message!!)) }
        exception<NotebookExistsException> { call, e -> call.respond(HttpStatusCode.Conflict, ErrorResponse(e.message!!)) }
        exception<InvalidNotebookNameException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message!!))
        }
        exception<BadRequestException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Bad request"))
        }
        exception<Throwable> { call, e ->
            serverLog.error("Request failed: ${call.request.httpMethod.value} ${call.request.uri}", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: e.javaClass.name))
        }
    }
    monitor.subscribe(ApplicationStopping) { sessions.close() }

    // Any web page can send requests to localhost, and cells run arbitrary code, so only the notebook's own pages
    // may use the API.
    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        if ((path.startsWith("/api/") || path == "/ws") && !isTrustedRequest(call.request, loopbackOnly)) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("Cross-origin requests are not allowed"))
            finish()
        }
    }

    routing {
        staticResources("/", "static")

        route("/api") {
            get("/info") {
                call.respond(ServerInfo(KotlinVersion.CURRENT.toString(), Runtime.version().toString(), store.root.toString()))
            }
            get("/notebooks") {
                call.respond(store.list().map(::NotebookSummary))
            }
            get("/notebook") {
                val name = NotebookStore.normalizeName(call.requiredName())
                call.respond(NotebookDocument(name, store.load(name)))
            }
            put("/notebook") {
                val name = call.requiredName()
                store.save(name, call.receive<SaveNotebookRequest>().cells)
                call.respond(HttpStatusCode.NoContent)
            }
            post("/notebook") {
                val request = call.receive<CreateNotebookRequest>()
                val name = NotebookStore.normalizeName(request.name)
                store.create(name, request.cells)
                call.respond(HttpStatusCode.Created, NotebookDocument(name, request.cells))
            }
            delete("/notebook") {
                val name = NotebookStore.normalizeName(call.requiredName())
                store.delete(name)
                sessions.delete(name)
                call.respond(HttpStatusCode.NoContent)
            }
            post("/notebook/rename") {
                val request = call.receive<RenameNotebookRequest>()
                val from = NotebookStore.normalizeName(request.from)
                val to = NotebookStore.normalizeName(request.to)
                store.rename(from, to)
                sessions.rename(from, to)
                call.respond(NotebookSummary(to))
            }
            post("/complete") {
                val request = call.receive<CompletionRequest>()
                val name = NotebookStore.normalizeName(request.notebook)
                val items = withContext(Dispatchers.IO) {
                    sessions.session(name).complete(request.cellId, request.code, request.cursor)
                }
                call.respond(CompletionResponse(items))
            }
        }

        webSocket("/ws") {
            val name = NotebookStore.normalizeName(call.requiredName())
            val outgoing = Channel<ServerMessage>(Channel.UNLIMITED)
            val subscription = sessions.channel(name).subscribe { outgoing.trySend(it) }
            val sender = launch { outgoing.consumeEach { sendSerialized(it) } }
            try {
                outgoing.trySend(sessions.session(name).status())
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val message = try {
                        protocolJson.decodeFromString<ClientMessage>(frame.readText())
                    } catch (e: SerializationException) {
                        serverLog.warn("Ignoring malformed message: ${e.message}")
                        continue
                    }
                    when (message) {
                        is RunCells -> sessions.session(name).run(message.cells)
                        InterruptKernel -> sessions.session(name).interrupt()
                        RestartKernel -> sessions.restart(name)
                    }
                }
            } finally {
                subscription.close()
                outgoing.close()
                sender.cancel()
            }
        }
    }
}

private fun ApplicationCall.requiredName(): String =
    request.queryParameters["name"] ?: throw BadRequestException("Missing 'name' query parameter")

private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "[::1]", "::1")

private fun isTrustedRequest(request: ApplicationRequest, loopbackOnly: Boolean): Boolean {
    val host = request.host().lowercase()
    if (loopbackOnly && host !in LOOPBACK_HOSTS) return false
    // Browsers send Origin on WebSocket handshakes and on cross-origin fetches; it must name this server.
    val origin = request.headers[HttpHeaders.Origin] ?: return true
    val uri = runCatching { URI(origin) }.getOrNull() ?: return false
    val originPort = if (uri.port >= 0) uri.port else URLProtocol.createOrDefault(uri.scheme ?: "").defaultPort
    return uri.host.equals(host, ignoreCase = true) && originPort == request.port()
}
