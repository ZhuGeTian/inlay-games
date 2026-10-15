package dev.gomoku.server

import dev.gomoku.core.*
import kotlinx.serialization.encodeToString
import kotlin.test.*

class DdzRoomsTest {
    private class Client(val id: String, ip: String = "127.0.0.1") {
        val messages = mutableListOf<DdzServerMessage>()
        var closed = false
        val peer = DdzPeer(id, ip, messages::add, { closed = true })
        fun last() = messages.last()
        fun welcome() = messages.first { it.type == "welcome" }
        fun state() = last().state!!
    }

    private fun rooms(now: () -> Long = { 0L }) = DdzRooms(now, { (0..53).toList() })
    private fun send(rooms: DdzRooms, client: Client, type: String, code: String = "", token: String = "", id: String = "req-$type") {
        rooms.handle(client.peer, DdzClientMessage(type, requestId = id, roomCode = code, token = token))
    }
    private fun trio(rooms: DdzRooms): List<Client> = List(3) { Client("p$it") }.also { players ->
        send(rooms, players[0], "create")
        players.drop(1).forEach { send(rooms, it, "join", players[0].welcome().roomCode) }
    }
    private fun bid(rooms: DdzRooms, client: Client, value: Int = 3, id: String = "bid") =
        DdzClientMessage("bid", requestId = id, bid = value, revision = client.state().revision).also { rooms.handle(client.peer, it) }

    @Test fun `only third join starts private deal and extra players cannot join`() {
        val rooms = rooms(); val a = Client("a"); val b = Client("b"); val c = Client("c"); val outsider = Client("x")
        send(rooms, a, "create")
        val code = a.welcome().roomCode
        send(rooms, b, "join", code)
        assertEquals("waiting", a.state().status)
        assertEquals(listOf(true, true, false), a.state().online)
        assertTrue(a.state().hand.isEmpty())
        send(rooms, c, "join", code)
        val players = listOf(a, b, c)
        assertEquals("bidding", a.state().status)
        assertEquals(51, players.flatMap { it.state().hand }.toSet().size)
        players.forEachIndexed { index, client ->
            assertEquals((index * 17 until (index + 1) * 17).toList(), client.state().hand)
            assertEquals(listOf(17, 17, 17), client.state().counts)
            assertTrue(client.state().bottom.isEmpty())
            assertEquals(listOf(true, true, true), client.state().online)
            assertTrue(client.messages.filter { it.type != "welcome" }.all { it.token.isEmpty() })
            players.filter { it !== client }.forEach { other ->
                assertFalse(client.messages.any { wireJson.encodeToString(it).contains(other.welcome().token) })
            }
        }
        send(rooms, outsider, "join", code)
        assertEquals("error", outsider.last().type)
        assertNull(outsider.last().state)
        rooms.handle(outsider.peer, DdzClientMessage("bid", requestId = "unknown", bid = 3, revision = 1))
        assertEquals("error", outsider.last().type)
        assertNull(outsider.last().state)
        bid(rooms, a)
        assertEquals(20, a.state().hand.size)
        assertEquals(listOf(51, 52, 53), b.state().bottom)
        assertEquals(17, b.state().hand.size)
        assertTrue(a.state().hand.intersect(b.state().hand.toSet()).isEmpty())
    }

    @Test fun `request replay is private idempotent and conflicting reuse or stale revision fails`() {
        val rooms = rooms(); val (a, b, c) = trio(rooms)
        val command = bid(rooms, a)
        val state = a.state()
        val others = b.messages.size to c.messages.size
        rooms.handle(a.peer, command)
        assertEquals(state, a.state())
        assertEquals(others, b.messages.size to c.messages.size)
        rooms.handle(a.peer, command.copy(bid = 2))
        assertEquals("error", a.last().type)
        assertEquals(state, a.state())
        rooms.handle(a.peer, DdzClientMessage("play", requestId = "stale", cards = listOf(0), revision = state.revision - 1))
        assertEquals("error", a.last().type)
        assertEquals(state, a.state())
        rooms.handle(b.peer, DdzClientMessage("play", requestId = "wrong-turn", cards = listOf(17), revision = state.revision))
        assertEquals("error", b.last().type)
        assertEquals((17..33).toList(), b.state().hand)
        assertTrue(b.last().token.isEmpty())
    }

