package dev.gomoku.plugin

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.editor.*
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.*
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.JBUI
import dev.gomoku.core.DdzRules
import java.awt.*
import java.awt.event.KeyEvent
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

class DdzView(private val editor: Editor, private val session: GomokuSession) : EditorCustomElementRenderer, GameView {
    private data class Token(val text: String, val card: Int? = null, val command: String? = null, val value: Int = 0, val yellow: Boolean = false)
    private data class Cell(val token: Token, val bounds: Rectangle)
    private var inlay: Inlay<DdzView>? = null
    private var keyboardActive = true
    private var cursor = 0
    private var disposed = false
    private val orange = Color(0xE89A4B)
    private val yellow = Color(0xE4D35E)
    private val font get() = editor.colorsScheme.getFont(EditorFontType.PLAIN).deriveFont(JBUI.scale(session.fontSize).toFloat())
    private val padding get() = JBUI.scale(6)

    private fun rows(): List<List<Token>> {
        val s = session.ddzState
        val rows = mutableListOf<List<Token>>()
        fun line(text: String) { rows.add(listOf(Token(text))) }
        fun cards(prefix: String, values: List<Int>, selectable: Boolean = false, marked: Boolean = false) {
            val chunks = values.chunked(10).ifEmpty { listOf(emptyList()) }
            chunks.forEachIndexed { row, chunk ->
                val tokens = mutableListOf(Token(if (row == 0) prefix else " ".repeat(prefix.length)))
                chunk.forEachIndexed { i, card ->
                    tokens.add(Token(DdzRules.label(card).padStart(2), if (selectable) card else null, yellow = marked))
                    if (i < chunk.lastIndex || row < chunks.lastIndex) tokens.add(Token(","))
                }
                if (row == chunks.lastIndex) tokens.add(Token(")"))
                rows.add(tokens)
            }
        }
        val phase = listOf("waiting", "bidding", "playing", "finished").indexOf(s.status)
        line("/* cache: ${session.seat}/${s.turn}; phase=$phase */")
        line("val owner = ${s.landlord}; val round = ${s.round}")
        cards("val cache = arrayOf(", s.hand, selectable = true)
        line("val sizes = intArrayOf(${s.counts.joinToString(",")})")
        line("val live = intArrayOf(${s.online.joinToString(",") { if (it) "1" else "0" }})")
        if (s.bots.any { it }) line("val auto = intArrayOf(${s.bots.joinToString(",") { if (it) "1" else "0" }})")
        if (s.status == "bidding") line("val bids = intArrayOf(${s.bids.joinToString(",")})")
        cards("val seed = arrayOf(", s.bottom)
        cards("val delta = arrayOf(", s.trick, marked = true)
        line("val source = ${s.trickSeat}; val skip = ${s.passes}")
        line("val base = ${s.bid}; val factor = ${s.multiplier}")
        line("val score = longArrayOf(${s.scores.joinToString(",")})")
        if (s.status == "finished") {
            line("val winner = ${s.winner}")
            line("val change = longArrayOf(${s.roundDelta.joinToString(",")})")
            line("val ready = intArrayOf(${s.ready.joinToString(",") { if (it) "1" else "0" }})")
        }
        if (s.lastAction.isNotEmpty()) s.lastAction.chunked(48).forEach { line("// $it") }
        val controls = when (s.status) {
            "waiting" -> listOf(Token("init()", command = "start"))
            "bidding" -> (0..3).map { Token("bid($it)", command = "bid", value = it) }
            "playing" -> listOf(Token("commit()", command = "play"), Token("skip()", command = "pass"), Token("clear()", command = "clear"))
            "finished" -> listOf(Token("next()", command = "ready"))
            else -> emptyList()
        }
        if (controls.isNotEmpty()) rows.add(controls.flatMapIndexed { i, token -> if (i == 0) listOf(token) else listOf(Token("  "), token) })
        if (session.status != "已连接") session.status.chunked(28).forEach { line("// $it") }
        return rows
    }

    private fun layout(): List<Cell> {
        val metrics = editor.contentComponent.getFontMetrics(font)
        return rows().flatMapIndexed { row, tokens ->
            var x = padding
            tokens.map { token ->
                val width = metrics.stringWidth(token.text)
                Cell(token, Rectangle(x, padding + row * metrics.height, width, metrics.height)).also { x += width }
            }
        }
    }

