package dev.gomoku.core

import kotlin.random.Random
import kotlin.test.*

class TexasGameTest {
    private fun game(stacks: List<Long> = listOf(1000, 1000, 1000), deck: List<Int> = (0..51).toList()) =
        TexasGame({ deck }, stacks).also { it.start() }
    private fun TexasGame.state() = snapshot(1)
    private fun TexasGame.action(type: String, target: Long = 0) {
        val state = state()
        act(state.turn, type, target, state.revision)
    }
    private fun TexasGame.passiveFinish() {
        repeat(30) {
            if (state().status == "finished") return
            val own = snapshot(state().turn)
            action(if ("check" in own.legalActions) "check" else "call")
        }
        fail("Hand did not finish")
    }
    private fun TexasGame.allReady() { for (seat in 1..3) ready(seat, state().revision) }
    private fun deck(hands: List<List<Int>>, board: List<Int>): List<Int> {
        val fixed = mutableMapOf(0 to hands[1][0], 1 to hands[2][0], 2 to hands[0][0],
            3 to hands[1][1], 4 to hands[2][1], 5 to hands[0][1],
            7 to board[0], 8 to board[1], 9 to board[2], 11 to board[3], 13 to board[4])
        require(fixed.values.toSet().size == fixed.size)
        val remaining = (0..51).filter { it !in fixed.values }.iterator()
        return (0..51).map { fixed[it] ?: remaining.next() }
    }
    private val pairDeck = deck(listOf(listOf(48, 49), listOf(44, 45), listOf(40, 41)), listOf(0, 5, 22, 31, 36))

    @Test fun `deal is private turn blinds and each street follow expected order`() {
        val game = game()
        assertEquals(listOf(1000L, 995L, 990L), game.state().stacks)
        assertEquals(1, game.state().turn)
        assertEquals(listOf(2, 5), game.snapshot(1).hand)
        assertEquals(listOf(0, 3), game.snapshot(2).hand)
        assertEquals(listOf(1, 4), game.snapshot(3).hand)
        assertTrue(game.state().revealed.flatten().isEmpty())
        game.action("call"); game.action("call")
        assertEquals(3, game.state().turn)
        assertTrue("check" in game.snapshot(3).legalActions)
        game.action("check")
        assertEquals(listOf(7, 8, 9), game.state().board)
        assertEquals(2, game.state().turn)
        game.passiveFinish()
        assertEquals(listOf(7, 8, 9, 11, 13), game.state().board)
        assertEquals(3000, game.state().stacks.sum())
        assertEquals(0, game.state().roundDelta.sum())
    }

    @Test fun `invalid commands and stale revision leave exact state intact`() {
        val game = game()
        val before = game.state()
        for (action in listOf<() -> Unit>(
            { game.act(2, "call", 0, before.revision) }, { game.act(1, "call", 0, 0) },
            { game.act(1, "raise", 19, before.revision) }, { game.act(1, "raise", Long.MAX_VALUE, before.revision) },
            { game.act(1, "check", 0, before.revision) }, { game.act(1, "ready", 0, before.revision) },
            { game.ready(1, before.revision) }, { game.act(0, "call", 0, before.revision) })) {
            assertFailsWith<GameError> { action() }
            assertEquals(before, game.state())
        }
    }

    @Test fun `fold win returns uncalled bet without revealing any hand`() {
        val game = game()
        game.action("raise", 100)
        game.action("fold"); game.action("fold")
        val done = game.state()
        assertEquals("finished", done.status)
        assertEquals(listOf(25L, 0L, 0L), done.payouts)
        assertEquals(listOf(90L, 0L, 0L), done.returned)
        assertEquals(listOf(1015L, 995L, 990L), done.stacks)
        assertTrue(done.revealed.flatten().isEmpty())
        assertEquals(0, done.pot)
        assertEquals(listOf("ready"), done.legalActions)
        game.allReady()
        assertEquals(2, game.state().dealer)
        assertEquals(3, game.state().smallBlindSeat)
        assertEquals(1, game.state().bigBlindSeat)
    }

