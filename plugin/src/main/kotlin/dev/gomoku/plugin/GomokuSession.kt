package dev.gomoku.plugin

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import dev.gomoku.core.*
import kotlinx.serialization.encodeToString
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletionStage
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class GomokuSession(private val project: Project) : Disposable {
    var state = GameState(); private set
    var ddzState = DdzState(); private set
    var texasState = TexasState(); private set
    var game = GameKind.GOMOKU; private set
    val isDdz get() = game == GameKind.DDZ
    val isTexas get() = game == GameKind.TEXAS
    internal val selectedCards = linkedSetOf<Int>()
    var roomCode = ""; private set
    var seat = 0; private set
    var status = "使用右键菜单创建或加入房间"; private set
    var connected = false; private set
    val hasSession: Boolean get() = roomCode.isNotEmpty() || connecting
    val isBoardVisible: Boolean get() = board != null
    var fontSize: Int
        get() = PropertiesComponent.getInstance(project).getInt("gomoku.fontSize", 14).coerceIn(8, 32)
        set(value) {
            require(value in 8..32)
            PropertiesComponent.getInstance(project).setValue("gomoku.fontSize", value, 14)
            refresh()
        }
    val canMove: Boolean get() = game == GameKind.GOMOKU && connected && pending == null && state.status == "playing" && state.turn == seat && state.blackOnline && state.whiteOnline
    private var endpoint = DEFAULT_SERVER
    private var token = ""
    private var socket: WebSocket? = null
    private var connecting = false
    private var generation = 0
    @Volatile private var disposed = false
    private var pending: String? = null
    private var timeout: ScheduledFuture<*>? = null
    private var retry: ScheduledFuture<*>? = null
    private var retries = 0
    private var board: GameView? = null
    private var client: HttpClient? = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    init {
        val factory = EditorFactory.getInstance()
        val toggle = ActionManager.getInstance().getAction("Gomoku.Toggle")
        // Editor-local registration takes priority over IDE actions sharing Ctrl+G.
        fun register(editor: Editor) {
            if (editor.project === project) toggle.registerCustomShortcutSet(editor.contentComponent, null)
        }
        factory.addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) { register(event.editor) }
            override fun editorReleased(event: EditorFactoryEvent) {
                if (event.editor.project === project) toggle.unregisterCustomShortcutSet(event.editor.contentComponent)
            }
        }, this)
        factory.allEditors.forEach(::register)
        Disposer.register(this, Disposable {
            factory.allEditors.filter { it.project === project }.forEach { toggle.unregisterCustomShortcutSet(it.contentComponent) }
        })
    }

    fun start(editor: Editor, server: String, code: String?, kind: GameKind = GameKind.GOMOKU) {
        game = kind
        endpoint = gameEndpoint(server, kind)
        state = GameState()
        ddzState = DdzState()
        texasState = TexasState()
        selectedCards.clear()
        seat = 0
        showBoard(editor)
        val type = if (code == null) "create" else "join"
        if (isTexas) connect(TexasClientMessage(type, roomCode = code.orEmpty(), requestId = id()))
        else if (isDdz) connect(DdzClientMessage(type, roomCode = code.orEmpty(), requestId = id()))
        else connect(ClientMessage(type, roomCode = code.orEmpty(), requestId = id()))
    }

    fun showBoard(editor: Editor) {
        board?.let { Disposer.dispose(it) }
        val view: GameView = when (game) { GameKind.TEXAS -> TexasView(editor, this); GameKind.DDZ -> DdzView(editor, this); GameKind.GOMOKU -> BoardView(editor, this) }
        board = view
        Disposer.register(this, view)
        view.attach()
    }

    fun toggleBoard(editor: Editor) {
        val current = board
        if (current == null) showBoard(editor) else Disposer.dispose(current)
    }

    fun move(x: Int, y: Int) {
        if (!canMove) return
        send(ClientMessage("move", requestId = id(), x = x, y = y, revision = state.revision))
    }

    fun resign() {
        if (isTexas) { texasCommand("fold"); return }
        if (isDdz) { ddzCommand("resign"); return }
        if (!connected || state.status != "playing" || pending != null) { notice("当前不能认输"); return }
        send(ClientMessage("resign", requestId = id(), revision = state.revision))
    }

    fun reconnect() {
        if (token.isEmpty()) { notice("没有可恢复的对局，请创建或加入房间"); return }
        retries = 0
        resume()
    }

    private fun resume() {
        if (isTexas) connect(TexasClientMessage("resume", requestId = id(), roomCode = roomCode, token = token))
        else if (isDdz) connect(DdzClientMessage("resume", requestId = id(), roomCode = roomCode, token = token))
        else connect(ClientMessage("resume", requestId = id(), roomCode = roomCode, token = token))
    }

    fun canDdzCommand(type: String, value: Int = 0): Boolean {
        if (!isDdz || !connected || pending != null || seat !in 1..3) return false
        if (type == "start") return seat == 1 && ddzState.status == "waiting" && ddzState.online[0] && ddzState.online.count { it } in 1..2
        if (ddzState.online.any { !it }) return false
        return when (type) {
            "bid" -> ddzState.status == "bidding" && ddzState.turn == seat && value in 0..3 && (value == 0 || value > ddzState.bid)
            "play" -> ddzState.status == "playing" && ddzState.turn == seat && selectedCards.isNotEmpty()
            "pass" -> ddzState.status == "playing" && ddzState.turn == seat && ddzState.trick.isNotEmpty() && ddzState.trickSeat != seat
            "ready" -> ddzState.status == "finished" && !ddzState.ready[seat - 1]
            "resign" -> ddzState.status == "playing"
            else -> false
        }
    }

    fun ddzCommand(type: String, value: Int = 0) {
        if (!canDdzCommand(type, value)) return
        send(DdzClientMessage(type, requestId = id(), revision = ddzState.revision, bid = value,
            cards = if (type == "play") selectedCards.toList() else emptyList()))
    }

    fun canTexasCommand(type: String): Boolean {
        if (!isTexas || !connected || pending != null || seat !in 1..3) return false
        if (type == "start") return seat == 1 && texasState.status == "waiting" &&
            texasState.online[0] && texasState.online.count { it } in 1..2
        return texasState.online.all { it } && type in texasState.legalActions
    }

    fun texasCommand(type: String, raiseTo: Long = 0, revision: Int = texasState.revision) {
        if (revision != texasState.revision) { notice("局面已更新，请重新选择操作"); return }
        if (!canTexasCommand(type)) return
        if (type == "raise" && !validTexasRaise(texasState, raiseTo)) { notice("加注额超出当前范围"); return }
        send(TexasClientMessage(type, requestId = id(), revision = revision, raiseTo = raiseTo))
    }

    internal fun receiveTexas(message: TexasServerMessage) {
        if (message.protocol != TEXAS_PROTOCOL_VERSION || message.state?.let { !validTexasState(it) } == true ||
            (message.type == "welcome" && (message.seat !in 1..3 || message.token.length !in 32..256 || !message.roomCode.matches(Regex("[A-HJ-NP-Z2-9]{8}"))))) {
            failed(generation, "服务器协议不兼容"); return
        }
        if (message.requestId == pending || message.type in setOf("welcome", "error", "left", "closed", "expired")) {
            pending = null
            timeout?.cancel(false)
        }
        message.state?.let { if (it.revision >= texasState.revision) texasState = it }
        when (message.type) {
            "welcome" -> {
                game = GameKind.TEXAS
                roomCode = message.roomCode
                seat = message.seat
                token = message.token
                connected = true
                connecting = false
                retries = 0
                status = "已连接"
            }
            "state" -> status = "已连接"
            "error" -> {
                status = message.error
                notice(message.error)
                if (connecting) reset(message.error)
            }
            "left" -> reset("已退出房间")
            "closed", "expired" -> { reset(message.error); notice(message.error) }
        }
        refresh()
    }

    fun leave() {
        if (!connected) { reset("已退出；离线棋局将在房间过期后清理"); return }
        if (pending != null) { notice("正在等待服务器确认，请稍后退出"); return }
        if (isTexas) send(TexasClientMessage("leave", requestId = id()))
        else if (isDdz) send(DdzClientMessage("leave", requestId = id()))
        else send(ClientMessage("leave", requestId = id()))
    }

    private fun connect(first: TexasClientMessage) = connect(wireJson.encodeToString(first), first.requestId, first.type == "resume")
    private fun connect(first: ClientMessage) = connect(wireJson.encodeToString(first), first.requestId, first.type == "resume")
    private fun connect(first: DdzClientMessage) = connect(wireJson.encodeToString(first), first.requestId, first.type == "resume")

    private fun connect(payload: String, requestId: String, resume: Boolean) {
        val httpClient = client ?: return
        retry?.cancel(false)
        timeout?.cancel(false)
        val gen = ++generation
        socket?.abort()
        socket = null
        connecting = true
        connected = false
        pending = requestId
        status = if (resume) "正在恢复连接…" else "正在连接服务器…"
        refresh()
        httpClient.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(URI(endpoint), object : WebSocket.Listener {
            private val buffer = StringBuilder()
            override fun onOpen(ws: WebSocket) {
                if (disposed || project.isDisposed) { ws.abort(); return }
                ui {
                    if (gen != generation) { ws.abort(); return@ui }
                    socket = ws
                    ws.request(1)
                    transmit(ws, payload, requestId, gen)
                }
            }
            override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                buffer.append(data)
                if (buffer.length > 65_536) { ws.abort(); ui { failed(gen, "服务器消息过大") }; return null }
                if (last) {
                    val text = buffer.toString()
                    buffer.setLength(0)
                    ui {
                        if (gen != generation) return@ui
                        if (isTexas) {
                            val message = runCatching { wireJson.decodeFromString<TexasServerMessage>(text) }.getOrNull()
                            if (message == null) failed(gen, "服务器消息格式不兼容") else receiveTexas(message)
                        } else if (isDdz) {
                            val message = runCatching { wireJson.decodeFromString<DdzServerMessage>(text) }.getOrNull()
                            if (message == null) failed(gen, "服务器消息格式不兼容") else receiveDdz(message)
                        } else {
                            val message = runCatching { wireJson.decodeFromString<ServerMessage>(text) }.getOrNull()
                            if (message == null) failed(gen, "服务器消息格式不兼容") else receive(message)
                        }
                    }
                }
                ws.request(1)
                return null
            }
            override fun onClose(ws: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                ui { failed(gen, "连接已断开") }; return null
            }
            override fun onError(ws: WebSocket, error: Throwable) { ui { failed(gen, "连接失败，请检查服务器地址和网络") } }
        }).whenComplete { _, error -> if (error != null) ui { failed(gen, "连接失败，请检查服务器地址和网络") } }
    }

    private fun send(message: TexasClientMessage) = send(wireJson.encodeToString(message), message.requestId)
    private fun send(message: ClientMessage) = send(wireJson.encodeToString(message), message.requestId)
    private fun send(message: DdzClientMessage) = send(wireJson.encodeToString(message), message.requestId)

    private fun send(payload: String, requestId: String) {
        val ws = socket ?: return
        pending = requestId
        status = "已连接"
        refresh()
        transmit(ws, payload, requestId, generation)
    }

    private fun transmit(ws: WebSocket, payload: String, requestId: String, gen: Int) {
        timeout?.cancel(false)
        timeout = AppExecutorUtil.getAppScheduledExecutorService().schedule({ ui { if (gen == generation && pending == requestId) failed(gen, "服务器响应超时") } }, 12, TimeUnit.SECONDS)
        ws.sendText(payload, true).whenComplete { _, error ->
            if (error != null) ui { failed(gen, "发送失败，正在恢复对局") }
        }
    }

    internal fun receiveDdz(message: DdzServerMessage) {
        if (message.protocol != DDZ_PROTOCOL_VERSION || message.state?.let { !validDdzState(it) } == true ||
            (message.type == "welcome" && (message.seat !in 1..3 || message.token.length !in 32..256 || !message.roomCode.matches(Regex("[A-HJ-NP-Z2-9]{8}"))))) {
            failed(generation, "服务器协议不兼容"); return
        }
        if (message.requestId == pending || message.type in setOf("welcome", "error", "left", "closed", "expired")) {
            pending = null
            timeout?.cancel(false)
        }
        message.state?.let {
            if (it.revision >= ddzState.revision) {
                if (it.round != ddzState.round || it.hand != ddzState.hand) selectedCards.clear()
                ddzState = it
            }
        }
        when (message.type) {
            "welcome" -> {
                game = GameKind.DDZ
                roomCode = message.roomCode
                seat = message.seat
                token = message.token
                connected = true
                connecting = false
                retries = 0
                status = "已连接"
            }
            "state" -> status = "已连接"
            "error" -> {
                status = message.error
                notice(message.error)
                if (connecting) reset(message.error)
            }
            "left" -> reset("已退出房间")
            "closed", "expired" -> { reset(message.error); notice(message.error) }
        }
        refresh()
    }

    private fun receive(message: ServerMessage) {
        if (message.protocol != PROTOCOL_VERSION || message.state?.let { it.board.size != 225 || it.board.any { v -> v !in 0..2 } } == true) {
            failed(generation, "服务器协议不兼容"); return
        }
        if (message.requestId == pending || message.type in setOf("welcome", "error", "left", "expired")) {
            pending = null
            timeout?.cancel(false)
        }
        message.state?.let { if (it.revision >= state.revision) state = it }
        when (message.type) {
            "welcome" -> {
                roomCode = message.roomCode
                seat = message.seat
                token = message.token
                connected = true
                connecting = false
                retries = 0
                status = "已连接"
            }
            "error" -> {
                status = message.error
                notice(message.error)
                if (connecting) reset(message.error)
            }
            "left" -> reset("已退出房间")
            "expired" -> { reset(message.error); notice(message.error) }
        }
        refresh()
    }

    private fun failed(gen: Int, reason: String) {
        if (gen != generation || disposed) return
        generation++
        socket?.abort()
        socket = null
        connected = false
        connecting = false
        pending = null
        timeout?.cancel(false)
        status = reason
        if (token.isNotEmpty() && retries++ < 10) {
            status += "；3 秒后重试"
            retry = AppExecutorUtil.getAppScheduledExecutorService().schedule({ ui {
                if (token.isNotEmpty() && !connected && !connecting) resume()
            } }, 3, TimeUnit.SECONDS)
        }
        refresh()
    }

    private fun reset(message: String) {
        generation++
        retry?.cancel(false)
        timeout?.cancel(false)
        socket?.abort()
        socket = null
        roomCode = ""
        token = ""
        seat = 0
        connected = false
        connecting = false
        pending = null
        state = GameState()
        ddzState = DdzState()
        texasState = TexasState()
        selectedCards.clear()
        status = message
        refresh()
    }

    private fun refresh() { board?.refresh() }
    internal fun boardClosed(view: GameView) { if (board === view) board = null }
    private fun ui(action: () -> Unit) { ApplicationManager.getApplication().invokeLater { if (!disposed && !project.isDisposed) action() } }
    private fun id() = UUID.randomUUID().toString()
    fun notice(text: String) {
        Notifications.Bus.notify(Notification("Gomoku", "联机小游戏", text, NotificationType.WARNING), project)
    }
    override fun dispose() {
        disposed = true
        generation++
        retry?.cancel(false)
        timeout?.cancel(false)
        socket?.abort()
        // Java 17 has no explicit HttpClient shutdown API; release it after aborting the socket.
        socket = null
        client = null
        token = ""
    }
}
