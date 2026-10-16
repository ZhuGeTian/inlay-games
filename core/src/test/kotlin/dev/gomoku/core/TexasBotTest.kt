package dev.gomoku.core

import kotlin.random.Random
import kotlin.test.*

class TexasBotTest {
    private fun state(hand: List<Int>, board: List<Int>, live: List<Boolean> = listOf(true, true, true)) =
        TexasState(status = "playing", street = "river", hand = hand, board = board, inHand = live,
            turn = 1, pot = 60, contributions = listOf(20, 20, 20), toCall = 0,
            legalActions = listOf("fold", "check", "raise", "allin"), minRaiseTo = 10, maxRaiseTo = 1000)

    @Test fun `royal flush wins and shared royal ties split equity including heads up`() {
        val nuts = state(listOf(48, 44), listOf(40, 36, 32, 1, 6))
        val win = TexasBot.estimate(nuts, 1, Random(1), 48)
        assertEquals(1.0, win.equity)
        assertEquals(60.0, win.callReturn)
        val shared = state(listOf(0, 5), listOf(32, 36, 40, 44, 48))
        assertEquals(1.0 / 3, TexasBot.estimate(shared, 1, Random(2), 48).equity, 1e-12)
        assertEquals(0.5, TexasBot.estimate(shared.copy(inHand = listOf(true, true, false)), 1, Random(2), 48).equity)
    }

    @Test fun `aces have more preflop equity than seven two and opponent count matters`() {
        val aa = state(listOf(48, 49), emptyList())
        val weak = aa.copy(hand = listOf(20, 1))
        val strongEquity = TexasBot.estimate(aa, 1, Random(10), 256).equity
        val weakEquity = TexasBot.estimate(weak, 1, Random(10), 256).equity
        assertTrue(strongEquity > 0.60, "$strongEquity")
        assertTrue(strongEquity > weakEquity + 0.25, "$strongEquity vs $weakEquity")
        assertTrue(TexasBot.estimate(aa.copy(inHand = listOf(true, true, false)), 1, Random(10), 256).equity > strongEquity)
    }

    @Test fun `unreachable side pots do not inflate short stack call return`() {
        val s = state(listOf(0, 5), listOf(32, 36, 40, 44, 48)).copy(
            contributions = listOf(10, 200, 200), pot = 410, toCall = 10)
        assertEquals(20.0, TexasBot.estimate(s, 1, Random(3), 32).callReturn)
        assertEquals(30.0, TexasBot.estimate(s.copy(folded = listOf(false, false, true)), 1, Random(3), 32).callReturn)
    }

    @Test fun `estimator ignores revealed opponent fields and never needs other real hands`() {
        val s = state(listOf(48, 49), listOf(0, 5, 10))
        val first = TexasBot.estimate(s, 1, Random(42), 48)
        val fake = s.copy(revealed = listOf(emptyList(), listOf(44,45), listOf(40,41)), lastAction = "ignored")
        assertEquals(first, TexasBot.estimate(fake, 1, Random(42), 48))
    }

    @Test fun `free check is never folded and weak hand folds expensive calls`() {
        val weak = state(listOf(0, 4), listOf(48, 49, 50, 51, 8))
        assertEquals("check", TexasBot.decide(weak.copy(legalActions = listOf("fold", "check")), 1, Random(1), 96).type)
        val costly = weak.copy(legalActions = listOf("fold", "call", "allin"), toCall = 1000,
            contributions = listOf(0, 1000, 1000), pot = 2000)
        assertEquals("fold", TexasBot.decide(costly, 1, Random(1), 192).type)
        assertEquals("call", TexasBot.decide(costly.copy(hand = listOf(44, 45)), 1, Random(1), 48).type)
    }

    @Test fun `simulation bots complete short stack and ordinary games with legal actions and conserved chips`() {
        for (seed in 0 until 36) {
            val stacks = if (seed % 2 == 0) listOf(15L, 25L, 50L) else listOf(1000L, 1000L, 1000L)
            val game = TexasGame({ (0..51).shuffled(Random(seed)) }, stacks)
            game.start()
            var steps = 0
            while (game.snapshot(1).status == "playing" && steps++ < 150) {
                val seat = game.snapshot(1).turn
                val s = game.snapshot(seat)
                val command = TexasBot.decide(s, seat, Random(seed * 1000 + steps), 12)
                assertTrue(command.type in s.legalActions)
                if (command.type == "raise") assertTrue(command.raiseTo in s.minRaiseTo..s.maxRaiseTo)
                game.act(seat, command.type, command.raiseTo, command.revision)
                val after = game.snapshot(1)
                assertEquals(stacks.sum(), after.stacks.sum() + after.pot)
            }
            assertEquals("finished", game.snapshot(1).status, "seed $seed")
            assertEquals(stacks.sum(), game.snapshot(1).stacks.sum())
            assertEquals(0, game.snapshot(1).roundDelta.sum())
        }
    }

    @Test fun `invalid sample counts and duplicate visible cards are rejected`() {
        val s = state(listOf(48,49), listOf(0,5,10))
        assertFailsWith<IllegalArgumentException> { TexasBot.estimate(s, 1, Random(1), 0) }
        assertFailsWith<IllegalArgumentException> { TexasBot.estimate(s.copy(hand=listOf(0,49)), 1, Random(1), 12) }
    }
}