    @Test fun `multiple side pots and unmatched excess go to their proper owners`() {
        val game = game(listOf(50, 100, 200), pairDeck)
        game.action("allin"); game.action("allin")
        assertFalse("raise" in game.snapshot(3).legalActions)
        assertFalse("allin" in game.snapshot(3).legalActions)
        game.action("call")
        assertEquals(listOf(150L, 100L, 0L), game.state().payouts)
        assertEquals(listOf(150L, 100L, 100L), game.state().stacks)
        assertEquals(listOf(100L, 0L, -100L), game.state().roundDelta)
        assertEquals(5, game.state().board.size)
        val excess = game(listOf(200, 100, 50), pairDeck)
        excess.action("allin"); excess.action("call"); excess.action("call")
        assertEquals(listOf(100L, 0L, 0L), excess.state().returned)
        assertEquals(listOf(250L, 0L, 0L), excess.state().payouts)
        assertEquals(350, excess.state().stacks.sum())
        assertTrue(excess.state().matchOver)
        excess.allReady()
        assertEquals(3000, excess.state().stacks.sum() + excess.state().pot)
        assertEquals(listOf(2, 2, 2), (1..3).map { excess.snapshot(it).hand.size })
    }

    @Test fun `odd chip in tied pot goes clockwise left of dealer and folded cards stay secret`() {
        val tied = deck(listOf(listOf(0, 5), listOf(8, 13), listOf(16, 21)), listOf(32, 36, 40, 44, 48))
        val game = game(deck = tied)
        game.action("raise", 21); game.action("call"); game.action("call")
        game.action("fold")
        game.passiveFinish()
        assertEquals(listOf(31L, 0L, 32L), game.state().payouts)
        assertEquals(emptyList(), game.state().revealed[1])
        assertEquals(2, game.state().revealed[0].size)
        assertEquals(2, game.state().revealed[2].size)
    }

    @Test fun `one short all in does not reopen but cumulative short all ins do`() {
        val short = game(listOf(1000, 150, 1000))
        short.action("raise", 100); short.action("allin"); short.action("call")
        assertEquals(1, short.state().turn)
        assertFalse("raise" in short.snapshot(1).legalActions)
        assertFalse("allin" in short.snapshot(1).legalActions)
        val before = short.state()
        assertFailsWith<GameError> { short.action("raise", 240) }
        assertEquals(before, short.state())
        short.action("call")
        assertEquals("flop", short.state().street)
        assertTrue("raise" in short.snapshot(1).legalActions || "raise" in short.snapshot(3).legalActions)
        // Three seats allow cumulative short raises, but the sole remaining stack cannot raise an all-in opponent.
        val accumulated = game(listOf(1000, 150, 190))
        accumulated.action("raise", 100); accumulated.action("allin"); accumulated.action("allin")
        assertEquals(190, accumulated.state().currentBet)
        assertFalse("raise" in accumulated.snapshot(1).legalActions)
        accumulated.action("call")
        assertEquals("finished", accumulated.state().status)
        assertEquals(1340, accumulated.state().stacks.sum())
    }

    @Test fun `full raise reopens and minimum raise follows the increment`() {
        val game = game()
        game.action("raise", 100); game.action("raise", 190); game.action("call")
        assertTrue("raise" in game.snapshot(1).legalActions)
        assertEquals(280, game.snapshot(1).minRaiseTo)
        game.action("raise", 280)
        assertEquals(370, game.snapshot(2).minRaiseTo)
    }

    @Test fun `opening short all in still requires a full minimum raise increment`() {
        val game = game(listOf(1000, 15, 1000))
        game.action("call"); game.action("call"); game.action("check")
        assertEquals(2, game.state().turn)
        game.action("allin")
        assertEquals(5, game.state().currentBet)
        assertEquals(15, game.snapshot(3).minRaiseTo)
        assertFailsWith<GameError> { game.action("raise", 10) }
        game.action("raise", 15)
        assertEquals(25, game.snapshot(1).minRaiseTo)
    }

