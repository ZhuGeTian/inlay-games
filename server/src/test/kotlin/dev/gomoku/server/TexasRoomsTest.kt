package dev.gomoku.server

import dev.gomoku.core.*
import kotlinx.serialization.encodeToString
import kotlin.test.*

class TexasRoomsTest {
    private class Client(val id: String, ip: String = "127.0.0.1") {
        val messages = mutableListOf<TexasServerMessage>()
        var closed = false
        val peer = TexasPeer(id, ip, messages::add, { closed = true })
        fun last() = messages.last()
        fun welcome() = messages.first { it.type == "welcome" }
        fun state() = last().state!!
    }
    private fun rooms(now: () -> Long = { 0L }) = TexasRooms(now, { (0..51).toList() })
    private fun send(rooms: TexasRooms, client: Client, type: String, code: String = "", token: String = "", id: String = "req-$type") {
        rooms.handle(client.peer, TexasClientMessage(type, requestId = id, roomCode = code, token = token))
    }
    private fun trio(rooms: TexasRooms): List<Client> = List(3) { Client("p$it") }.also { players ->
        send(rooms, players[0], "create")
        players.drop(1).forEach { send(rooms, it, "join", players[0].welcome().roomCode) }
    }
    private fun act(rooms: TexasRooms, client: Client, type: String, id: String = "act-$type", raiseTo: Long = 0) =
        TexasClientMessage(type, requestId = id, revision = client.state().revision, raiseTo = raiseTo).also { rooms.handle(client.peer, it) }

    @Test fun `only three players start and each snapshot contains only its own cards and token`() {
        val rooms = rooms(); val a = Client("a"); val b = Client("b"); val c = Client("c"); val outsider = Client("x")
        send(rooms, a, "create")
        val code = a.welcome().roomCode
        send(rooms, b, "join", code)
        assertEquals("waiting", a.state().status)
        assertEquals(listOf(true, true, false), a.state().online)
        assertTrue(a.state().hand.isEmpty())
        send(rooms, c, "join", code)
        val players = listOf(a, b, c)
        assertEquals("playing", a.state().status)
        assertEquals("preflop", a.state().street)
        assertEquals(6, players.flatMap { it.state().hand }.toSet().size)
        players.forEach { client ->
            assertEquals(2, client.state().hand.size)
            assertTrue(client.state().board.isEmpty())
            assertTrue(client.state().revealed.all { it.isEmpty() })
            assertEquals(listOf(true, true, true), client.state().online)
            assertTrue(client.messages.filter { it.type != "welcome" }.all { it.token.isEmpty() })
            players.filter { it !== client }.forEach { other ->
                assertFalse(client.messages.any { wireJson.encodeToString(it).contains(other.welcome().token) })
            }
        }
        send(rooms, outsider, "join", code)
        assertEquals("error", outsider.last().type); assertNull(outsider.last().state)
        rooms.handle(outsider.peer, TexasClientMessage("allin", requestId = "unknown", revision = a.state().revision))
        assertEquals("error", outsider.last().type); assertNull(outsider.last().state)
    }

    @Test fun `replay is private idempotent and stale revision wrong turn invalid raise are rejected`() {
        val rooms = rooms(); val players = trio(rooms)
        val a = players[players[0].state().turn - 1]
        val command = act(rooms, a, "call")
        assertEquals("state", a.last().type)
        val state = a.state()
        val others = players.filter { it !== a }.map { it.messages.size }
        rooms.handle(a.peer, command)
        assertEquals(state, a.state())
        assertEquals(others, players.filter { it !== a }.map { it.messages.size })
        rooms.handle(a.peer, command.copy(type = "fold"))
        assertEquals("error", a.last().type); assertEquals(state, a.state())
        rooms.handle(a.peer, TexasClientMessage("fold", requestId = "stale", revision = state.revision - 1))
        assertEquals("error", a.last().type); assertEquals(state, a.state())
        act(rooms, a, "fold", "wrong-turn")
        assertEquals("error", a.last().type); assertEquals(state, a.state())
        val current = players[state.turn - 1]
        val before = current.state()
        act(rooms, current, "raise", "negative", -1)
        assertEquals("error", current.last().type); assertEquals(before, current.state())
        act(rooms, current, "raise", "huge", Long.MAX_VALUE)
        assertEquals("error", current.last().type); assertEquals(before, current.state())
    }

