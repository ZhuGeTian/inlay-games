package dev.gomoku.core

import kotlin.test.*

class TexasRulesTest {
    private fun cards(vararg ranks: Int) = ranks.mapIndexed { i, r -> (r - 2) * 4 + i % 4 }

    @Test fun `categories and all kickers compare in poker order`() {
        val ordered = listOf(
            cards(14, 11, 9, 7, 3), cards(2, 2, 14, 8, 3), cards(3, 3, 2, 2, 14),
            cards(2, 2, 2, 14, 3), cards(6, 5, 4, 3, 2),
            listOf(0, 8, 20, 32, 48), cards(2, 2, 2, 3, 3),
            cards(2, 2, 2, 2, 14), listOf(0, 4, 8, 12, 16),
        ).map(TexasRules::strength)
        assertTrue(ordered.zipWithNext().all { (a, b) -> a < b })
        assertTrue(TexasRules.strength(cards(14, 14, 13, 8, 7)) > TexasRules.strength(cards(14, 14, 13, 8, 6)))
        assertTrue(TexasRules.strength(cards(10, 10, 10, 9, 9)) > TexasRules.strength(cards(9, 9, 9, 14, 14)))
    }

    @Test fun `wheel is five high and seven cards choose the best five`() {
        assertTrue(TexasRules.strength(cards(6, 5, 4, 3, 2)) > TexasRules.strength(cards(14, 5, 4, 3, 2)))
        val royal = listOf(32, 36, 40, 44, 48)
        assertEquals(TexasRules.strength(royal), TexasRules.strength(royal + listOf(1, 6)))
        assertEquals(TexasRules.strength(royal), TexasRules.strength(royal.map { it + 1 }))
        assertEquals(TexasRules.strength(cards(14, 14, 14, 13, 13)),
            TexasRules.strength(listOf(48, 49, 50, 44, 45, 46, 0)))
    }

    @Test fun `invalid cards fail and rank suit mapping covers all cards`() {
        assertFailsWith<IllegalArgumentException> { TexasRules.strength(listOf(0, 0, 1, 2, 3)) }
        assertFailsWith<IllegalArgumentException> { TexasRules.strength(listOf(0, 1, 2, 3, 52)) }
        assertFailsWith<IllegalArgumentException> { TexasRules.strength(listOf(0, 1)) }
        for (card in 0..51) {
            assertEquals(card / 4 + 2, TexasRules.rank(card))
            assertEquals(card % 4, TexasRules.suit(card))
        }
    }
}
