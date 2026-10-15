package dev.gomoku.server

import dev.gomoku.core.*
import kotlinx.serialization.encodeToString
import kotlin.test.*

class DdzBotRoomsTest {
    private class Client(val id: String) {
        val messages = mutableListOf<DdzServerMessage>()
        val peer = DdzPeer(id, "127.0.0.1", messages::add)
        val last get() = messages.last()
        val state get() = last.state!!
        val identity get() = messages.first { it.type == "welcome" }
    }
    private class Table(val humans: Int) {
        var time = 0L
        val rooms = DdzRooms({ time }, { (0..53).toList() })
        val clients = List(humans) { Client("human-$it") }
        var request = 0
        init {
            send(clients[0], "create")
            clients.drop(1).forEach { send(it, "join", code = clients[0].identity.roomCode) }
        }
        fun send(client: Client, type: String, revision: Int = client.messages.lastOrNull()?.state?.revision ?: -1,
                 code: String = "", token: String = "", bid: Int = 0): DdzClientMessage =
            DdzClientMessage(type, requestId = "request-${++request}", revision = revision,
                roomCode = code, token = token, bid = bid).also { rooms.handle(client.peer, it) }
        fun tick() { time += 700; rooms.tick() }
        fun start() = send(clients[0], "start")
        fun humanMove(client: Client) {
            val command = DdzBot.decide(client.state, client.identity.seat)
            rooms.handle(client.peer, command.copy(requestId = "request-${++request}"))
            assertEquals("state", client.last.type, client.last.error)
        }
    }

    @Test fun `one and two humans can start and only missing seats become bots`() {
        for (humanCount in 1..2) {
            val table = Table(humanCount)
            val a = table.clients[0]
            assertEquals("waiting", a.state.status)
            val command = table.start()
            assertEquals("bidding", a.state.status)
            assertEquals(List(3) { it >= humanCount }, a.state.bots)
            assertEquals(List(3) { true }, a.state.online)
            assertEquals(listOf(17, 17, 17), a.state.counts)
            assertEquals(17, a.state.hand.size)
            assertTrue(a.state.bottom.isEmpty())
            val before = a.state
            table.rooms.handle(a.peer, command)
            assertEquals(before, a.state, "Start retries must not redeal")
            table.send(a, "start")
            assertEquals("error", a.last.type)
            assertEquals(before, a.state)
            val outsider = Client("outsider")
            table.send(outsider, "join", code = a.identity.roomCode)
            assertEquals("error", outsider.last.type)
            assertNull(outsider.last.state)
            for (token in listOf("", "robot", "null")) {
                table.send(outsider, "resume", code = a.identity.roomCode, token = token)
                assertEquals("error", outsider.last.type)
                assertNull(outsider.last.state)
            }
        }
    }

    @Test fun `start requires host current revision and all humans online`() {
        val table = Table(2); val (a, b) = table.clients
        table.send(b, "start")
        assertEquals("error", b.last.type)
        assertEquals("waiting", b.state.status)
        table.send(a, "start", revision = -1)
        assertEquals("error", a.last.type)
        table.rooms.disconnect(b.peer)
        table.start()
        assertEquals("error", a.last.type)
        assertEquals(listOf(false, false, false), a.state.bots)
        val resumed = Client("replacement")
        table.send(resumed, "resume", code = a.identity.roomCode, token = b.identity.token)
        table.start()
        assertEquals("bidding", a.state.status)
        assertEquals(listOf(false, false, true), a.state.bots)
    }

    @Test fun `bot actions are delayed at most one per room tick and preserve private views`() {
        val table = Table(1); val a = table.clients[0]
        table.start()
        table.send(a, "bid", bid = 0)
        val before = a.state
        table.time = 699
        table.rooms.tick()
        assertEquals(before, a.state)
        table.time = 700
        table.rooms.tick()
        assertEquals(before.revision + 1, a.state.revision)
        assertEquals(before.hand, a.state.hand)
        val after = a.state
        table.rooms.tick()
        assertEquals(after, a.state)
        assertTrue(a.messages.filter { it.type != "welcome" }.all { it.token.isEmpty() })
        assertTrue(wireJson.encodeToString(a.last).contains("\"bots\":[false,true,true]"))
        assertEquals(17, a.state.hand.size)
    }

    @Test fun `offline human pauses all robot actions and resume restores same cards`() {
        val table = Table(2); val (a, b) = table.clients
        table.start()
        table.send(a, "bid", bid = 0)
        table.send(b, "bid", bid = 0)
        val before = a.state
        val hand = b.state.hand
        table.rooms.disconnect(b.peer)
        repeat(10) { table.tick() }
        assertEquals(before.revision, a.state.revision)
        assertEquals(listOf(true, false, true), a.state.online)
        val resumed = Client("new-b")
        table.send(resumed, "resume", code = a.identity.roomCode, token = b.identity.token)
        assertEquals(hand, resumed.state.hand)
        assertEquals(before.revision, resumed.state.revision)
        table.tick()
        assertEquals(before.revision + 1, a.state.revision)
        assertEquals(listOf(true, true, true), a.state.online)
        assertEquals(a.state.copy(hand = resumed.state.hand), resumed.state)
    }

    @Test fun `solo and duo naturally finish and robots wait for every human to ready`() {
        for (humanCount in 1..2) {
            val table = Table(humanCount); val a = table.clients[0]
            table.start()
            var steps = 0
            while (a.state.status != "finished" && steps++ < 400) {
                val seat = a.state.turn
                if (seat <= humanCount) table.humanMove(table.clients[seat - 1]) else table.tick()
            }
            assertEquals("finished", a.state.status, "A natural game must terminate")
            assertTrue(a.state.counts[a.state.winner - 1] == 0)
            assertEquals(0L, a.state.scores.sum())
            repeat(3) { table.tick() }
            assertEquals(List(3) { it >= humanCount }, a.state.ready)
            val round = a.state.round
            val scores = a.state.scores
            table.clients.forEachIndexed { index, client ->
                table.send(client, "ready")
                if (index < humanCount - 1) {
                    repeat(3) { table.tick() }
                    assertEquals("finished", a.state.status)
                }
            }
            assertEquals("bidding", a.state.status)
            assertEquals(round + 1, a.state.round)
            assertEquals(scores, a.state.scores)
            assertEquals(List(3) { it >= humanCount }, a.state.bots)
            assertTrue(a.messages.none { it.type == "error" })
        }
    }

    @Test fun `bot activity never extends human inactivity expiry and leave closes mixed room`() {
        val table = Table(1); val a = table.clients[0]
        table.start()
        table.send(a, "bid", bid = 0)
        table.tick()
        assertTrue(a.state.revision > 2)
        table.time = 30 * 60 * 1000L
        table.rooms.tick()
        table.rooms.sweep()
        assertEquals("expired", a.last.type)
        assertNull(a.peer.room)
        val duo = Table(2); val (b, c) = duo.clients
        duo.start()
        duo.send(b, "leave")
        assertEquals("left", b.last.type)
        assertEquals("closed", c.last.type)
        repeat(3) { duo.tick() }
        assertEquals("closed", c.last.type)
        assertNull(c.peer.room)
    }
}
