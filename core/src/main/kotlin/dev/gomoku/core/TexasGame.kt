package dev.gomoku.core

class TexasGame(
    private val shuffledDeck: () -> List<Int> = { (0..51).shuffled() },
    initialStacks: List<Long> = listOf(1000, 1000, 1000),
) {
    init {
        require(initialStacks.size == 3 && initialStacks.all { it in 0..1_000_000 } && initialStacks.count { it > 0 } >= 2)
    }
    private var state = TexasState(stacks = initialStacks.toList())
    private var hands = List(3) { emptyList<Int>() }
    private var deck = emptyList<Int>()
    private var deckIndex = 0
    private var openingStacks = initialStacks.toList()
    private val pending = mutableSetOf<Int>()
    private val actedAt = LongArray(3) { -1 }
    private var lastRaise = 10L

    fun snapshot(seat: Int): TexasState {
        checkSeat(seat)
        val index = seat - 1
        val legal = legalActions(seat)
        return state.copy(hand = hands[index].toList(), legalActions = legal,
            minRaiseTo = minimumRaise(), maxRaiseTo = state.bets[index] + state.stacks[index],
            toCall = if (state.status == "playing" && state.inHand[index] && !state.folded[index])
                minOf(state.stacks[index], maxOf(0, state.currentBet - state.bets[index])) else 0)
    }

    fun start() {
        if (state.status != "waiting") throw GameError("对局已经开始")
        deal(validDeck(), state.stacks)
    }

    fun act(seat: Int, type: String, raiseTo: Long, revision: Int) {
        checkRevision(seat, revision)
        if (type !in legalActions(seat) || type == "ready") throw GameError("当前不能进行此操作")
        val i = seat - 1
        val target = when (type) {
            "raise" -> raiseTo.also {
                if (it < minimumRaise() || it > state.bets[i] + state.stacks[i]) throw GameError("加注总额超出范围")
            }
            "allin" -> state.bets[i] + state.stacks[i]
            "call" -> minOf(state.bets[i] + state.stacks[i], state.currentBet)
            else -> state.bets[i]
        }
        if (type == "fold") state = state.copy(folded = state.folded.replace(i, true))
        else commit(i, target - state.bets[i])
        pending.remove(seat)
        if (target > state.currentBet) {
            val increment = target - state.currentBet
            if (increment >= lastRaise) lastRaise = increment
            state = state.copy(currentBet = target)
            pending.clear()
            pending.addAll(active().filter { it != seat })
        }
        actedAt[i] = state.currentBet
        state = state.copy(revision = state.revision + 1, lastAction = "$seat:$type($target)")
        advance(seat)
    }

    fun ready(seat: Int, revision: Int) {
        checkRevision(seat, revision)
        if (state.status != "finished" || state.ready[seat - 1]) throw GameError("当前不能准备")
        val ready = state.ready.replace(seat - 1, true)
        if (ready.all { it }) {
            val nextDeck = validDeck()
            deal(nextDeck, if (state.matchOver) listOf(1000, 1000, 1000) else state.stacks)
        } else state = state.copy(ready = ready, revision = state.revision + 1, lastAction = "$seat:ready()")
    }

    private fun legalActions(seat: Int): List<String> {
        val i = seat - 1
        if (state.status == "finished") return if (state.ready[i]) emptyList() else listOf("ready")
        if (state.status != "playing" || state.turn != seat) return emptyList()
        val owed = maxOf(0, state.currentBet - state.bets[i])
        val maximum = state.bets[i] + state.stacks[i]
        val canRaise = active().any { it != seat } &&
            (actedAt[i] < 0 || state.currentBet - actedAt[i] >= lastRaise)
        return buildList {
            add("fold")
            add(if (owed == 0L) "check" else "call")
            if (canRaise && maximum >= minimumRaise()) add("raise")
            if (state.stacks[i] > 0 && (maximum <= state.currentBet || canRaise)) add("allin")
        }
    }

    private fun deal(cards: List<Int>, stacks: List<Long>) {
        val funded = (1..3).filter { stacks[it - 1] > 0 }
        val old = state
        val dealer: Int
        val small: Int
        val big: Int
        if (funded.size == 2) {
            big = if (old.bigBlindSeat == 0) funded.last() else after(old.bigBlindSeat, funded)
            dealer = funded.single { it != big }
            small = dealer
        } else {
            dealer = if (old.dealer == 0) 1 else after(old.dealer, funded)
            small = after(dealer, funded)
            big = after(small, funded)
        }
        state = TexasState(status = "playing", street = "preflop", stacks = stacks.toList(),
            inHand = (1..3).map { it in funded }, dealer = dealer, smallBlindSeat = small, bigBlindSeat = big,
            currentBet = 10, round = old.round + 1, revision = old.revision + 1, lastAction = "deal()")
        openingStacks = stacks.toList()
        deck = cards
        deckIndex = 0
        val dealt = List(3) { mutableListOf<Int>() }
        var at = dealer
        repeat(2) { repeat(funded.size) { at = after(at, funded); dealt[at - 1].add(draw()) } }
        hands = dealt.map { it.toList() }
        commit(small - 1, minOf(5, state.stacks[small - 1]))
        commit(big - 1, minOf(10, state.stacks[big - 1]))
        lastRaise = 10
        actedAt.fill(-1)
        pending.clear()
        pending.addAll(active())
        advance(big)
    }

    private fun advance(lastSeat: Int) {
        if (live().size == 1) { finish(false); return }
        val active = active()
        pending.retainAll(active.toSet())
        if (active.size <= 1) {
            // A short all-in blind cannot require an uncontested nominal blind payment.
            state = state.copy(currentBet = minOf(state.currentBet, state.bets.maxOrNull() ?: 0))
            val sole = active.singleOrNull()
            if (sole != null && state.bets[sole - 1] < state.currentBet) {
                state = state.copy(turn = sole)
                return
            }
            while (state.board.size < 5) nextStreet()
            finish(true)
            return
        }
        if (pending.isNotEmpty()) {
            state = state.copy(turn = after(lastSeat, pending.toList()))
            return
        }
        if (state.street == "river") { finish(true); return }
        nextStreet()
        pending.addAll(active())
        state = state.copy(turn = after(state.dealer, pending.toList()))
    }

    private fun nextStreet() {
        draw() // Burn a card before each board street.
        val number = if (state.board.isEmpty()) 3 else 1
        val board = state.board + List(number) { draw() }
        state = state.copy(board = board, street = when (board.size) { 3 -> "flop"; 4 -> "turn"; else -> "river" },
            bets = listOf(0, 0, 0), currentBet = 0)
        lastRaise = 10
        actedAt.fill(-1)
    }

    private fun finish(showdown: Boolean) {
        val payouts = LongArray(3)
        val returned = LongArray(3)
        val scores = if (showdown) live().associateWith { TexasRules.strength(hands[it - 1] + state.board) } else emptyMap()
        var previous = 0L
        for (level in state.contributions.filter { it > 0 }.distinct().sorted()) {
            val contributors = (1..3).filter { state.contributions[it - 1] >= level }
            val amount = (level - previous) * contributors.size
            if (contributors.size == 1) returned[contributors.single() - 1] += amount
            else {
                val eligible = contributors.filter { it in live() }
                check(eligible.isNotEmpty())
                val best = if (showdown) eligible.maxOf { scores.getValue(it) } else 0
                val winners = eligible.filter { !showdown || scores.getValue(it) == best }
                for (winner in winners) payouts[winner - 1] += amount / winners.size
                var remainder = amount % winners.size
                var at = state.dealer
                while (remainder > 0) {
                    at = after(at, winners)
                    payouts[at - 1]++
                    remainder--
                }
            }
            previous = level
        }
        val stacks = (0..2).map { state.stacks[it] + payouts[it] + returned[it] }
        check(stacks.sum() == openingStacks.sum())
        state = state.copy(status = "finished", turn = 0, stacks = stacks, pot = 0,
            payouts = payouts.toList(), returned = returned.toList(),
            roundDelta = (0..2).map { stacks[it] - openingStacks[it] },
            revealed = (1..3).map { if (showdown && it in live()) hands[it - 1].toList() else emptyList() },
            winners = (1..3).filter { payouts[it - 1] > 0 }, matchOver = stacks.count { it > 0 } == 1)
        pending.clear()
    }

    private fun commit(index: Int, amount: Long) {
        val remaining = state.stacks[index] - amount
        state = state.copy(stacks = state.stacks.replace(index, remaining), bets = state.bets.replace(index, state.bets[index] + amount),
            contributions = state.contributions.replace(index, state.contributions[index] + amount),
            allIn = state.allIn.replace(index, remaining == 0L), pot = state.pot + amount)
    }

    private fun minimumRaise() = state.currentBet + lastRaise
    private fun live() = (1..3).filter { state.inHand[it - 1] && !state.folded[it - 1] }
    private fun active() = live().filter { state.stacks[it - 1] > 0 }
    private fun draw() = deck[deckIndex++]
    private fun after(seat: Int, eligible: List<Int>) = (1..3).map { (seat + it - 1) % 3 + 1 }.first { it in eligible }
    private fun checkSeat(seat: Int) { if (seat !in 1..3) throw GameError("座位无效") }
    private fun checkRevision(seat: Int, revision: Int) {
        checkSeat(seat)
        if (revision != state.revision) throw GameError("牌局已更新，请按最新状态操作")
    }
    private fun validDeck() = shuffledDeck().toList().also {
        if (it.size != 52 || it.toSet() != (0..51).toSet()) throw GameError("牌堆无效")
    }
    private fun <T> List<T>.replace(index: Int, value: T) = toMutableList().also { it[index] = value }.toList()
}
