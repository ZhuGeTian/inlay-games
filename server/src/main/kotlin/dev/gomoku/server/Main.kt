package dev.gomoku.server

import dev.gomoku.core.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

fun main() {
    val host = System.getenv("GOMOKU_HOST") ?: "127.0.0.1"
    val port = (System.getenv("GOMOKU_PORT") ?: "8091").toInt()
    embeddedServer(Netty, host = host, port = port) { gomoku() }.start(wait = true)
}

fun Application.gomoku() {
    val rooms = Rooms()
    val ddzRooms = DdzRooms()
    val texasRooms = TexasRooms()
    val connections = AtomicInteger()
    val perIp = ConcurrentHashMap<String, AtomicInteger>()
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 20.seconds
        maxFrameSize = 4096
        masking = false
    }
    launch { while (isActive) { delay(30_000); rooms.sweep(); ddzRooms.sweep(); texasRooms.sweep() } }
    launch { while (isActive) { delay(250); ddzRooms.tick() } }
    routing {
        get("/gomoku/health") { call.respondText("""{"status":"ok","protocol":1}""", io.ktor.http.ContentType.Application.Json) }
        for (path in listOf("/gomoku/ddz/health", "/gomoku/ddz/v2/health")) {
            get(path) { call.respondText("""{"status":"ok","protocol":$DDZ_PROTOCOL_VERSION}""", io.ktor.http.ContentType.Application.Json) }
        }
        get("/gomoku/texas/health") { call.respondText("""{"status":"ok","protocol":1}""", io.ktor.http.ContentType.Application.Json) }
        webSocket("/gomoku/ws") {
            transport(connections, perIp,
                createPeer = { id, ip, emit, close -> Peer(id, ip, { emit(wireJson.encodeToString(it)) }, close) },
                handle = { peer, text -> rooms.handle(peer, wireJson.decodeFromString<ClientMessage>(text)) },
                disconnect = rooms::disconnect,
                malformed = wireJson.encodeToString(ServerMessage("error", error = "消息格式不正确")))
        }
        for (path in listOf("/gomoku/ddz/ws", "/gomoku/ddz/v2/ws")) {
            webSocket(path) {
                transport(connections, perIp,
                    createPeer = { id, ip, emit, close -> DdzPeer(id, ip, { emit(wireJson.encodeToString(it)) }, close) },
                    handle = { peer, text -> ddzRooms.handle(peer, wireJson.decodeFromString<DdzClientMessage>(text)) },
                    disconnect = ddzRooms::disconnect,
                    malformed = wireJson.encodeToString(DdzServerMessage("error", error = "消息格式不正确")))
            }
        }
        webSocket("/gomoku/texas/ws") {
            transport(connections, perIp,
                createPeer = { id, ip, emit, close -> TexasPeer(id, ip, { emit(wireJson.encodeToString(it)) }, close) },
                handle = { peer, text -> texasRooms.handle(peer, wireJson.decodeFromString<TexasClientMessage>(text)) },
                disconnect = texasRooms::disconnect,
                malformed = wireJson.encodeToString(TexasServerMessage("error", error = "消息格式不正确")))
        }
    }
}

private suspend fun <P> DefaultWebSocketServerSession.transport(
    connections: AtomicInteger,
    perIp: ConcurrentHashMap<String, AtomicInteger>,
    createPeer: (String, String, (String) -> Unit, () -> Unit) -> P,
    handle: (P, String) -> Unit,
    disconnect: (P) -> Unit,
    malformed: String,
) {
    val remote = call.request.local.remoteHost
    val ip = if (remote in setOf("127.0.0.1", "::1")) call.request.headers["X-Real-IP"] ?: remote else remote
    val ipCount = perIp.compute(ip) { _, current -> (current ?: AtomicInteger()).also { it.incrementAndGet() } }!!.get()
    val total = connections.incrementAndGet()
    if (ipCount > 12 || total > 400) {
        connections.decrementAndGet()
        perIp.computeIfPresent(ip) { _, value -> if (value.decrementAndGet() == 0) null else value }
        close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Too many connections"))
        return
    }
    val events = Channel<String>(32)
    val socketScope = this
    val emit: (String) -> Unit = { event -> if (!events.trySend(event).isSuccess) socketScope.cancel("Slow client") }
    val peer = createPeer(UUID.randomUUID().toString(), ip, emit) { socketScope.cancel("Session resumed elsewhere") }
    val writer = launch { for (event in events) send(Frame.Text(event)) }
    var window = System.nanoTime()
    var count = 0
    try {
        for (frame in incoming) {
            if (frame !is Frame.Text) { close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Text JSON required")); break }
            if (System.nanoTime() - window >= 1_000_000_000L) { window = System.nanoTime(); count = 0 }
            if (++count > 20) { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Too many requests")); break }
            val text = frame.readText()
            if (text.length > 4096) { close(CloseReason(CloseReason.Codes.TOO_BIG, "Message too large")); break }
            try {
                handle(peer, text)
            } catch (_: SerializationException) {
                emit(malformed)
            }
        }
    } finally {
        disconnect(peer)
        events.close()
        writer.cancel()
        connections.decrementAndGet()
        // Keep the counter until no socket uses it, including concurrent handshakes.
        perIp.computeIfPresent(ip) { _, value -> if (value.decrementAndGet() == 0) null else value }
    }
}
