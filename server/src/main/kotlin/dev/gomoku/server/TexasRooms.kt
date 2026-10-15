package dev.gomoku.server

import dev.gomoku.core.*
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections

class TexasPeer(val id: String, val ip: String, val emit: (TexasServerMessage) -> Unit, val close: () -> Unit = {}) {
    internal var room: String? = null
    internal var seat = 0
}

class TexasRooms(
    private val now: () -> Long = System::currentTimeMillis,
    private val shuffledDeck: () -> List<Int> = {
        (0..51).toMutableList().also { Collections.shuffle(it, SecureRandom()) }
    },
) {
    private data class Player(val token: String, var peer: TexasPeer?)
    private class Room(val code: String, val creatorIp: String, var touched: Long, deck: () -> List<Int>) {
        val players = mutableListOf<Player>()
        val game = TexasGame(deck)
        val requests = linkedMapOf<String, TexasClientMessage>()
        fun state(seat: Int) = game.snapshot(seat).copy(online = List(3) { players.getOrNull(it)?.peer != null })
    }
    private val random = SecureRandom()
    private val rooms = mutableMapOf<String, Room>()
    private val ttl = 30 * 60 * 1000L

    @Synchronized fun handle(peer: TexasPeer, command: TexasClientMessage) {
        try {
            if (command.protocol != TEXAS_PROTOCOL_VERSION) throw GameError("协议版本不兼容，请更新插件")
            if (command.requestId.length !in 1..64) throw GameError("请求编号无效")
            peer.room?.let {
                val room = boundRoom(peer)
                room.requests["${peer.seat}:${command.requestId}"]?.let { previous ->
                    if (previous != command) throw GameError("重复的请求编号")
                    if (command.type in setOf("create", "join", "resume")) {
                        welcome(peer, room, room.players[peer.seat - 1], command.requestId)
                    } else emitState(peer, room, command.requestId)
                    return
                }
            }
            when (command.type) {
                "create" -> {
                    requireFree(peer)
                    sweep()
                    if (rooms.size >= 200 || rooms.values.count { it.creatorIp == peer.ip } >= 5) throw GameError("房间数量已达上限，请稍后重试")
                    var code: String
                    do { code = (1..8).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[random.nextInt(32)] }.joinToString("") } while (rooms.containsKey(code))
                    val room = Room(code, peer.ip, now(), shuffledDeck)
                    val player = Player(newToken(), peer)
                    room.players.add(player)
                    rooms[code] = room
                    peer.room = code
                    peer.seat = 1
                    remember(room, peer.seat, command)
                    welcome(peer, room, player, command.requestId)
                }
                "join" -> {
                    requireFree(peer)
                    val room = find(command.roomCode)
                    if (room.players.size == 3) throw GameError("房间已满")
                    if (room.players.any { it.peer == null }) throw GameError("有玩家暂时离线，请稍后加入")
                    val player = Player(newToken(), peer)
                    room.players.add(player)
                    if (room.players.size == 3) room.game.start()
                    room.touched = now()
                    peer.room = room.code
                    peer.seat = room.players.size
                    remember(room, peer.seat, command)
                    welcome(peer, room, player, command.requestId)
                    broadcast(room, command.requestId)
                }
                "resume" -> {
                    requireFree(peer)
                    val room = find(command.roomCode)
                    val index = room.players.indexOfFirst { it.token == command.token }
                    if (index < 0) throw GameError("无法恢复身份，请重新创建或加入房间")
                    val previous = room.requests["${index + 1}:${command.requestId}"]
                    if (previous != null && previous != command) throw GameError("重复的请求编号")
                    val player = room.players[index]
                    player.peer?.let { old -> old.room = null; old.seat = 0; old.close() }
                    player.peer = peer
                    peer.room = room.code
                    peer.seat = index + 1
                    room.touched = now()
                    remember(room, peer.seat, command)
                    welcome(peer, room, player, command.requestId)
                    broadcast(room, command.requestId)
                }
                "fold", "check", "call", "raise", "allin", "ready" -> {
                    val room = boundRoom(peer)
                    if (room.players.size != 3 || room.players.any { it.peer == null }) throw GameError("等待三位玩家全部在线")
                    if (command.type == "ready") room.game.ready(peer.seat, command.revision)
                    else room.game.act(peer.seat, command.type, command.raiseTo, command.revision)
                    remember(room, peer.seat, command)
                    room.touched = now()
                    broadcast(room, command.requestId)
                }
                "leave" -> {
                    val room = boundRoom(peer)
                    rooms.remove(room.code)
                    for (player in room.players) {
                        player.peer?.let { member ->
                            member.room = null
                            member.seat = 0
                            member.emit(TexasServerMessage(if (member === peer) "left" else "closed",
                                roomCode = room.code, requestId = command.requestId,
                                error = if (member === peer) "" else "有玩家退出，房间已关闭"))
                        }
                        player.peer = null
                    }
                }
                else -> throw GameError("未知操作")
            }
        } catch (e: GameError) {
            peer.emit(TexasServerMessage("error", error = e.message ?: "操作失败", requestId = command.requestId,
                roomCode = peer.room.orEmpty(), state = peer.room?.let { rooms[it]?.state(peer.seat) }))
        }
    }

    @Synchronized fun disconnect(peer: TexasPeer) {
        val room = peer.room?.let { rooms[it] } ?: return
        val player = room.players.getOrNull(peer.seat - 1)
        peer.room = null
        peer.seat = 0
        if (player?.peer !== peer) return
        player.peer = null
        room.touched = now()
        broadcast(room, "")
    }

    @Synchronized fun sweep() {
        for (room in rooms.values.filter { now() - it.touched >= ttl }) {
            rooms.remove(room.code)
            for (player in room.players) {
                player.peer?.let { peer ->
                    peer.room = null
                    peer.seat = 0
                    peer.emit(TexasServerMessage("expired", error = "房间已超过 30 分钟没有操作，请重新开局"))
                }
                player.peer = null
            }
        }
    }

    private fun requireFree(peer: TexasPeer) {
        if (peer.room != null) throw GameError("请先退出当前房间")
    }

    private fun find(code: String): Room {
        if (!code.matches(Regex("[A-HJ-NP-Z2-9]{8}"))) throw GameError("房间码应为 8 位字母或数字")
        val room = rooms[code] ?: throw GameError("房间不存在或已经过期")
        if (now() - room.touched >= ttl) { sweep(); throw GameError("房间已过期") }
        return room
    }

    private fun boundRoom(peer: TexasPeer): Room {
        val room = peer.room?.let { find(it) } ?: throw GameError("请先加入房间")
        if (room.players.getOrNull(peer.seat - 1)?.peer !== peer) throw GameError("连接身份已失效")
        return room
    }

    private fun remember(room: Room, seat: Int, command: TexasClientMessage) {
        room.requests["$seat:${command.requestId}"] = command
        if (room.requests.size > 512) room.requests.remove(room.requests.keys.first())
    }

    private fun welcome(peer: TexasPeer, room: Room, player: Player, requestId: String) {
        peer.emit(TexasServerMessage("welcome", roomCode = room.code, seat = peer.seat, token = player.token,
            state = room.state(peer.seat), requestId = requestId))
    }

    private fun emitState(peer: TexasPeer, room: Room, requestId: String) {
        peer.emit(TexasServerMessage("state", roomCode = room.code, state = room.state(peer.seat), requestId = requestId))
    }

    private fun broadcast(room: Room, requestId: String) {
        room.players.forEach { it.peer?.let { peer -> emitState(peer, room, requestId) } }
    }

    private fun newToken(): String = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
}
