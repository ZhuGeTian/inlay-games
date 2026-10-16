package dev.gomoku.plugin

import dev.gomoku.core.DdzState
import java.net.URI

enum class GameKind(val label: String) { GOMOKU("五子棋"), DDZ("斗地主"), TEXAS("德州") }

internal fun gameEndpoint(server: String, ddz: Boolean) = gameEndpoint(server, if (ddz) GameKind.DDZ else GameKind.GOMOKU)

internal fun gameEndpoint(server: String, kind: GameKind): String {
    val uri = URI(server)
    val base = uri.path.orEmpty().replace(Regex("/(ddz|texas)(/v2)?/ws$"), "/ws")
    val target = if (kind == GameKind.GOMOKU) base else {
        val prefix = if (base.endsWith("/ws")) base.removeSuffix("/ws") else base.trimEnd('/')
        prefix + (if (kind == GameKind.DDZ) "/ddz/v2/ws" else "/texas/v2/ws")
    }
    return URI(uri.scheme, uri.authority, target, null, null).toString() +
        (uri.rawQuery?.let { "?$it" } ?: "")
}

internal fun validDdzState(s: DdzState): Boolean {
    fun cards(values: List<Int>, max: Int) = values.size <= max && values.distinct().size == values.size && values.all { it in 0..53 }
    return s.status in setOf("waiting", "bidding", "playing", "finished") &&
        cards(s.hand, 20) && cards(s.bottom, 3) && cards(s.trick, 20) &&
        s.bottom.size in setOf(0, 3) && s.counts.size == 3 && s.counts.all { it in 0..20 } &&
        s.landlord in 0..3 && s.turn in 0..3 && s.trickSeat in 0..3 && s.winner in 0..3 &&
        s.bid in 0..3 && s.bids.size == 3 && s.bids.all { it in -1..3 } && s.passes in 0..1 &&
        s.revision >= 0 && s.round >= 0 && s.multiplier >= 1 &&
        s.roundDelta.size == 3 && s.scores.size == 3 && s.ready.size == 3 && s.online.size == 3 && s.bots.size == 3
}
