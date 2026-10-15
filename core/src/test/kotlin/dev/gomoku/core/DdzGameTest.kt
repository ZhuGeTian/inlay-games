package dev.gomoku.core

import kotlin.test.*

class DdzGameTest {
    private fun cards(vararg ranks: Int): List<Int> {
        val used = mutableMapOf<Int, Int>()
        return ranks.map { rank ->
            val copy = used.getOrDefault(rank, 0)
            used[rank] = copy + 1
            require(copy < if (rank >= 16) 1 else 4)
            if (rank >= 16) rank + 36 else (rank - 3) * 4 + copy
        }
    }

    @Test fun `every supported pattern has a stable classification`() {
        val examples = listOf(
            "single" to cards(17), "pair" to cards(15,15), "triple" to cards(14,14,14),
            "tripleSingle" to cards(3,3,3,16), "triplePair" to cards(3,3,3,15,15),
            "straight" to cards(10,11,12,13,14), "pairStraight" to cards(12,12,13,13,14,14),
            "airplane" to cards(3,3,3,4,4,4),
            "airplaneSingle" to cards(3,3,3,4,4,4,8,8),
            "airplaneSingle" to cards(3,3,3,4,4,4,5,5,5,8,8,16),
            "airplaneSingle" to cards(3,3,3,4,4,4,5,5,5,6,6,6,8,8,9,9),
            "airplanePair" to cards(3,3,3,4,4,4,8,8,15,15),
            "fourSingles" to cards(3,3,3,3,15,15),
            "fourPairs" to cards(3,3,3,3,8,8,15,15),
            "bomb" to cards(15,15,15,15), "rocket" to cards(16,17),
        )
        for ((kind, hand) in examples) {
            assertEquals(kind, DdzRules.classify(hand)?.kind, hand.toString())
            assertEquals(hand.size, DdzRules.classify(hand)?.size)
            assertEquals(DdzRules.classify(hand), DdzRules.classify(hand.reversed()))
        }
        assertEquals(3, DdzRules.rank(0))
        assertEquals(15, DdzRules.rank(51))
        assertEquals(16, DdzRules.rank(52))
        assertEquals(17, DdzRules.rank(53))
    }

    @Test fun `invalid bodies wings IDs and short sequences are rejected`() {
        val invalid = listOf(emptyList(), listOf(-1), listOf(54), listOf(0,0), cards(3,4),
            cards(3,4,5,6), cards(11,12,13,14,15), cards(3,3,4,4),
            cards(14,14,14,15,15,15), cards(3,3,3,5,5,5),
            cards(3,3,3,4,4,4,16,17),
            cards(3,3,3,3,4,4,4,8),
            cards(3,3,3,4,4,4,5,5,5,9,9,9),
            cards(3,3,3,4,4,4,9,9,9,9),
            cards(3,3,3,3,16,17), cards(3,3,3,3,9,9,9,9),
            cards(3,3,3,4,4,4,8,8,8,8,9,9),
            cards(3,3,3,4,4,4,5,5,5,6,6,6,9,9,9,9))
        for (hand in invalid) assertNull(DdzRules.classify(hand), hand.toString())
        assertFailsWith<IllegalArgumentException> { DdzRules.rank(54) }
    }

    @Test fun `only same size and kind compare except bomb and rocket`() {
        fun c(vararg rank: Int) = DdzRules.classify(cards(*rank))!!
        assertTrue(DdzRules.beats(c(15), c(14)))
        assertFalse(DdzRules.beats(c(14), c(14)))
        assertFalse(DdzRules.beats(c(15,15), c(14)))
        assertTrue(DdzRules.beats(c(4,4,4,5), c(3,3,3,17)))
        assertTrue(DdzRules.beats(c(4,5,6,7,8), c(3,4,5,6,7)))
        assertFalse(DdzRules.beats(c(4,5,6,7,8,9), c(3,4,5,6,7)))
        assertTrue(DdzRules.beats(c(3,3,3,3), c(17)))
        assertTrue(DdzRules.beats(c(4,4,4,4), c(3,3,3,3)))
        assertFalse(DdzRules.beats(c(17), c(3,3,3,3)))
        assertTrue(DdzRules.beats(c(16,17), c(15,15,15,15)))
        assertFalse(DdzRules.beats(c(15,15,15,15), c(16,17)))
        assertFalse(DdzRules.beats(c(16,17), c(16,17)))
    }

