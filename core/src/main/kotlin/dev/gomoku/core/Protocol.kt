package dev.gomoku.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val BOARD_SIZE = 15
const val PROTOCOL_VERSION = 1
val wireJson = Json { encodeDefaults = true; ignoreUnknownKeys = false }

@Serializable
data class ClientMessage(
    val type: String,
    val protocol: Int = PROTOCOL_VERSION,
    val requestId: String = "",
    val roomCode: String = "",
    val token: String = "",
    val x: Int = -1,
    val y: Int = -1,
    val revision: Int = -1,
)

@Serializable
data class GameState(
    val board: List<Int> = List(BOARD_SIZE * BOARD_SIZE) { 0 },
    val status: String = "waiting",
    val turn: Int = 1,
    val winner: Int = 0,
    val revision: Int = 0,
    val lastMove: Int = -1,
    val blackOnline: Boolean = false,
    val whiteOnline: Boolean = false,
)

@Serializable
data class ServerMessage(
    val type: String,
    val protocol: Int = PROTOCOL_VERSION,
    val roomCode: String = "",
    val seat: Int = 0,
    val token: String = "",
    val state: GameState? = null,
    val requestId: String = "",
    val error: String = "",
)

