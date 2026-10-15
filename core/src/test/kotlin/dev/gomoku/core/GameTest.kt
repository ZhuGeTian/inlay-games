package dev.gomoku.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlin.test.*

class GameTest {
    @Test fun `wins in all four directions`() {
        for ((dx, dy) in listOf(1 to 0, 0 to 1, 1 to 1, 1 to -1)) {
            val game = Game().apply { start() }
            repeat(5) { i ->
                game.move(1, 3 + i * dx, 7 + i * dy, game.snapshot().revision)
                if (i < 4) game.move(2, 14, i, game.snapshot().revision)
            }
            assertEquals(1, game.snapshot().winner)
            assertEquals("finished", game.snapshot().status)
            assertFailsWith<GameError> { game.move(2, 14, 4, game.snapshot().revision) }
        }
    }

    @Test fun `six stones also win in freestyle rules`() {
        val game = Game().apply { start() }
        listOf(0, 1, 2, 4, 5, 3).forEachIndexed { i, x ->
            game.move(1, x, 0, game.snapshot().revision)
            if (i < 5) game.move(2, i * 2, 5, game.snapshot().revision)
        }
        assertEquals(1, game.snapshot().winner)
    }

    @Test fun `invalid moves do not mutate state`() {
        val game = Game()
        assertFailsWith<GameError> { game.move(1, 0, 0, 0) }
        game.start()
        val before = game.snapshot()
        for ((seat, x, y, rev) in listOf(listOf(2,0,0,1), listOf(1,-1,0,1), listOf(1,15,0,1), listOf(1,0,0,0))) {
            assertFailsWith<GameError> { game.move(seat, x, y, rev) }
            assertEquals(before, game.snapshot())
        }
        game.move(1, 0, 0, 1)
        assertFailsWith<GameError> { game.move(2, 0, 0, 2) }
        assertEquals(2, game.snapshot().revision)
    }

    @Test fun `fractional coordinates and unknown JSON fields are rejected`() {
        assertFailsWith<SerializationException> { wireJson.decodeFromString<ClientMessage>("""{"type":"move","x":1.5}""") }
        assertFailsWith<SerializationException> { wireJson.decodeFromString<ClientMessage>("""{"type":"move","admin":true}""") }
    }

    @Test fun `resign declares opponent winner`() {
        val game = Game().apply { start(); resign(1, 1) }
        assertEquals(2, game.snapshot().winner)
        assertEquals("finished", game.snapshot().status)
    }

    @Test fun `full board without five is a draw`() {
        val black = mutableListOf<Pair<Int, Int>>()
        val white = mutableListOf<Pair<Int, Int>>()
        for (y in 0..14) for (x in 0..14) {
            (if ((x + 2 * y) % 4 < 2) black else white).add(x to y)
        }
        val game = Game().apply { start() }
        black.forEachIndexed { i, (x, y) ->
            game.move(1, x, y, game.snapshot().revision)
            if (i < white.size) game.move(2, white[i].first, white[i].second, game.snapshot().revision)
        }
        assertEquals("finished", game.snapshot().status)
        assertEquals(0, game.snapshot().winner)
        assertTrue(game.snapshot().board.all { it != 0 })
    }

    @Test fun `hit testing handles edges scaling and empty space`() {
        for (cell in listOf(16, 24, 40)) {
            val g = BoardGeometry(cell)
            assertEquals(0 to 0, g.intersection(g.left, g.top))
            assertEquals(14 to 14, g.intersection(g.left + 14 * cell, g.top + 14 * cell))
            assertNull(g.intersection(g.left - cell, g.top))
            assertNull(g.intersection(g.left, g.top - cell))
            assertNull(g.intersection(g.left + cell / 2, g.top + cell / 2))
        }
    }

    @Test fun `array cells stay clickable with rectangular text metrics`() {
        for (scale in listOf(1, 2)) {
            val g = BoardGeometry(12 * scale, 27 * scale, 27 * scale, 14 * scale, 27 * scale)
            for (y in 0 until BOARD_SIZE) for (x in 0 until BOARD_SIZE) {
                val px = g.left + x * g.cell
                val py = g.top + y * g.rowHeight
                assertTrue(px in 0 until g.width && py in 0 until g.height)
                assertEquals(x to y, g.intersection(px, py))
                assertEquals(x to y, g.intersection(px + 2 * scale, py + 2 * scale))
            }
            assertNull(g.intersection(g.left, g.top - g.rowHeight))
            assertNull(g.intersection(g.left, g.top + BOARD_SIZE * g.rowHeight))
            assertNull(g.intersection(g.left + g.cell / 2, g.top))
        }
    }
}
