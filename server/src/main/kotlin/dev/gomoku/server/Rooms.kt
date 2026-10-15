package dev.gomoku.server

import dev.gomoku.core.*
import java.security.SecureRandom
import java.util.Base64

class Peer(val id: String, val ip: String, val emit: (ServerMessage) -> Unit, val close: () -> Unit = {}) {
    internal var room: String? = null
    internal var seat = 0
}

class Rooms(private val now: () -> Long = System::currentTimeMillis) {
    private data class Player(val token: String, var peer: Peer?)
    private class Room(val code: String, val creatorIp: String, val black: Player, var touched: Long) {
        var white: Player? = null
        val game = Game()
        val requests = linkedMapOf<String, ClientMessage>()
        fun state() = game.snapshot().copy(blackOnline = black.peer != null, whiteOnline = white?.peer != null)
    }
    private val random = SecureRandom()
    private val rooms = mutableMapOf<String, Room>()
    private val ttl = 30 * 60 * 1000L

    @Synchronized fun handle(peer: Peer, command: ClientMessage) {
        try {
            if (command.protocol != PROTOCOL_VERSION) throw GameError("协议版本不兼容，请更新插件")
            if (command.requestId.length !in 1..64) throw GameError("请求编号无效")
            when (command.type) {
                "create" -> {
                    requireFree(peer)
                    sweep()
                    if (rooms.size >= 200 || rooms.values.count { it.creatorIp == peer.ip } >= 5) throw GameError("房间数量已达上限，请稍后重试")
                    var code: String
                    do { code = (1..8).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[random.nextInt(32)] }.joinToString("") } while (rooms.containsKey(code))
                    val player = Player(newToken(), peer)
                    val room = Room(code, peer.ip, player, now())
                    rooms[code] = room
                    peer.room = code
                    peer.seat = 1
                    welcome(peer, room, player, command.requestId)
                }
                "join" -> {
                    requireFree(peer)
                    val room = find(command.roomCode)
                    if (room.white != null || room.game.snapshot().status != "waiting") throw GameError("房间已满或对局已经结束")
                    if (room.black.peer == null) throw GameError("房主暂时离线，请稍后加入")
                    val player = Player(newToken(), peer)
                    room.white = player
                    room.game.start()
                    room.touched = now()
                    peer.room = room.code
                    peer.seat = 2
                    welcome(peer, room, player, command.requestId)
                    broadcast(room, command.requestId)
                }
                "resume" -> {
                    requireFree(peer)
                    val room = find(command.roomCode)
                    val player = when (command.token) {
                        room.black.token -> { peer.seat = 1; room.black }
                        room.white?.token -> { peer.seat = 2; room.white!! }
                        else -> throw GameError("无法恢复身份，请重新创建或加入房间")
                    }
                    player.peer?.let { old -> old.room = null; old.seat = 0; old.close() }
                    player.peer = peer
                    peer.room = room.code
                    room.touched = now()
                    welcome(peer, room, player, command.requestId)
                    broadcast(room, command.requestId)
                }
                "move", "resign" -> {
                    val room = boundRoom(peer)
                    val key = "${peer.seat}:${command.requestId}"
                    val previous = room.requests[key]
                    if (previous != null) {
                        if (previous != command) throw GameError("重复的请求编号")
                        peer.emit(ServerMessage("state", roomCode = room.code, state = room.state(), requestId = command.requestId))
                        return
                    }
                    if (command.type == "move") {
                        if (room.black.peer == null || room.white?.peer == null) throw GameError("对方暂时离线，等待重连")
                        room.game.move(peer.seat, command.x, command.y, command.revision)
                    } else room.game.resign(peer.seat, command.revision)
                    room.requests[key] = command
                    if (room.requests.size > 512) room.requests.remove(room.requests.keys.first())
                    room.touched = now()
                    broadcast(room, command.requestId)
                }
                "leave" -> {
                    val room = boundRoom(peer)
                    val state = room.game.snapshot()
                    if (state.status == "playing") room.game.resign(peer.seat, state.revision)
                    disconnect(peer)
                    if (state.status == "waiting" || (room.black.peer == null && room.white?.peer == null)) rooms.remove(room.code)
                    peer.emit(ServerMessage("left", requestId = command.requestId))
                }
                else -> throw GameError("未知操作")
            }
        } catch (e: GameError) {
            peer.emit(ServerMessage("error", error = e.message ?: "操作失败", requestId = command.requestId,
                roomCode = peer.room.orEmpty(), state = peer.room?.let { rooms[it]?.state() }))
        }
    }

    @Synchronized fun disconnect(peer: Peer) {
        val room = peer.room?.let { rooms[it] } ?: return
        if (room.black.peer === peer) room.black.peer = null
        if (room.white?.peer === peer) room.white?.peer = null
        peer.room = null
        peer.seat = 0
        room.touched = now()
        broadcast(room, "")
    }

    @Synchronized fun sweep() {
        val expired = rooms.values.filter { now() - it.touched >= ttl }
        for (room in expired) {
            rooms.remove(room.code)
            for (peer in listOfNotNull(room.black.peer, room.white?.peer)) {
                peer.room = null
                peer.seat = 0
                peer.emit(ServerMessage("expired", error = "房间已超过 30 分钟没有操作，请重新开局"))
            }
        }
    }

    private fun requireFree(peer: Peer) {
        if (peer.room != null) throw GameError("请先退出当前房间")
    }

    private fun find(code: String): Room {
        if (!code.matches(Regex("[A-HJ-NP-Z2-9]{8}"))) throw GameError("房间码应为 8 位字母或数字")
        val room = rooms[code] ?: throw GameError("房间不存在或已经过期")
        if (now() - room.touched >= ttl) { sweep(); throw GameError("房间已过期") }
        return room
    }

    private fun boundRoom(peer: Peer): Room {
        val room = peer.room?.let { find(it) } ?: throw GameError("请先加入房间")
        val current = if (peer.seat == 1) room.black.peer else room.white?.peer
        if (current !== peer) throw GameError("连接身份已失效")
        return room
    }

    private fun welcome(peer: Peer, room: Room, player: Player, requestId: String) {
        peer.emit(ServerMessage("welcome", roomCode = room.code, seat = peer.seat, token = player.token,
            state = room.state(), requestId = requestId))
    }

    private fun broadcast(room: Room, requestId: String) {
        val event = ServerMessage("state", roomCode = room.code, state = room.state(), requestId = requestId)
        room.black.peer?.emit(event)
        room.white?.peer?.emit(event)
    }

    private fun newToken(): String = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
}