    @Test fun `create and join retries reuse seats without additional rooms`() {
        val rooms = rooms(); val a = Client("a"); val b = Client("b")
        send(rooms, a, "create")
        val welcome = a.welcome()
        send(rooms, a, "create")
        assertEquals(welcome, a.last())
        send(rooms, b, "join", welcome.roomCode)
        val second = b.welcome()
        send(rooms, b, "join", welcome.roomCode)
        assertEquals(second, b.last())
        send(rooms, b, "create", id = "req-join")
        assertEquals("error", b.last().type)
    }

    @Test fun `all game actions pause offline and token resumes hand and dedup history`() {
        val rooms = rooms(); val (a, b, c) = trio(rooms)
        val command = bid(rooms, a)
        val before = a.state()
        rooms.disconnect(c.peer)
        for ((i, type) in listOf("bid", "play", "pass", "ready", "resign").withIndex()) {
            rooms.handle(a.peer, DdzClientMessage(type, requestId = "offline-$i", revision = before.revision, cards = listOf(0)))
            assertEquals("error", a.last().type)
            assertEquals(before.revision, a.state().revision)
        }
        val resumedC = Client("c2")
        send(rooms, resumedC, "resume", a.welcome().roomCode, "wrong")
        assertEquals("error", resumedC.last().type)
        assertNull(resumedC.last().state)
        send(rooms, resumedC, "resume", a.welcome().roomCode, c.welcome().token)
        assertEquals(3, resumedC.welcome().seat)
        assertEquals((34..50).toList(), resumedC.state().hand)
        val resumedA = Client("a2")
        send(rooms, resumedA, "resume", a.welcome().roomCode, a.welcome().token)
        assertTrue(a.closed)
        rooms.disconnect(a.peer)
        assertEquals(listOf(true, true, true), resumedA.state().online)
        rooms.handle(resumedA.peer, command)
        assertEquals(before.revision, resumedA.state().revision)
        assertEquals(before.hand, resumedA.state().hand)
        rooms.handle(a.peer, command.copy(requestId = "old-socket"))
        assertEquals("error", a.last().type)
        assertNull(a.last().state)
        assertEquals(listOf(true, true, true), b.state().online)
    }

    @Test fun `resign settles room scores and three ready players start next round`() {
        val rooms = rooms(); val players = trio(rooms); val a = players[0]
        bid(rooms, a)
        rooms.handle(a.peer, DdzClientMessage("resign", requestId = "resign", revision = a.state().revision))
        assertEquals("finished", a.state().status)
        assertEquals(listOf(-6L, 3L, 3L), a.state().scores)
        val scores = a.state().scores
        players.forEachIndexed { i, client ->
            rooms.handle(client.peer, DdzClientMessage("ready", requestId = "ready-$i", revision = client.state().revision))
        }
        assertEquals("bidding", a.state().status)
        assertEquals(2, a.state().round)
        assertEquals(2, a.state().turn)
        assertEquals(scores, a.state().scores)
        assertEquals(listOf(17, 17, 17), a.state().counts)
    }

    @Test fun `leave closes room clears all identities including offline seats and reclaims capacity`() {
        val rooms = rooms()
        repeat(6) {
            val (a, b, c) = trio(rooms)
            val code = a.welcome().roomCode
            val token = c.welcome().token
            rooms.disconnect(c.peer)
            send(rooms, a, "leave")
            assertEquals("left", a.last().type)
            assertEquals("closed", b.last().type)
            assertNull(a.peer.room); assertEquals(0, a.peer.seat)
            assertNull(b.peer.room); assertEquals(0, b.peer.seat)
            send(rooms, c, "resume", code, token)
            assertEquals("error", c.last().type)
            assertNull(c.last().state)
        }
    }

    @Test fun `ttl invalid requests and room limits reject without exposing state`() {
        var time = 0L
        val rooms = rooms { time }; val a = Client("a")
        rooms.handle(a.peer, DdzClientMessage("create", protocol = 99, requestId = "bad"))
        assertEquals("error", a.last().type)
        rooms.handle(a.peer, DdzClientMessage("create"))
        assertEquals("error", a.last().type)
        send(rooms, a, "create")
        val code = a.welcome().roomCode
        repeat(4) { send(rooms, Client("limit-$it"), "create") }
        val extra = Client("extra")
        send(rooms, extra, "create")
        assertEquals("error", extra.last().type)
        time = 30 * 60 * 1000L
        send(rooms, extra, "join", code)
        assertEquals("expired", a.last().type)
        assertEquals("error", extra.last().type)
        assertNull(extra.last().state)
        assertNull(a.peer.room)
        send(rooms, extra, "create", id = "after-expiry")
        assertEquals("welcome", extra.last().type)
    }
}