    @Test fun `all in blinds cap unopposed calls and run out automatically`() {
        val game = game(listOf(1000, 5, 3))
        assertEquals(5, game.snapshot(1).toCall)
        assertEquals(listOf("fold", "call"), game.snapshot(1).legalActions)
        game.action("call")
        assertEquals("finished", game.state().status)
        assertEquals(1008, game.state().stacks.sum())
        assertEquals(listOf(5L, 5L, 3L), game.state().contributions)
        val all = game(listOf(3, 2, 1))
        all.action("call")
        assertEquals("finished", all.state().status)
        assertEquals(6, all.state().stacks.sum())
    }

    @Test fun `short big blind requires only its actual amount once no raise is possible`() {
        val game = game(listOf(1000, 1000, 8))
        assertEquals(10, game.snapshot(1).toCall)
        game.action("fold")
        assertEquals(2, game.state().turn)
        assertEquals(3, game.snapshot(2).toCall)
        assertEquals(listOf("fold", "call"), game.snapshot(2).legalActions)
        game.action("call")
        assertEquals("finished", game.state().status)
        assertEquals(listOf(0L, 8L, 8L), game.state().contributions)
        assertEquals(listOf(0L, 0L, 0L), game.state().returned)
        assertEquals(2008, game.state().stacks.sum())
    }

    @Test fun `checking then facing a short opening all in does not reopen raises`() {
        val game = game(listOf(1000, 1000, 15))
        game.action("call"); game.action("call"); game.action("check")
        game.action("check"); game.action("allin"); game.action("call")
        assertEquals(2, game.state().turn)
        assertEquals(5, game.snapshot(2).toCall)
        assertFalse("raise" in game.snapshot(2).legalActions)
        assertFalse("allin" in game.snapshot(2).legalActions)
        game.action("call")
        assertEquals("turn", game.state().street)
        assertTrue("raise" in game.snapshot(2).legalActions)
    }

    @Test fun `heads up blinds and action switch correctly after elimination`() {
        val game = game(listOf(50, 100, 100), pairDeck)
        game.action("allin"); game.action("call"); game.action("call")
        game.action("allin"); game.action("call")
        assertEquals(0, game.state().stacks[2])
        game.allReady()
        val state = game.state()
        assertEquals(1, state.bigBlindSeat)
        assertEquals(2, state.dealer)
        assertEquals(2, state.smallBlindSeat)
        assertEquals(2, state.turn)
        assertTrue(game.snapshot(3).hand.isEmpty())
        game.action("call"); game.action("check")
        assertEquals(1, game.state().turn)
        assertEquals("flop", game.state().street)
    }

    @Test fun `failed redeal keeps the completed hand and readiness intact`() {
        var valid = true
        val game = TexasGame({ if (valid) (0..51).toList() else emptyList() })
        game.start(); game.action("fold"); game.action("fold")
        game.ready(1, game.state().revision); game.ready(2, game.state().revision)
        val before = game.state()
        valid = false
        assertFailsWith<GameError> { game.ready(3, before.revision) }
        assertEquals(before, game.state())
    }

    @Test fun `random legal hands conserve chips and never expose opponents before showdown`() {
        val random = Random(8721)
        repeat(100) {
            val game = TexasGame({ (0..51).shuffled(random) })
            game.start()
            repeat(300) action@{
                val state = game.state()
                assertTrue(state.stacks.all { it >= 0 })
                assertEquals(3000, state.stacks.sum() + state.pot)
                if (state.status == "finished") return@action
                assertTrue(state.revealed.flatten().isEmpty())
                val own = game.snapshot(state.turn)
                val action = own.legalActions.random(random)
                game.action(action, if (action == "raise") own.minRaiseTo else 0)
            }
            assertEquals("finished", game.state().status)
            assertEquals(0, game.state().roundDelta.sum())
        }
    }
}
