package dev.gomoku.core

object TexasRules {
    fun rank(card: Int): Int {
        require(card in 0..51) { "牌面无效" }
        return card / 4 + 2
    }

    fun suit(card: Int): Int {
        require(card in 0..51) { "牌面无效" }
        return card % 4
    }

    // Base 15 retains category and every kicker in a directly comparable value.
    fun strength(cards: List<Int>): Long {
        require(cards.size in 5..7 && cards.toSet().size == cards.size && cards.all { it in 0..51 })
        var best = -1L
        for (a in 0 until cards.size - 4) for (b in a + 1 until cards.size - 3)
            for (c in b + 1 until cards.size - 2) for (d in c + 1 until cards.size - 1)
                for (e in d + 1 until cards.size) {
                    best = maxOf(best, five(listOf(cards[a], cards[b], cards[c], cards[d], cards[e])))
                }
        return best
    }

    private fun five(cards: List<Int>): Long {
        val ranks = cards.map(::rank).sortedDescending()
        val groups = ranks.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenByDescending { it.key })
        val flush = cards.map(::suit).distinct().size == 1
        val straight = when {
            ranks.distinct().size != 5 -> 0
            ranks.first() - ranks.last() == 4 -> ranks.first()
            ranks == listOf(14, 5, 4, 3, 2) -> 5
            else -> 0
        }
        val (category, kickers) = when {
            flush && straight > 0 -> 8 to listOf(straight)
            groups[0].value == 4 -> 7 to groups.map { it.key }
            groups[0].value == 3 && groups[1].value == 2 -> 6 to groups.map { it.key }
            flush -> 5 to ranks
            straight > 0 -> 4 to listOf(straight)
            groups[0].value == 3 -> 3 to groups.map { it.key }
            groups[0].value == 2 && groups[1].value == 2 -> 2 to groups.map { it.key }
            groups[0].value == 2 -> 1 to groups.map { it.key }
            else -> 0 to ranks
        }
        var value = category.toLong()
        repeat(5) { value = value * 15 + kickers.getOrElse(it) { 0 } }
        return value
    }
}
