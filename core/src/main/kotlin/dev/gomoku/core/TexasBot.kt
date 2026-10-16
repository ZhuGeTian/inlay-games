package dev.gomoku.core

import kotlin.random.Random

/** Decisions use only this seat's private snapshot, never the actual deck or other hands. */
object TexasBot {
    internal data class Estimate(val equity: Double, val callReturn: Double)

    fun decide(state: TexasState, seat: Int): TexasClientMessage = decide(state, seat, Random.Default, 192)

    internal fun decide(state: TexasState, seat: Int, random: Random, samples: Int): TexasClientMessage {
        require(seat in 1..3)
        fun action(type: String, target: Long = 0) = TexasClientMessage(type, revision = state.revision, raiseTo = target)
        if ("ready" in state.legalActions) return action("ready")
        require(state.status == "playing" && state.turn == seat && state.legalActions.isNotEmpty())
        val estimate = estimate(state, seat, random, samples)
        val legal = state.legalActions
        val potAfterCall = state.pot + state.toCall
        val strong = estimate.equity >= 0.65
        if (strong && "raise" in legal && random.nextDouble() < 0.8) {
            val increment = maxOf(state.minRaiseTo - state.currentBet, potAfterCall / 2)
            val target = minOf(state.maxRaiseTo, state.currentBet + increment)
            return if (target == state.maxRaiseTo && "allin" in legal) action("allin") else action("raise", target)
        }
        if (estimate.equity >= 0.85 && "allin" in legal && state.maxRaiseTo < state.minRaiseTo) return action("allin")
        if ("check" in legal) return action("check")
        // Existing contributions are sunk; compare only the extra call with eligible pool returns.
        if ("call" in legal && estimate.callReturn >= state.toCall * 1.02) return action("call")
        return action("fold")
    }

    internal fun estimate(state: TexasState, seat: Int, random: Random, samples: Int): Estimate {
        require(seat in 1..3 && samples in 1..4096)
        require(state.hand.size == 2 && state.board.size in setOf(0, 3, 4, 5))
        val known = state.hand + state.board
        require(known.distinct().size == known.size && known.all { it in 0..51 })
        val hero = seat - 1
        val live = (0..2).filter { state.inHand[it] && !state.folded[it] }
        require(hero in live)
        val opponents = live.filter { it != hero }
        val unseen = (0..51).filter { it !in known }.toIntArray()
        val drawCount = 5 - state.board.size + opponents.size * 2
        val contributions = state.contributions.toMutableList().also { it[hero] += state.toCall }
        // Each layer includes folded dead money, but only eligible live seats can win it.
        var previous = 0L
        val pools = contributions.filter { it > 0 }.distinct().sorted().mapNotNull { level ->
            val contributors = (0..2).filter { contributions[it] >= level }
            val amount = (level - previous) * contributors.size
            previous = level
            if (hero !in contributors) null else amount to contributors.filter { it in live }
        }
        var equity = 0.0
        var callReturn = 0.0
        repeat(samples) {
            // Partial Fisher-Yates draws without replacement; folded hands/burn cards remain unknown.
            for (i in 0 until drawCount) {
                val j = random.nextInt(i, unseen.size)
                val saved = unseen[i]; unseen[i] = unseen[j]; unseen[j] = saved
            }
            var at = 0
            val board = state.board + List(5 - state.board.size) { unseen[at++] }
            val scores = LongArray(3)
            scores[hero] = TexasRules.strength(state.hand + board)
            for (opponent in opponents) scores[opponent] = TexasRules.strength(listOf(unseen[at++], unseen[at++]) + board)
            val best = live.maxOf { scores[it] }
            if (scores[hero] == best) equity += 1.0 / live.count { scores[it] == best }
            for ((amount, eligible) in pools) {
                val poolBest = eligible.maxOf { scores[it] }
                if (scores[hero] == poolBest) callReturn += amount.toDouble() / eligible.count { scores[it] == poolBest }
            }
        }
        // shortcut: uniform opponent ranges and fixed samples; add range modelling only if stronger play is needed.
        return Estimate(equity / samples, callReturn / samples)
    }
}