    @Test fun `create join retries do not allocate new seats and resume preserves dedup across replaced socket`() {
        val rooms = rooms(); val a = Client("a"); val b = Client("b")
        send(rooms, a, "create")
        val first = a.welcome()
        send(rooms, a, "create"); assertEquals(first, a.last())
        send(rooms, b, "join", first.roomCode)
        val second = b.welcome()
        send(rooms, b, "join", first.roomCode); assertEquals(second, b.last())
        val c = Client("c"); send(rooms, c, "join", first.roomCode)
        val players = listOf(a, b, c)
        val actor = players[a.state().turn - 1]
        val command = act(rooms, actor, "call")
        val before = actor.state()
        rooms.disconnect(c.peer)
        for ((i, type) in listOf("fold", "check", "call", "raise", "allin", "ready").withIndex()) {
            rooms.handle(a.peer, TexasClientMessage(type, requestId = "offline-$i", revision = before.revision))
            assertEquals("error", a.last().type); assertEquals(before.revision, a.state().revision)
        }
        val resumedC = Client("c2")
        send(rooms, resumedC, "resume", first.roomCode, "wrong")
        assertEquals("error", resumedC.last().type); assertNull(resumedC.last().state)
        send(rooms, resumedC, "resume", first.roomCode, c.welcome().token)
        assertEquals(c.state().hand, resumedC.state().hand)
        val replacement = Client("replacement")
        send(rooms, replacement, "resume", first.roomCode, actor.welcome().token)
        assertTrue(actor.closed)
        rooms.disconnect(actor.peer)
        assertEquals(listOf(true, true, true), replacement.state().online)
        rooms.handle(replacement.peer, command)
        assertEquals(before.revision, replacement.state().revision)
        assertEquals(before.hand, replacement.state().hand)
        rooms.handle(actor.peer, command.copy(requestId = "old-socket"))
        assertEquals("error", actor.last().type); assertNull(actor.last().state)
    }

    @Test fun `check call showdown conserves chips and unanimous ready starts next hand`() {
        val rooms = rooms(); val players = trio(rooms)
        var actions = 0
        while (players[0].state().status == "playing" && actions < 20) {
            val client = players[players[0].state().turn - 1]
            act(rooms, client, if (client.state().toCall == 0L) "check" else "call", "turn-${actions++}")
            assertEquals("state", client.last().type)
            assertEquals(3000L, client.state().stacks.sum() + client.state().pot)
        }
        val finished = players[0].state()
        assertEquals("finished", finished.status)
        assertEquals(5, finished.board.size)
        assertTrue(finished.revealed.all { it.size == 2 })
        assertEquals(0L, finished.roundDelta.sum())
        players.forEachIndexed { i, client -> act(rooms, client, "ready", "ready-$i") }
        val next = players[0].state()
        assertEquals("playing", next.status)
        assertEquals(finished.round + 1, next.round)
        assertEquals(3000L, next.stacks.sum() + next.pot)
        assertTrue(next.revealed.all { it.isEmpty() })
    }

    @Test fun `leave closes all identities and capacity can be reused`() {
        val rooms = rooms()
        repeat(6) {
            val (a, b, c) = trio(rooms)
            val code = a.welcome().roomCode
            rooms.disconnect(c.peer)
            send(rooms, a, "leave")
            assertEquals("left", a.last().type); assertEquals("closed", b.last().type)
            assertNull(a.peer.room); assertEquals(0, a.peer.seat)
            assertNull(b.peer.room); assertEquals(0, b.peer.seat)
            send(rooms, c, "resume", code, c.welcome().token)
            assertEquals("error", c.last().type); assertNull(c.last().state)
        }
    }

    @Test fun `ttl protocol request identity and per ip room limit fail closed`() {
        var time = 0L
        val rooms = rooms { time }; val a = Client("a")
        rooms.handle(a.peer, TexasClientMessage("create", protocol = 99, requestId = "bad"))
        assertEquals("error", a.last().type)
        rooms.handle(a.peer, TexasClientMessage("create"))
        assertEquals("error", a.last().type)
        send(rooms, a, "create")
        val code = a.welcome().roomCode
        repeat(4) { send(rooms, Client("limit-$it"), "create") }
        val extra = Client("extra"); send(rooms, extra, "create")
        assertEquals("error", extra.last().type)
        time = 30 * 60 * 1000L
        send(rooms, extra, "join", code)
        assertEquals("expired", a.last().type); assertNull(a.peer.room)
        assertEquals("error", extra.last().type); assertNull(extra.last().state)
        send(rooms, extra, "create", id = "after-expiry")
        assertEquals("welcome", extra.last().type)
    }
}
