package dev.gomoku.core

import kotlinx.serialization.Serializable

const val DDZ_PROTOCOL_VERSION = 2

@Serializable
data class DdzClientMessage(
    val type: String,
    val protocol: Int = DDZ_PROTOCOL_VERSION,
    val requestId: String = "",
    val roomCode: String = "",
    val token: String = "",
    val revision: Int = -1,
    val bid: Int = 0,
    val cards: List<Int> = emptyList(),
)

@Serializable
data class DdzState(
    val status: String = "waiting",
    val hand: List<Int> = emptyList(),
    val counts: List<Int> = listOf(0, 0, 0),
    val bottom: List<Int> = emptyList(),
    val landlord: Int = 0,
    val turn: Int = 0,
    val bid: Int = 0,
    val bids: List<Int> = listOf(-1, -1, -1),
    val trick: List<Int> = emptyList(),
    val trickSeat: Int = 0,
    val passes: Int = 0,
    val revision: Int = 0,
    val winner: Int = 0,
    val multiplier: Long = 1,
    val roundDelta: List<Long> = listOf(0, 0, 0),
    val scores: List<Long> = listOf(0, 0, 0),
    val ready: List<Boolean> = listOf(false, false, false),
    val bots: List<Boolean> = listOf(false, false, false),
    val online: List<Boolean> = listOf(false, false, false),
    val round: Int = 0,
    val lastAction: String = "",
)

@Serializable
data class DdzServerMessage(
    val type: String,
    val protocol: Int = DDZ_PROTOCOL_VERSION,
    val roomCode: String = "",
    val seat: Int = 0,
    val token: String = "",
    val state: DdzState? = null,
    val requestId: String = "",
    val error: String = "",
)
