package dev.gomoku.core

import kotlin.random.Random
import kotlin.test.*
import kotlin.system.measureTimeMillis

class DdzBotTest {
    private fun cards(vararg ranks: Int): List<Int> {
        val used = mutableMapOf<Int, Int>()
        return ranks.map { rank ->
            val copy = used.getOrDefault(rank, 0)
            used[rank] = copy + 1
            require(copy < if (rank >= 16) 1 else 4)
            if (rank >= 16) rank + 36 else (rank - 3) * 4 + copy
        }
    }

    @Test fun `card labels cover face cards two and both jokers`() {
        assertEquals(listOf("3", "10", "J", "Q", "K", "A", "2", "Xiao", "Da"),
            cards(3, 10, 11, 12, 13, 14, 15, 16, 17).map(DdzRules::label))
        assertFailsWith<IllegalArgumentException> { DdzRules.label(54) }
        val game = DdzGame { (0..53).toList() }.apply { start(); bid(1, 3, 1) }
        game.play(1, listOf(52, 53), 2)
        assertEquals("1:play(Xiao,Da)", game.snapshot(1).lastAction)
    }

    @Test fun `candidate generator supports every rule including complex wings`() {
        val examples = listOf(
            cards(17), cards(15,15), cards(14,14,14), cards(3,3,3,16), cards(3,3,3,15,15),
            cards(10,11,12,13,14), cards(12,12,13,13,14,14), cards(3,3,3,4,4,4),
            cards(3,3,3,4,4,4,8,8), cards(3,3,3,4,4,4,5,5,5,8,8,16),
            cards(3,3,3,4,4,4,5,5,5,6,6,6,8,8,9,9),
            cards(3,3,3,4,4,4,8,8,15,15), cards(3,3,3,3,15,15),
            cards(3,3,3,3,8,8,15,15), cards(15,15,15,15), cards(16,17),
        )
        for (hand in examples) {
            val generated = DdzBot.candidates(hand)
            assertTrue(hand.sorted() in generated, "Missing ${DdzRules.classify(hand)}")
            generated.forEach { assertNotNull(DdzRules.classify(it)); assertTrue(hand.containsAll(it)) }
            val state = DdzState(status = "playing", hand = hand, counts = listOf(hand.size, 17, 17), landlord = 1, turn = 1)
            assertEquals(hand.sorted(), DdzBot.decide(state, 1).cards.sorted())
        }
    }

    @Test fun `generated rank shapes match exhaustive small hand oracle`() {
        val random = Random(93)
        repeat(16) {
            val hand = (0..53).shuffled(random).take(12)
            val expected = (1 until (1 shl hand.size)).mapNotNull { mask ->
                val subset = hand.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
                if (DdzRules.classify(subset) == null) null else subset.map(DdzRules::rank).sorted()
            }.toSet()
            val actual = DdzBot.candidates(hand).map { it.map(DdzRules::rank).sorted() }.toSet()
            assertEquals(expected, actual)
        }
    }

    @Test fun `bot obeys phase turn lead and ready boundaries`() {
        val hand = cards(3, 4, 5)
        val state = DdzState(status = "playing", hand = hand, counts = listOf(3, 2, 2), landlord = 1, turn = 1, revision = 18)
        assertEquals("play", DdzBot.decide(state, 1).type)
        assertEquals(18, DdzBot.decide(state, 1).revision)
        assertFailsWith<IllegalArgumentException> { DdzBot.decide(state, 2) }
        assertFailsWith<IllegalArgumentException> { DdzBot.decide(state, 0) }
        assertFailsWith<IllegalArgumentException> { DdzBot.decide(DdzState(), 1) }
        assertFailsWith<IllegalArgumentException> { DdzBot.decide(state.copy(hand = listOf(54)), 1) }
        assertEquals("pass", DdzBot.decide(state.copy(trick = cards(17), trickSeat = 2), 1).type)
        assertEquals("ready", DdzBot.decide(state.copy(status = "finished", turn = 0), 1).type)
        assertFailsWith<IllegalArgumentException> {
            DdzBot.decide(state.copy(status = "finished", ready = listOf(true, false, false)), 1)
        }
    }

    @Test fun `farmers preserve teammate lead unless finishing or landlord is near empty`() {
        val state = DdzState(status = "playing", hand = cards(8, 9), counts = listOf(10, 2, 5),
            landlord = 1, turn = 2, trick = cards(7), trickSeat = 3)
        assertEquals("pass", DdzBot.decide(state, 2).type)
        assertEquals("play", DdzBot.decide(state.copy(hand = cards(8), counts = listOf(10, 1, 5)), 2).type)
        assertEquals("play", DdzBot.decide(state.copy(counts = listOf(1, 2, 5)), 2).type)
    }

    @Test fun `last bidder guarantees a call with a weak hand`() {
        val state = DdzState(status = "bidding", hand = cards(3, 4, 5), turn = 3, bids = listOf(0, 0, -1))
        assertEquals(1, DdzBot.decide(state, 3).bid)
        assertEquals(0, DdzBot.decide(state.copy(bid = 2, bids = listOf(2, 0, -1)), 3).bid)
    }

    @Test fun `random tables finish with legal private hand decisions and zero sum rematches`() {
        val random = Random(713)
        repeat(40) {
            val game = DdzGame { (0..53).shuffled(random) }.apply { start() }
            repeat(2) { round ->
                var moves = 0
                while (game.snapshot(1).status != "finished") {
                    assertTrue(++moves <= 165, "Bot table did not finish")
                    val seat = game.snapshot(1).turn
                    val before = game.snapshot(seat)
                    val action = DdzBot.decide(before, seat)
                    assertEquals(before.revision, action.revision)
                    when (action.type) {
                        "bid" -> game.bid(seat, action.bid, action.revision)
                        "play" -> {
                            assertTrue(before.hand.containsAll(action.cards))
                            assertNotNull(DdzRules.classify(action.cards))
                            game.play(seat, action.cards, action.revision)
                            assertEquals(before.hand.size - action.cards.size, game.snapshot(seat).hand.size)
                        }
                        "pass" -> game.pass(seat, action.revision)
                        else -> fail("Unexpected ${action.type}")
                    }
                    assertEquals(before.revision + 1, game.snapshot(1).revision)
                    val allHands = (1..3).flatMap { game.snapshot(it).hand }
                    assertEquals(allHands.size, allHands.distinct().size)
                }
                val end = game.snapshot(1)
                assertEquals(round + 1, end.round)
                assertEquals(0L, end.roundDelta.sum())
                assertEquals(0L, end.scores.sum())
                assertTrue(end.counts[end.winner - 1] == 0)
                for (seat in 1..3) {
                    val action = DdzBot.decide(game.snapshot(seat), seat)
                    assertEquals("ready", action.type)
                    game.ready(seat, action.revision)
                }
            }
        }
    }

    @Test fun `twenty card compound hands have bounded decision cost`() {
        val hands = listOf(cards(3,3,3,4,4,4,5,5,5,6,6,6,7,8,9,10,11,12,16,17),
            cards(3,3,3,3,4,4,4,4,5,5,5,5,6,6,6,6,7,7,7,7))
        hands.forEach(DdzBot::candidates)
        val elapsed = measureTimeMillis {
            repeat(20) { for (hand in hands) {
                val candidates = DdzBot.candidates(hand)
                assertTrue(candidates.size < 10000)
                assertTrue(candidates.all { DdzRules.classify(it) != null })
            } }
        }
        assertTrue(elapsed < 10000, "40 complex-hand generations took ${elapsed}ms")
    }
}
