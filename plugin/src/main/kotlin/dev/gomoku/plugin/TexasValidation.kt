package dev.gomoku.plugin

import dev.gomoku.core.TexasState
import java.awt.Color

internal fun texasRank(card: Int): String = when (val rank = card / 4 + 2) {
    11 -> "J"; 12 -> "Q"; 13 -> "K"; 14 -> "A"; else -> rank.toString()
}

// The same suit keeps its color in the private hand, board and showdown.
internal fun texasColor(card: Int, dark: Boolean): Color = Color(
    (if (dark) intArrayOf(0x61AFEF, 0xE06C75, 0x98C379, 0xE5A05A)
    else intArrayOf(0x1565C0, 0xC62828, 0x287D32, 0xAD6500))[card % 4]
)

internal fun validTexasRaise(s: TexasState, amount: Long) = amount in s.minRaiseTo..s.maxRaiseTo && amount > s.currentBet

internal fun validTexasState(s: TexasState): Boolean {
    fun cards(values: List<Int>) = values.distinct().size == values.size && values.all { it in 0..51 }
    fun amounts(values: List<Long>) = values.size == 3 && values.all { it in 0..3000 }
    val exposed = s.revealed.flatten()
    return s.status in setOf("waiting", "playing", "finished") &&
        s.street in setOf("", "preflop", "flop", "turn", "river") &&
        s.hand.size in setOf(0, 2) && s.board.size in setOf(0, 3, 4, 5) && cards(s.hand + s.board) &&
        s.revealed.size == 3 && s.revealed.all { it.size in setOf(0, 2) && cards(it) } && cards(exposed + s.board) &&
        amounts(s.stacks) && amounts(s.bets) && amounts(s.contributions) && amounts(s.payouts) && amounts(s.returned) &&
        s.roundDelta.size == 3 && s.roundDelta.all { it in -3000..3000 } &&
        listOf(s.folded, s.allIn, s.inHand, s.ready, s.online).all { it.size == 3 } &&
        listOf(s.dealer, s.smallBlindSeat, s.bigBlindSeat, s.turn).all { it in 0..3 } &&
        s.winners.distinct().size == s.winners.size && s.winners.all { it in 1..3 } &&
        listOf(s.currentBet, s.minRaiseTo, s.maxRaiseTo, s.toCall, s.pot).all { it in 0..6000 } &&
        s.legalActions.distinct().size == s.legalActions.size && s.legalActions.all { it in setOf("fold", "check", "call", "raise", "allin", "ready") } &&
        s.round >= 0 && s.revision >= 0 && s.lastAction.length <= 256
}