    @Test fun `deal exposes only own 17 cards and hides bottom before bidding ends`() {
        val game = DdzGame { (0..53).toList() }
        assertEquals("waiting", game.snapshot(1).status)
        assertTrue(game.snapshot(1).hand.isEmpty())
        game.start()
        val views = (1..3).map(game::snapshot)
        assertEquals(51, views.flatMap { it.hand }.toSet().size)
        views.forEach { assertEquals(listOf(17,17,17), it.counts); assertTrue(it.bottom.isEmpty()) }
        assertEquals((0..16).toList(), views[0].hand)
        game.bid(1, 1, 1)
        game.bid(2, 2, 2)
        game.bid(3, 0, 3)
        assertEquals(2, game.snapshot(1).landlord)
        assertEquals(2, game.snapshot(1).turn)
        assertEquals(2, game.snapshot(1).bid)
        assertEquals(listOf(17,20,17), game.snapshot(1).counts)
        assertEquals(listOf(51,52,53), game.snapshot(1).bottom)
        assertEquals(54, (1..3).flatMap { game.snapshot(it).hand }.toSet().size)
    }

    @Test fun `three bid immediately selects landlord and all passes redeal with rotated starter`() {
        var deals = 0
        val game = DdzGame { deals++; (0..53).toList() }
        game.start()
        game.bid(1,0,1); game.bid(2,0,2); game.bid(3,0,3)
        assertEquals(2, deals)
        assertEquals(2, game.snapshot(1).turn)
        assertEquals(2, game.snapshot(1).round)
        assertEquals(listOf(-1,-1,-1), game.snapshot(1).bids)
        game.bid(2,3,4)
        assertEquals("playing", game.snapshot(1).status)
        assertEquals(2, game.snapshot(1).landlord)
        assertEquals(listOf(-1,3,-1), game.snapshot(1).bids)
    }

    private fun gameWithLandlord(hand: List<Int> = (0..19).toList()): DdzGame {
        require(hand.size == 20)
        val deck = hand.take(17) + (0..53).filterNot { it in hand } + hand.takeLast(3)
        return DdzGame { deck }.apply { start(); bid(1, 3, 1) }
    }

    @Test fun `passes return lead after two and lower plays and duplicate IDs are atomic failures`() {
        val game = gameWithLandlord()
        val start = game.snapshot(1)
        assertFailsWith<GameError> { game.pass(1,start.revision) }
        assertEquals(start, game.snapshot(1))
        game.play(1,listOf(0),start.revision)
        val before = game.snapshot(2)
        listOf<() -> Unit>(
            { game.play(2,listOf(20,20),before.revision) },
            { game.play(2,listOf(0),before.revision) },
            { game.play(2,listOf(20),before.revision - 1) },
            { game.play(3,listOf(37),before.revision) },
            { game.play(0,listOf(20),before.revision) },
        ).forEach { op -> assertFailsWith<GameError> { op() }; assertEquals(before, game.snapshot(2)) }
        game.play(2,listOf(20),before.revision)
        game.pass(3,game.snapshot(3).revision)
        val landlord = game.snapshot(1)
        assertFailsWith<GameError> { game.play(1,listOf(1),landlord.revision) }
        assertEquals(landlord, game.snapshot(1))
        game.pass(1,landlord.revision)
        assertEquals(2, game.snapshot(1).turn)
        assertEquals(0, game.snapshot(1).trickSeat)
        assertEquals(0, game.snapshot(1).passes)
        assertTrue(game.snapshot(1).trick.isEmpty())
    }

    private fun passOthers(game: DdzGame) {
        repeat(2) { val state = game.snapshot(1); game.pass(state.turn, state.revision) }
    }

    @Test fun `landlord bombs spring and unanimous rematch preserve zero sum scores`() {
        val game = gameWithLandlord()
        repeat(5) { rank ->
            game.play(1,(rank * 4 until rank * 4 + 4).toList(),game.snapshot(1).revision)
            if (rank < 4) passOthers(game)
        }
        val finished = game.snapshot(1)
        assertEquals("finished", finished.status)
        assertEquals(1, finished.winner)
        assertEquals(64L, finished.multiplier)
        assertEquals(listOf(384L,-192L,-192L), finished.roundDelta)
        assertEquals(0L, finished.scores.sum())
        game.ready(1,finished.revision)
        val ready = game.snapshot(1)
        assertFailsWith<GameError> { game.ready(1,ready.revision) }
        assertEquals(ready,game.snapshot(1))
        game.ready(2,ready.revision)
        assertEquals("finished",game.snapshot(1).status)
        game.ready(3,game.snapshot(1).revision)
        val rematch = game.snapshot(1)
        assertEquals("bidding",rematch.status)
        assertEquals(2,rematch.turn)
        assertEquals(finished.scores,rematch.scores)
        assertEquals(listOf(0L,0L,0L),rematch.roundDelta)
        assertEquals(listOf(false,false,false),rematch.ready)
        assertEquals(1L,rematch.multiplier)
        assertTrue(rematch.bottom.isEmpty())
        game.bid(2,3,rematch.revision)
        game.resign(1,game.snapshot(1).revision)
        assertEquals(listOf(381L,-186L,-195L),game.snapshot(1).scores)
    }