    override fun attach() {
        if (editor.isDisposed) { Disposer.dispose(this); return }
        val offset = editor.document.getLineEndOffset(editor.caretModel.logicalPosition.line)
        inlay = editor.inlayModel.addBlockElement(offset, true, false, 0, this)
        if (inlay == null) { session.notice("当前编辑器不支持数组显示，请换一个文本文件"); Disposer.dispose(this); return }
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorReleased(event: EditorFactoryEvent) { if (event.editor === editor) Disposer.dispose(this@DdzView) }
        }, this)
        editor.addEditorMouseListener(object : EditorMouseListener {
            override fun mousePressed(event: EditorMouseEvent) {
                val target = inlay ?: return
                val bounds = target.bounds ?: return
                val point = event.mouseEvent.point
                if (event.inlay !== target || !bounds.contains(point)) {
                    keyboardActive = false
                    refresh()
                    return
                }
                if (!SwingUtilities.isLeftMouseButton(event.mouseEvent)) return
                keyboardActive = true
                event.consume()
                val hit = layout().firstOrNull { it.bounds.contains(point.x - bounds.x, point.y - bounds.y) }?.token
                hit?.card?.let { card ->
                    cursor = session.ddzState.hand.indexOf(card).coerceAtLeast(0)
                    toggleCard(card)
                }
                hit?.command?.let { command ->
                    if (command == "clear") session.selectedCards.clear() else session.ddzCommand(command, hit.value)
                }
                refresh()
            }
        }, this)
        for (key in listOf(KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_SPACE, KeyEvent.VK_ENTER, KeyEvent.VK_ESCAPE)) {
            object : DumbAwareAction("斗地主键盘操作") {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) { e.presentation.isEnabled = keyboardActive && !disposed && inlay?.isValid == true && inlay?.bounds != null }
                override fun actionPerformed(e: AnActionEvent) {
                    val hand = session.ddzState.hand
                    when (key) {
                        KeyEvent.VK_LEFT -> cursor--
                        KeyEvent.VK_RIGHT -> cursor++
                        KeyEvent.VK_UP -> cursor -= 10
                        KeyEvent.VK_DOWN -> cursor += 10
                        KeyEvent.VK_SPACE -> hand.getOrNull(cursor)?.let(::toggleCard)
                        KeyEvent.VK_ENTER -> session.ddzCommand("play")
                        KeyEvent.VK_ESCAPE -> Disposer.dispose(this@DdzView)
                    }
                    cursor = cursor.coerceIn(0, maxOf(0, hand.lastIndex))
                    refresh()
                }
            }.registerCustomShortcutSet(CustomShortcutSet(KeyStroke.getKeyStroke(key, 0)), editor.contentComponent, this)
        }
        editor.contentComponent.requestFocusInWindow()
        inlay?.bounds?.let { editor.scrollingModel.scrollVertically(it.y) }
    }

    private fun toggleCard(card: Int) {
        if (session.ddzState.status != "playing") return
        if (!session.selectedCards.add(card)) session.selectedCards.remove(card)
    }

    override fun refresh() {
        cursor = cursor.coerceIn(0, maxOf(0, session.ddzState.hand.lastIndex))
        if (!disposed && !editor.isDisposed) inlay?.takeIf { it.isValid }?.update()
    }
    override fun calcWidthInPixels(inlay: Inlay<*>) = (layout().maxOfOrNull { it.bounds.x + it.bounds.width } ?: 0) + padding
    override fun calcHeightInPixels(inlay: Inlay<*>) = (layout().maxOfOrNull { it.bounds.y + it.bounds.height } ?: 0) + padding
    override fun getContextMenuGroupId(inlay: Inlay<*>) = "Gomoku.Menu"

    override fun paint(inlay: Inlay<*>, graphics: Graphics, region: Rectangle, attributes: TextAttributes) {
        val g = graphics.create() as Graphics2D
        try {
            g.translate(region.x, region.y)
            g.color = editor.colorsScheme.defaultBackground
            g.fillRect(0, 0, region.width, region.height)
            g.font = font
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            for ((token, bounds) in layout()) {
                val selected = token.card != null && token.card in session.selectedCards
                val enabled = token.command?.let { if (it == "clear") session.selectedCards.isNotEmpty() else session.canDdzCommand(it, token.value) } ?: true
                g.color = when {
                    selected -> orange
                    token.yellow -> yellow
                    token.command != null && enabled -> orange
                    token.command != null -> editor.colorsScheme.defaultForeground.let { Color(it.red, it.green, it.blue, 110) }
                    else -> editor.colorsScheme.defaultForeground
                }
                val baseline = bounds.y + g.fontMetrics.ascent
                g.drawString(token.text, bounds.x, baseline)
                if (selected || (keyboardActive && token.card != null && token.card == session.ddzState.hand.getOrNull(cursor))) {
                    g.drawLine(bounds.x, baseline + 2, bounds.x + bounds.width - 1, baseline + 2)
                }
            }
        } finally { g.dispose() }
    }

    override fun dispose() {
        disposed = true
        inlay?.takeIf { it.isValid }?.let { Disposer.dispose(it) }
        inlay = null
        session.boardClosed(this)
    }
}
