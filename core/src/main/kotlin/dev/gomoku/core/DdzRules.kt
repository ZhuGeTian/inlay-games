package dev.gomoku.core

data class DdzCombination(val kind: String, val size: Int, val strength: Int)

object DdzRules {
    fun label(card: Int): String = when (val rank = rank(card)) {
        11 -> "J"
        12 -> "Q"
        13 -> "K"
        14 -> "A"
        15 -> "2"
        16 -> "Xiao"
        17 -> "Da"
        else -> rank.toString()
    }

    fun rank(card: Int): Int {
        require(card in 0..53) { "牌 ID 无效" }
        return if (card < 52) card / 4 + 3 else card - 36
    }

    fun classify(cards: List<Int>): DdzCombination? {
        if (cards.isEmpty() || cards.size > 20 || cards.any { it !in 0..53 } || cards.toSet().size != cards.size) return null
        val counts = cards.groupingBy(::rank).eachCount().toSortedMap()
        val size = cards.size
        fun result(kind: String, strength: Int) = DdzCombination(kind, size, strength)
        fun consecutive(ranks: List<Int>) = ranks.last() <= 14 && ranks.zipWithNext().all { (a, b) -> b == a + 1 }
        if (size == 2 && counts.keys.toList() == listOf(16, 17)) return result("rocket", 17)
        if (counts.size == 1) return when (size) {
            1 -> result("single", counts.firstKey())
            2 -> result("pair", counts.firstKey())
            3 -> result("triple", counts.firstKey())
            4 -> result("bomb", counts.firstKey())
            else -> null
        }
        val triple = counts.entries.singleOrNull { it.value == 3 }?.key
        if (triple != null && size == 4) return result("tripleSingle", triple)
        if (triple != null && size == 5 && counts.values.sorted() == listOf(2, 3)) return result("triplePair", triple)
        if (size >= 5 && counts.values.all { it == 1 } && consecutive(counts.keys.toList())) return result("straight", counts.lastKey())
        if (size >= 6 && size % 2 == 0 && counts.values.all { it == 2 } && consecutive(counts.keys.toList())) return result("pairStraight", counts.lastKey())

        val triples = counts.filter { (rank, count) -> rank <= 14 && count == 3 }.keys.toList()
        for ((kind, groupSize) in listOf("airplane" to 3, "airplaneSingle" to 4, "airplanePair" to 5)) {
            if (size % groupSize != 0) continue
            val bodies = size / groupSize
            if (bodies < 2 || triples.size < bodies) continue
            for (body in triples.windowed(bodies)) {
                if (!consecutive(body)) continue
                val wings = counts.filterKeys { it !in body }
                val valid = when (kind) {
                    "airplane" -> wings.isEmpty()
                    "airplaneSingle" -> wings.values.sum() == bodies && wings.values.all { it <= 2 } && !(16 in wings && 17 in wings)
                    else -> wings.size == bodies && wings.values.all { it == 2 }
                }
                if (valid) return result(kind, body.last())
            }
        }
        val four = counts.entries.singleOrNull { it.value == 4 }?.key
        if (four != null) {
            val wings = counts.filterKeys { it != four }
            if (size == 6 && !(16 in wings && 17 in wings)) return result("fourSingles", four)
            if (size == 8 && wings.size == 2 && wings.values.all { it == 2 }) return result("fourPairs", four)
        }
        return null
    }

    fun beats(candidate: DdzCombination, previous: DdzCombination): Boolean = when {
        previous.kind == "rocket" -> false
        candidate.kind == "rocket" -> true
        candidate.kind == "bomb" && previous.kind != "bomb" -> true
        else -> candidate.kind == previous.kind && candidate.size == previous.size && candidate.strength > previous.strength
    }
}
