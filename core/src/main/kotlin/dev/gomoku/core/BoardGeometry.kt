package dev.gomoku.core

import kotlin.math.abs
import kotlin.math.roundToInt

data class BoardGeometry(val cell: Int, val left: Int = 10, val top: Int = 24, val rowHeight: Int = cell, val bottom: Int = left) {
    val width: Int get() = left * 2 + (BOARD_SIZE - 1) * cell
    val height: Int get() = top + (BOARD_SIZE - 1) * rowHeight + bottom

    fun intersection(px: Int, py: Int): Pair<Int, Int>? {
        val x = ((px - left).toDouble() / cell).roundToInt()
        val y = ((py - top).toDouble() / rowHeight).roundToInt()
        if (x !in 0 until BOARD_SIZE || y !in 0 until BOARD_SIZE) return null
        if (abs(px - left - x * cell) > cell * 0.46 || abs(py - top - y * rowHeight) > rowHeight * 0.46) return null
        return x to y
    }
}