    @Test fun `farmer wins for both farmers with reverse spring`() {
        val game = gameWithLandlord()
        game.play(1,listOf(0),game.snapshot(1).revision)
        val farmerHand = game.snapshot(2).hand
        farmerHand.forEachIndexed { index, card ->
            game.play(2,listOf(card),game.snapshot(2).revision)
            if (index < farmerHand.lastIndex) passOthers(game)
        }
        val result = game.snapshot(1)
        assertEquals(2,result.winner)
        assertEquals(2L,result.multiplier)
        assertEquals(listOf(-12L,6L,6L),result.roundDelta)
    }

    @Test fun `farmer win after two landlord plays does not count as reverse spring`() {
        val game = gameWithLandlord()
        repeat(2) { index ->
            game.play(1,listOf(index),game.snapshot(1).revision)
            if (index == 0) passOthers(game)
        }
        val farmerHand = game.snapshot(2).hand
        farmerHand.forEachIndexed { index, card ->
            game.play(2,listOf(card),game.snapshot(2).revision)
            if (index < farmerHand.lastIndex) passOthers(game)
        }
        assertEquals(1L,game.snapshot(1).multiplier)
        assertEquals(listOf(-6L,3L,3L),game.snapshot(1).roundDelta)
    }

    @Test fun `rocket doubles and resignation never adds spring`() {
        val game = gameWithLandlord((0..17).toList() + listOf(52,53))
        game.play(1,listOf(52,53),game.snapshot(1).revision)
        assertEquals(2L,game.snapshot(1).multiplier)
        game.resign(2,game.snapshot(2).revision)
        val result = game.snapshot(1)
        assertEquals(1,result.winner)
        assertEquals(2L,result.multiplier)
        assertEquals(listOf(12L,-6L,-6L),result.roundDelta)
        val landlordResigns = gameWithLandlord()
        landlordResigns.resign(1,landlordResigns.snapshot(1).revision)
        assertEquals(listOf(-6L,3L,3L),landlordResigns.snapshot(1).roundDelta)
    }

    @Test fun `bad seats stages bids revisions and invalid deck never mutate game`() {
        val game = DdzGame { (0..53).toList() }
        assertFailsWith<GameError> { game.snapshot(0) }
        assertFailsWith<GameError> { game.snapshot(4) }
        val waiting = game.snapshot(1)
        assertFailsWith<GameError> { game.bid(1,3,0) }
        assertFailsWith<GameError> { game.resign(1,0) }
        assertEquals(waiting,game.snapshot(1))
        game.start()
        val before = game.snapshot(1)
        listOf<() -> Unit>(
            { game.start() }, { game.bid(1,4,1) }, { game.bid(1,-1,1) },
            { game.bid(2,3,1) }, { game.bid(1,3,0) }, { game.ready(1,1) },
        ).forEach { op -> assertFailsWith<GameError> { op() }; assertEquals(before,game.snapshot(1)) }
        game.bid(1,2,1)
        val raised = game.snapshot(2)
        assertFailsWith<GameError> { game.bid(2,2,2) }
        assertEquals(raised,game.snapshot(2))
        val invalid = DdzGame { List(54) { 0 } }
        assertFailsWith<GameError> { invalid.start() }
        assertEquals("waiting",invalid.snapshot(1).status)
        var valid = true
        val redeal = DdzGame { if (valid) (0..53).toList() else emptyList() }
        redeal.start(); redeal.bid(1,0,1); redeal.bid(2,0,2)
        val lastBid = redeal.snapshot(3)
        valid = false
        assertFailsWith<GameError> { redeal.bid(3,0,3) }
        assertEquals(lastBid,redeal.snapshot(3))
    }
}
