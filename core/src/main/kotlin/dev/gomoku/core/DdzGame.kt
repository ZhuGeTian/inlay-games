package dev.gomoku.core

class DdzGame(private val shuffledDeck: () -> List<Int> = { (0..53).shuffled() }) {
    private var state = DdzState()
    private var hands = List(3) { emptyList<Int>() }
    private var bottom = emptyList<Int>()
    private var startingSeat = 1
    private val playCounts = IntArray(3)

    fun snapshot(seat: Int): DdzState {
        checkSeat(seat)
        return state.copy(hand = hands[seat - 1].toList(), counts = hands.map { it.size },
            bottom = if (state.landlord == 0) emptyList() else bottom.toList())
    }

    fun start() {
        if (state.status != "waiting") throw GameError("对局已经开始")
        deal(validDeck(), startingSeat)
    }

    fun bid(seat: Int, value: Int, expectedRevision: Int) {
        checkAction(seat, expectedRevision, "bidding", true)
        if (value !in 0..3 || value != 0 && value <= state.bid) throw GameError("叫分必须高于当前分数，或选择不叫")
        val bids = state.bids.toMutableList().apply { this[seat - 1] = value }
        if (value == 0 && bids.all { it == 0 }) {
            deal(validDeck(), next(startingSeat))
            return
        }
        val high = maxOf(state.bid, value)
        val landlord = if (value == 3 || bids.none { it == -1 }) bids.indexOf(high) + 1 else 0
        if (landlord != 0) {
            hands = hands.mapIndexed { index, hand -> if (index == landlord - 1) (hand + bottom).sorted() else hand }
        }
        state = state.copy(status = if (landlord == 0) "bidding" else "playing", bids = bids, bid = high,
            landlord = landlord, turn = if (landlord == 0) next(seat) else landlord,
            revision = state.revision + 1, lastAction = "$seat:bid($value)")
    }

    fun play(seat: Int, cards: List<Int>, expectedRevision: Int) {
        checkAction(seat, expectedRevision, "playing", true)
        val combination = DdzRules.classify(cards) ?: throw GameError("不是有效牌型")
        if (!hands[seat - 1].containsAll(cards)) throw GameError("只能打出自己的手牌")
        if (state.trick.isNotEmpty() && !DdzRules.beats(combination, DdzRules.classify(state.trick)!!)) throw GameError("出牌必须大于当前牌型")
        hands = hands.mapIndexed { index, hand -> if (index == seat - 1) hand.filterNot { it in cards } else hand }
        playCounts[seat - 1]++
        state = state.copy(trick = cards.sorted(), trickSeat = seat, passes = 0, turn = next(seat),
            multiplier = state.multiplier * if (combination.kind in listOf("bomb", "rocket")) 2 else 1,
            revision = state.revision + 1, lastAction = "$seat:play(${cards.joinToString(",", transform = DdzRules::label)})")
        if (hands[seat - 1].isEmpty()) finish(seat, spring = true)
    }

    fun pass(seat: Int, expectedRevision: Int) {
        checkAction(seat, expectedRevision, "playing", true)
        if (state.trick.isEmpty()) throw GameError("领出时不能不出")
        val cleared = state.passes == 1
        state = state.copy(turn = if (cleared) state.trickSeat else next(seat),
            trick = if (cleared) emptyList() else state.trick,
            trickSeat = if (cleared) 0 else state.trickSeat, passes = if (cleared) 0 else 1,
            revision = state.revision + 1, lastAction = "$seat:pass()")
    }

    fun ready(seat: Int, expectedRevision: Int) {
        checkAction(seat, expectedRevision, "finished")
        if (state.ready[seat - 1]) throw GameError("你已经准备")
        val ready = state.ready.toMutableList().apply { this[seat - 1] = true }
        if (ready.all { it }) deal(validDeck(), next(startingSeat))
        else state = state.copy(ready = ready, revision = state.revision + 1, lastAction = "$seat:ready()")
    }

    fun resign(seat: Int, expectedRevision: Int) {
        checkAction(seat, expectedRevision, "playing")
        state = state.copy(revision = state.revision + 1, lastAction = "$seat:resign()")
        finish(if (seat == state.landlord) next(seat) else state.landlord, spring = false)
    }

    private fun finish(winner: Int, spring: Boolean) {
        val landlordWon = winner == state.landlord
        val isSpring = if (landlordWon) playCounts.withIndex().filter { it.index != state.landlord - 1 }.all { it.value == 0 }
            else playCounts[state.landlord - 1] == 1
        val multiplier = state.multiplier * if (spring && isSpring) 2 else 1
        val unit = state.bid * multiplier
        val delta = (1..3).map { seat ->
            (if (seat == state.landlord) 2L else -1L) * unit * if (landlordWon) 1 else -1
        }
        state = state.copy(status = "finished", winner = winner, turn = 0, multiplier = multiplier,
            roundDelta = delta, scores = state.scores.zip(delta) { score, change -> score + change })
    }

    private fun deal(deck: List<Int>, starter: Int) {
        hands = (0..2).map { deck.subList(it * 17, (it + 1) * 17).sorted() }
        bottom = deck.takeLast(3)
        startingSeat = starter
        playCounts.fill(0)
        state = DdzState(status = "bidding", turn = starter, revision = state.revision + 1,
            scores = state.scores, round = state.round + 1, lastAction = "deal()")
    }

    private fun validDeck(): List<Int> = shuffledDeck().toList().also {
        if (it.size != 54 || it.toSet() != (0..53).toSet()) throw GameError("牌堆无效")
    }

    private fun checkAction(seat: Int, revision: Int, status: String, needsTurn: Boolean = false) {
        checkSeat(seat)
        if (revision != state.revision) throw GameError("牌局已更新，请按最新状态操作")
        if (state.status != status) throw GameError("当前不能进行此操作")
        if (needsTurn && state.turn != seat) throw GameError("还没有轮到你")
    }

    private fun checkSeat(seat: Int) {
        if (seat !in 1..3) throw GameError("座位无效")
    }

    private fun next(seat: Int) = seat % 3 + 1
}
