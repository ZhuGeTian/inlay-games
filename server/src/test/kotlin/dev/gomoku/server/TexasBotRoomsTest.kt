package dev.gomoku.server

import dev.gomoku.core.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class TexasBotRoomsTest {
    private class Client(id: String) {
        val messages = mutableListOf<TexasServerMessage>()
        val peer = TexasPeer(id, "127.0.0.1", messages::add)
        fun state() = messages.last().state!!
        fun welcome() = messages.first { it.type == "welcome" }
    }
    private var time = 0L
    private var sequence = 0
    private fun passive(state: TexasState, seat: Int): TexasClientMessage {
        assertEquals(seat, state.turn)
        assertEquals(2, state.hand.size)
        assertTrue(state.revealed.all { it.isEmpty() })
        return TexasClientMessage(if ("check" in state.legalActions) "check" else "call", revision = state.revision)
    }
    private fun rooms(decide: (TexasState, Int) -> TexasClientMessage = ::passive) =
        TexasRooms({ time }, { (0..51).toList() }, decide)
    private fun send(rooms: TexasRooms, client: Client, type: String, room: String = "", token: String = ""): TexasClientMessage =
        TexasClientMessage(type, requestId = "req-${sequence++}", roomCode = room, token = token,
            revision = client.messages.lastOrNull()?.state?.revision ?: 0).also { rooms.handle(client.peer, it) }
    private fun start(rooms: TexasRooms, humans: Int): List<Client> = List(humans) { Client("human-$it") }.also { clients ->
        send(rooms, clients[0], "create")
        clients.drop(1).forEach { send(rooms, it, "join", clients[0].welcome().roomCode) }
        send(rooms, clients[0], "start")
        assertEquals("playing", clients[0].state().status)
    }
    private fun tick(rooms: TexasRooms) { time += 700; rooms.tick() }

    @Test fun `one and two humans fill only vacant seats finish naturally and wait for humans to ready`() {
        for (humans in 1..2) {
            val rooms = rooms()
            val clients = start(rooms, humans)
            assertEquals(List(3) { it >= humans }, clients[0].state().bots)
            assertEquals(listOf(true, true, true), clients[0].state().online)
            var steps = 0
            while (clients[0].state().status == "playing" && steps++ < 30) {
                val state = clients[0].state()
                if (state.turn <= humans) {
                    val actor = clients[state.turn - 1]
                    send(rooms, actor, if (actor.state().toCall == 0L) "check" else "call")
                } else tick(rooms)
                assertEquals(3000L, clients[0].state().stacks.sum() + clients[0].state().pot)
            }
            assertEquals("finished", clients[0].state().status)
            repeat(3) { tick(rooms) }
            assertEquals(List(3) { it >= humans }, clients[0].state().ready)
            val round = clients[0].state().round
            clients.forEach { send(rooms, it, "ready") }
            assertEquals("playing", clients[0].state().status)
            assertEquals(round + 1, clients[0].state().round)
            clients.forEach { client -> assertTrue(client.messages.filter { it.type != "welcome" }.all { it.token.isEmpty() }) }
            send(rooms, clients[0], "leave")
        }
    }

    @Test fun `only online host can start and request retries are idempotent without replacing bots`() {
        val rooms = rooms(); val host = Client("host"); val guest = Client("guest")
        send(rooms, host, "create"); send(rooms, guest, "join", host.welcome().roomCode)
        send(rooms, guest, "start")
        assertEquals("error", guest.messages.last().type)
        rooms.disconnect(guest.peer)
        send(rooms, host, "start")
        assertEquals("error", host.messages.last().type)
        send(rooms, guest, "resume", host.welcome().roomCode, guest.welcome().token)
        val invalid = TexasClientMessage("start", requestId = "stale", revision = -1)
        rooms.handle(host.peer, invalid)
        assertEquals("error", host.messages.last().type)
        val command = send(rooms, host, "start")
        val state = host.state()
        val count = guest.messages.size
        rooms.handle(host.peer, command)
        assertEquals(state, host.state()); assertEquals(count, guest.messages.size)
        send(rooms, host, "start")
        assertEquals("error", host.messages.last().type); assertEquals(state, host.state())
        val outsider = Client("outsider")
        send(rooms, outsider, "join", host.welcome().roomCode)
        assertEquals("error", outsider.messages.last().type)
        send(rooms, outsider, "resume", host.welcome().roomCode, "")
        assertEquals("error", outsider.messages.last().type); assertNull(outsider.messages.last().state)
    }

    @Test fun `bot thinks once outside room lock and disconnect reconnect invalidates its result`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val calls = AtomicInteger()
        val rooms = rooms { state, seat ->
            calls.incrementAndGet(); entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            passive(state, seat)
        }
        val host = start(rooms, 1).single()
        send(rooms, host, "call")
        val revision = host.state().revision
        time += 700
        val worker = Executors.newSingleThreadExecutor()
        try {
            val computation = worker.submit { rooms.tick() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            rooms.tick()
            assertEquals(1, calls.get())
            rooms.disconnect(host.peer)
            send(rooms, host, "resume", host.welcome().roomCode, host.welcome().token)
            assertEquals(revision, host.state().revision)
            release.countDown(); computation.get(5, TimeUnit.SECONDS)
            assertEquals(revision, host.state().revision)
            tick(rooms)
            assertEquals(revision + 1, host.state().revision)
        } finally { release.countDown(); worker.shutdownNow() }
    }

    @Test fun `pending simulation is discarded on room close or ttl expiry`() {
        for (expire in listOf(false, true)) {
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val rooms = rooms { state, seat ->
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); passive(state, seat)
            }
            val host = start(rooms, 1).single()
            send(rooms, host, "call"); time += 700
            val worker = Executors.newSingleThreadExecutor()
            try {
                val computation = worker.submit { rooms.tick() }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                if (expire) time += 30 * 60 * 1000 else send(rooms, host, "leave")
                val count = host.messages.size
                release.countDown(); computation.get(5, TimeUnit.SECONDS)
                assertEquals(count, host.messages.size)
                if (expire) rooms.sweep()
                assertNull(host.peer.room)
                assertEquals(if (expire) "expired" else "left", host.messages.last().type)
            } finally { release.countDown(); worker.shutdownNow() }
        }
    }

    @Test fun `offline humans pause bots and robot actions do not keep an idle room alive`() {
        val rooms = rooms(); val host = start(rooms, 1).single()
        send(rooms, host, "call")
        rooms.disconnect(host.peer)
        val revision = host.state().revision
        repeat(3) { tick(rooms) }
        send(rooms, host, "resume", host.welcome().roomCode, host.welcome().token)
        assertEquals(revision, host.state().revision)
        time += 30 * 60 * 1000 - 700
        rooms.tick()
        assertEquals(revision + 1, host.state().revision)
        time += 700
        rooms.tick(); rooms.sweep()
        assertEquals("expired", host.messages.last().type)
    }

    @Test fun `failed bot decision is isolated and retried after delay`() {
        var fail = true
        val rooms = rooms { state, seat ->
            if (fail) { fail = false; error("injected decision failure") }
            passive(state, seat)
        }
        val host = start(rooms, 1).single(); send(rooms, host, "call")
        val revision = host.state().revision
        tick(rooms)
        assertEquals(revision, host.state().revision)
        tick(rooms)
        assertEquals(revision + 1, host.state().revision)
    }
}
