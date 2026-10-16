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

class TexasTransportTest {
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

    @Test fun `real texas sockets keep private hands through showdown and share all route limits`() = runBlocking {
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { gomoku() }.start(wait = false)
        val sockets = mutableListOf<Socket>()
        try {
            val port = server.engine.resolvedConnectors().single().port
            val http = HttpClient.newHttpClient()
            val response = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/gomoku/texas/health")).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, response.statusCode())
            assertEquals("""{"status":"ok","protocol":$TEXAS_PROTOCOL_VERSION}""", response.body())
            val v2Health = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/gomoku/texas/v2/health")).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(response.body(), v2Health.body())
            fun connect(path: String) = Socket(http, "ws://127.0.0.1:$port$path").also(sockets::add)
            fun Socket.read() = wireJson.decodeFromString<TexasServerMessage>(message())
            fun Socket.command(value: TexasClientMessage) = send(wireJson.encodeToString(value))
            val a = connect("/gomoku/texas/v2/ws")
            a.command(TexasClientMessage("create", protocol = 1, requestId = "legacy"))
            assertEquals("error", a.read().type)
            a.command(TexasClientMessage("create", requestId = "create"))
            val welcome = a.read()
            val b = connect("/gomoku/texas/ws")
            b.command(TexasClientMessage("join", requestId = "join-b", roomCode = welcome.roomCode))
            b.read(); b.read(); a.read()
            val c = connect("/gomoku/texas/ws")
            c.command(TexasClientMessage("join", requestId = "join-c", roomCode = welcome.roomCode))
            c.read()
            val clients = listOf(a, b, c)
            var states = clients.map { it.read().state!! }
            assertEquals(6, states.flatMap { it.hand }.toSet().size)
            assertTrue(states.all { it.hand.size == 2 && it.revealed.all(List<Int>::isEmpty) })
            var actions = 0
            while (states[0].status == "playing" && actions < 20) {
                val own = states[states[0].turn - 1]
                clients[own.turn - 1].command(TexasClientMessage(if (own.toCall == 0L) "check" else "call",
                    requestId = "action-${actions++}", revision = own.revision))
                val messages = clients.map { it.read() }
                assertTrue(messages.all { it.type == "state" && it.token.isEmpty() })
                states = messages.map { it.state!! }
                assertTrue(states.all { it.stacks.sum() + it.pot == 3000L })
            }
            assertEquals("finished", states[0].status)
            assertEquals(5, states[0].board.size)
            assertTrue(states[0].revealed.all { it.size == 2 })
            a.send("{}")
            assertEquals("error", a.read().type)
            a.send("""{"type":"raise","requestId":"overflow","raiseTo":9223372036854775808}""")
            assertEquals("error", a.read().type)
            val gomoku = connect("/gomoku/ws")
            gomoku.send(wireJson.encodeToString(ClientMessage("create", requestId = "old")))
            assertEquals("welcome", wireJson.decodeFromString<ServerMessage>(gomoku.message()).type)
            val ddz = connect("/gomoku/ddz/ws")
            ddz.send(wireJson.encodeToString(DdzClientMessage("create", requestId = "ddz")))
            assertEquals("welcome", wireJson.decodeFromString<DdzServerMessage>(ddz.message()).type)
            repeat(7) { connect("/gomoku/texas/ws") }
            for (path in listOf("/gomoku/ws", "/gomoku/ddz/ws", "/gomoku/texas/ws")) {
                assertEquals(1013, connect(path).closed())
            }
        } finally {
            sockets.forEach(Socket::close)
            server.stop(0, 1_000)
        }
    }
}
