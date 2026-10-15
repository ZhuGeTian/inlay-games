package dev.gomoku.core

object DdzBot {
    fun decide(state: DdzState, seat: Int): DdzClientMessage {
        require(seat in 1..3 && state.counts.size == 3 && state.ready.size == 3) { "座位无效" }
        fun action(type: String, bid: Int = 0, cards: List<Int> = emptyList()) =
            DdzClientMessage(type, revision = state.revision, bid = bid, cards = cards)
        if (state.status == "finished") {
            require(!state.ready[seat - 1]) { "已经准备" }
            return action("ready")
        }
        require(state.status in listOf("bidding", "playing") && state.turn == seat) { "当前不能操作" }
        require(state.hand.isNotEmpty() && state.hand.size <= 20 && state.hand.distinct().size == state.hand.size) { "手牌无效" }
        val ranks = state.hand.groupingBy(DdzRules::rank).eachCount()
        if (state.status == "bidding") {
            require(state.bids.size == 3 && state.bid in 0..3) { "叫分状态无效" }
            val power = ranks.count { it.value == 4 } * 3 + (ranks[17] ?: 0) * 2 +
                (ranks[16] ?: 0) + (ranks[15] ?: 0)
            val desired = when { power >= 7 -> 3; power >= 4 -> 2; power >= 2 -> 1; else -> 0 }
            // The final bidder takes one point when nobody called, so all-bot tables cannot redeal forever.
            val bid = if (desired > state.bid) desired else if (state.bid == 0 && state.bids.count { it == -1 } == 1) 1 else 0
            return action("bid", bid = bid)
        }
        require(state.landlord in 1..3) { "地主无效" }
        val previous = if (state.trick.isEmpty()) null else requireNotNull(DdzRules.classify(state.trick))
        val choices = candidates(state.hand).map { it to DdzRules.classify(it)!! }
            .filter { previous == null || DdzRules.beats(it.second, previous) }
        val finish = choices.firstOrNull { it.first.size == state.hand.size }
        if (finish != null) return action("play", cards = finish.first)
        val teammateLeads = previous != null && seat != state.landlord && state.trickSeat != state.landlord
        if (teammateLeads && state.counts[state.landlord - 1] > 2) return action("pass")
        // shortcut: a public-information heuristic, upgrade only if stronger play is requested.
        val chosen = choices.minWithOrNull(compareBy<Pair<List<Int>, DdzCombination>> {
            when (it.second.kind) { "rocket" -> 2; "bomb" -> 1; else -> 0 }
        }.thenByDescending { it.first.size }.thenBy { it.second.strength })
        if (chosen != null) return action("play", cards = chosen.first)
        check(previous != null) { "领出必须有合法牌" }
        return action("pass")
    }

    internal fun candidates(hand: List<Int>): List<List<Int>> {
        require(hand.size in 1..20 && hand.distinct().size == hand.size) { "手牌无效" }
        val groups = hand.sorted().groupBy(DdzRules::rank).toSortedMap()
        val result = linkedSetOf<List<Int>>()
        fun add(cards: List<Int>) {
            if (DdzRules.classify(cards) != null) result.add(cards.sorted())
        }
        fun wings(bodyRanks: List<Int>, body: List<Int>, count: Int, pairs: Boolean) {
            val available = groups.filterKeys { it !in bodyRanks }.values.toList()
            val chosen = mutableListOf<Int>()
            fun visit(index: Int, remaining: Int) {
                if (remaining == 0) { add(body + chosen); return }
                if (index == available.size) return
                val group = available[index]
                val max = minOf(if (pairs) 1 else 2, if (pairs) group.size / 2 else group.size, remaining)
                for (units in 0..max) {
                    val take = units * if (pairs) 2 else 1
                    chosen.addAll(group.take(take))
                    visit(index + 1, remaining - units)
                    repeat(take) { chosen.removeAt(chosen.lastIndex) }
                }
            }
            visit(0, count)
        }
        for ((rank, group) in groups) {
            for (size in 1..group.size) add(group.take(size))
            if (group.size >= 3) {
                wings(listOf(rank), group.take(3), 1, false)
                wings(listOf(rank), group.take(3), 1, true)
            }
            if (group.size == 4) {
                wings(listOf(rank), group, 2, false)
                wings(listOf(rank), group, 2, true)
            }
        }
        if (16 in groups && 17 in groups) add(groups.getValue(16) + groups.getValue(17))
        for (copies in 1..3) {
            val minimum = when (copies) { 1 -> 5; 2 -> 3; else -> 2 }
            for (start in 3..14) {
                val body = mutableListOf<Int>()
                for (end in start..14) {
                    val group = groups[end] ?: break
                    if (group.size < copies) break
                    body.addAll(group.take(copies))
                    val length = end - start + 1
                    if (body.size > 20) break
                    if (length < minimum) continue
                    add(body.toList())
                    if (copies == 3) {
                        if (length * 4 <= hand.size) wings((start..end).toList(), body, length, false)
                        if (length * 5 <= hand.size) wings((start..end).toList(), body, length, true)
                    }
                }
            }
        }
        return result.toList()
    }
}
