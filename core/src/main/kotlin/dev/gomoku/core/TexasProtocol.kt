package dev.gomoku.core

import kotlinx.serialization.Serializable

const val TEXAS_PROTOCOL_VERSION = 1

@Serializable
data class TexasClientMessage(
    val type: String,
    val protocol: Int = TEXAS_PROTOCOL_VERSION,
    val requestId: String = "",
    val roomCode: String = "",
    val token: String = "",
    val revision: Int = -1,
    val raiseTo: Long = 0,
)

@Serializable
data class TexasState(
    val status: String = "waiting",
    val street: String = "",
    val hand: List<Int> = emptyList(),
    val board: List<Int> = emptyList(),
    val stacks: List<Long> = listOf(1000, 1000, 1000),
    val bets: List<Long> = listOf(0, 0, 0),
    val contributions: List<Long> = listOf(0, 0, 0),
    val folded: List<Boolean> = listOf(false, false, false),
    val allIn: List<Boolean> = listOf(false, false, false),
    val inHand: List<Boolean> = listOf(false, false, false),
    val dealer: Int = 0,
    val smallBlindSeat: Int = 0,
    val bigBlindSeat: Int = 0,
    val turn: Int = 0,
    val currentBet: Long = 0,
    val minRaiseTo: Long = 0,
    val maxRaiseTo: Long = 0,
    val toCall: Long = 0,
    val legalActions: List<String> = emptyList(),
    val pot: Long = 0,
    val payouts: List<Long> = listOf(0, 0, 0),
    val returned: List<Long> = listOf(0, 0, 0),
    val roundDelta: List<Long> = listOf(0, 0, 0),
    val revealed: List<List<Int>> = listOf(emptyList(), emptyList(), emptyList()),
    val winners: List<Int> = emptyList(),
    val ready: List<Boolean> = listOf(false, false, false),
    val online: List<Boolean> = listOf(false, false, false),
    val round: Int = 0,
    val revision: Int = 0,
    val matchOver: Boolean = false,
    val lastAction: String = "",
)

@Serializable
data class TexasServerMessage(
    val type: String,
    val protocol: Int = TEXAS_PROTOCOL_VERSION,
    val roomCode: String = "",
    val seat: Int = 0,
    val token: String = "",
    val state: TexasState? = null,
    val requestId: String = "",
    val error: String = "",
)
