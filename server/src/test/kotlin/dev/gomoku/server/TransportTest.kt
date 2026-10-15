package dev.gomoku.server

import dev.gomoku.core.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*

class TransportTest {
    private class Socket(client: HttpClient, url: String) : AutoCloseable {
        private val messages = LinkedBlockingQueue<String>()
        private val closes = LinkedBlockingQueue<Int>()
        private val socket = client.newWebSocketBuilder().buildAsync(URI(url), object : WebSocket.Listener {
            private val buffer = StringBuilder()
            override fun onOpen(webSocket: WebSocket) { webSocket.request(1) }
            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): java.util.concurrent.CompletionStage<*>? {
                buffer.append(data)
                if (last) { messages.add(buffer.toString()); buffer.setLength(0) }
                webSocket.request(1)
                return null
            }
            override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): java.util.concurrent.CompletionStage<*>? {
                closes.add(statusCode)
                return null
            }
        }).get(5, TimeUnit.SECONDS)
        fun send(text: String) { socket.sendText(text, true).get(5, TimeUnit.SECONDS) }
        fun message(): String = messages.poll(5, TimeUnit.SECONDS) ?: error("Timed out waiting for WebSocket message")
        fun closed(): Int = closes.poll(5, TimeUnit.SECONDS) ?: error("Timed out waiting for WebSocket close")
        override fun close() { socket.abort() }
    }

    @Test fun `real sockets preserve gomoku JSON and share connection limits with private doudizhu`() = runBlocking {
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { gomoku() }.start(wait = false)
        val sockets = mutableListOf<Socket>()
        try {
            val port = server.engine.resolvedConnectors().single().port
            val http = HttpClient.newHttpClient()
            for (path in listOf("/gomoku/health", "/gomoku/ddz/health", "/gomoku/ddz/v2/health")) {
                val response = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).build(), HttpResponse.BodyHandlers.ofString())
                assertEquals(200, response.statusCode())
                val protocol = if (path.contains("/ddz/")) DDZ_PROTOCOL_VERSION else 1
                assertEquals("""{"status":"ok","protocol":$protocol}""", response.body())
            }
            fun connect(path: String) = Socket(http, "ws://127.0.0.1:$port$path").also(sockets::add)
            val old = connect("/gomoku/ws")
            old.send(wireJson.encodeToString(ClientMessage("create", requestId = "old-create")))
            val originalJson = old.message()
            val original = wireJson.decodeFromString<ServerMessage>(originalJson)
            assertEquals("welcome", original.type)
            val stateKeys = (wireJson.parseToJsonElement(originalJson) as JsonObject)["state"] as JsonObject
            assertEquals(setOf("board", "status", "turn", "winner", "revision", "lastMove", "blackOnline", "whiteOnline"), stateKeys.keys)
            old.send("bad json")
            assertEquals("error", wireJson.decodeFromString<ServerMessage>(old.message()).type)

            val a = connect("/gomoku/ddz/v2/ws")
            a.send(wireJson.encodeToString(DdzClientMessage("create", requestId = "create")))
            val welcome = wireJson.decodeFromString<DdzServerMessage>(a.message())
            val b = connect("/gomoku/ddz/ws")
            b.send(wireJson.encodeToString(DdzClientMessage("join", requestId = "join-b", roomCode = welcome.roomCode)))
            b.message(); b.message(); a.message()
            val c = connect("/gomoku/ddz/ws")
            c.send(wireJson.encodeToString(DdzClientMessage("join", requestId = "join-c", roomCode = welcome.roomCode)))
            c.message()
            val clients = listOf(a, b, c)
            val states = clients.map { wireJson.decodeFromString<DdzServerMessage>(it.message()).state!! }
            assertEquals(51, states.flatMap { it.hand }.toSet().size)
            assertTrue(states.all { it.hand.size == 17 && it.bottom.isEmpty() })
            a.send(wireJson.encodeToString(DdzClientMessage("bid", requestId = "bid", bid = 3, revision = states[0].revision)))
            val playing = clients.map { wireJson.decodeFromString<DdzServerMessage>(it.message()).state!! }
            assertEquals(listOf(20, 17, 17), playing.map { it.hand.size })
            assertEquals(3, playing[0].bottom.size)
            a.send(wireJson.encodeToString(DdzClientMessage("resign", requestId = "resign", revision = playing[0].revision)))
            clients.forEach { assertEquals("finished", wireJson.decodeFromString<DdzServerMessage>(it.message()).state!!.status) }
            a.send("{}")
            assertEquals("error", wireJson.decodeFromString<DdzServerMessage>(a.message()).type)

            repeat(8) { connect(if (it % 2 == 0) "/gomoku/ws" else "/gomoku/ddz/ws") }
            assertEquals(1013, connect("/gomoku/ddz/ws").closed())
            assertEquals(1013, connect("/gomoku/ws").closed())
        } finally {
            sockets.forEach(Socket::close)
            server.stop(0, 1_000)
        }
    }
}
