package dev.gomoku.server

import dev.gomoku.core.*
import kotlin.test.*

class RoomsTest {
    private class Client(val id: String) {
        val messages = mutableListOf<ServerMessage>()
        val peer = Peer(id, "127.0.0.1", messages::add)
        fun last() = messages.last()
        fun welcome() = messages.first { it.type == "welcome" }
    }
    private fun send(rooms: Rooms, c: Client, type: String, code: String = "", token: String = "", id: String = "req-$type") {
        rooms.handle(c.peer, ClientMessage(type, requestId = id, roomCode = code, token = token))
    }

    @Test fun `create join full room identity and duplicate moves`() {
        val rooms = Rooms()
        val a = Client("a"); val b = Client("b"); val c = Client("c")
        send(rooms, a, "create")
        val code = a.welcome().roomCode
        assertEquals(8, code.length)
        send(rooms, b, "join", code)
        assertEquals("playing", a.last().state!!.status)
        assertEquals(a.last().state, b.last().state)
        send(rooms, c, "join", code)
        assertEquals("error", c.last().type)
        rooms.handle(c.peer, ClientMessage("move", requestId = "unbound", x = 0, y = 0, revision = 1))
        assertEquals("error", c.last().type)
        val move = ClientMessage("move", requestId = "m1", x = 0, y = 0, revision = 1)
        rooms.handle(a.peer, move)
        rooms.handle(a.peer, move)
        assertEquals(2, a.last().state!!.revision)
        assertEquals(1, a.last().state!!.board.count { it != 0 })
        rooms.handle(a.peer, move.copy(x = 1))
        assertEquals("error", a.last().type)
        assertTrue(b.messages.filter { it.type == "state" }.all { it.token.isEmpty() })
    }

    @Test fun `private token resumes disconnected player and invalid tokens fail`() {
        val rooms = Rooms()
        val a = Client("a"); val b = Client("b"); val resumed = Client("a2")
        send(rooms, a, "create")
        val welcome = a.welcome()
        send(rooms, b, "join", welcome.roomCode)
        rooms.disconnect(a.peer)
        assertFalse(b.last().state!!.blackOnline)
        rooms.handle(b.peer, ClientMessage("move", requestId = "offline", x = 1, y = 1, revision = 1))
        assertEquals("error", b.last().type)
        send(rooms, resumed, "resume", welcome.roomCode, "wrong")
        assertEquals("error", resumed.last().type)
        send(rooms, resumed, "resume", welcome.roomCode, welcome.token)
        assertEquals(1, resumed.welcome().seat)
        assertTrue(b.last().state!!.blackOnline)
        rooms.handle(resumed.peer, ClientMessage("move", requestId = "m1", x = 7, y = 7, revision = 1))
        assertEquals(1, b.last().state!!.board[112])
    }

    @Test fun `active socket replacement does not disconnect new socket`() {
        val rooms = Rooms(); val a = Client("a"); val resumed = Client("b")
        send(rooms, a, "create")
        send(rooms, resumed, "resume", a.welcome().roomCode, a.welcome().token)
        rooms.disconnect(a.peer)
        assertTrue(resumed.last().state!!.blackOnline)
    }

    @Test fun `rooms expire and leave ends active games`() {
        var time = 0L
        val rooms = Rooms { time }; val a = Client("a"); val b = Client("b")
        send(rooms, a, "create"); send(rooms, b, "join", a.welcome().roomCode)
        send(rooms, a, "leave")
        assertEquals(2, b.last().state!!.winner)
        time += 30 * 60 * 1000L
        rooms.sweep()
        assertEquals("expired", b.last().type)
        send(rooms, a, "join", b.welcome().roomCode)
        assertEquals("error", a.last().type)
    }

    @Test fun `protocol mismatch and expired room moves cannot mutate games`() {
        var time = 0L
        val rooms = Rooms { time }; val a = Client("a"); val b = Client("b")
        rooms.handle(a.peer, ClientMessage("create", protocol = 99, requestId = "bad"))
        assertEquals("error", a.last().type)
        send(rooms, a, "create"); send(rooms, b, "join", a.welcome().roomCode)
        time = 30 * 60 * 1000L
        rooms.handle(a.peer, ClientMessage("move", requestId = "late", x = 1, y = 1, revision = 1))
        assertEquals("error", a.last().type)
        assertEquals("expired", b.last().type)
    }

    @Test fun `room capacity is reclaimed after both players leave`() {
        val rooms = Rooms()
        repeat(6) { i ->
            val a = Client("a$i"); val b = Client("b$i")
            send(rooms, a, "create"); assertEquals("welcome", a.last().type)
            send(rooms, b, "join", a.welcome().roomCode)
            send(rooms, a, "leave"); send(rooms, b, "leave")
        }
    }
}
