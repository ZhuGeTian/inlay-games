package dev.gomoku.core

class GameError(message: String) : IllegalArgumentException(message)

class Game {
    private val board = IntArray(BOARD_SIZE * BOARD_SIZE)
    private var status = "waiting"
    private var turn = 1
    private var winner = 0
    private var revision = 0
    private var lastMove = -1

    fun snapshot() = GameState(board.toList(), status, turn, winner, revision, lastMove)

    fun start() {
        if (status != "waiting") throw GameError("对局已经开始")
        status = "playing"
        revision++
    }

    fun move(seat: Int, x: Int, y: Int, expectedRevision: Int) {
        if (status != "playing") throw GameError("当前不能落子")
        if (seat != turn) throw GameError("还没有轮到你")
        if (expectedRevision != revision) throw GameError("棋局已更新，请按最新棋盘落子")
        if (x !in 0 until BOARD_SIZE || y !in 0 until BOARD_SIZE) throw GameError("落点超出棋盘")
        val index = y * BOARD_SIZE + x
        if (board[index] != 0) throw GameError("这里已经有棋子")
        board[index] = seat
        lastMove = index
        revision++
        val won = listOf(1 to 0, 0 to 1, 1 to 1, 1 to -1).any { (dx, dy) ->
            1 + count(x, y, dx, dy, seat) + count(x, y, -dx, -dy, seat) >= 5
        }
        if (won) {
            winner = seat
            status = "finished"
        } else if (board.none { it == 0 }) {
            status = "finished"
        } else {
            turn = 3 - seat
        }
    }

    fun resign(seat: Int, expectedRevision: Int) {
        if (seat !in 1..2 || status != "playing") throw GameError("当前不能认输")
        if (expectedRevision != revision) throw GameError("棋局已更新，请重试")
        status = "finished"
        winner = 3 - seat
        revision++
    }

    private fun count(x: Int, y: Int, dx: Int, dy: Int, seat: Int): Int {
        var nx = x + dx
        var ny = y + dy
        var n = 0
        while (nx in 0 until BOARD_SIZE && ny in 0 until BOARD_SIZE && board[ny * BOARD_SIZE + nx] == seat) {
            n++
            nx += dx
            ny += dy
        }
        return n
    }
}

