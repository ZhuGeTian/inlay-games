package dev.gomoku.plugin

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.editor.*
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.*
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.KeyEvent
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

class TexasView(private val editor: Editor, private val session: GomokuSession) : EditorCustomElementRenderer, GameView {
    private data class Token(val text: String, val card: Int? = null, val command: String? = null)
    private data class Cell(val token: Token, val bounds: Rectangle)
    private var inlay: Inlay<TexasView>? = null
    private var keyboardActive = true
    private var disposed = false
    private val orange = Color(0xE89A4B)
    private val font get() = editor.colorsScheme.getFont(EditorFontType.PLAIN).deriveFont(JBUI.scale(session.fontSize).toFloat())
    private val padding get() = JBUI.scale(6)

    private fun rows(): List<List<Token>> {
        val s = session.texasState
        val rows = mutableListOf<List<Token>>()
        fun line(text: String) { rows.add(listOf(Token(text))) }
        fun cards(prefix: String, values: List<Int>) {
            val tokens = mutableListOf(Token(prefix))
            values.forEachIndexed { index, card ->
                if (index > 0) tokens.add(Token(", "))
                tokens.add(Token("\"${texasRank(card)}\"", card = card))
            }
            tokens.add(Token(")"))
            rows.add(tokens)
        }
        val phase = listOf("", "preflop", "flop", "turn", "river").indexOf(s.street)
        line("/* cache: ${session.seat}/${s.turn}; phase=$phase */")
        cards("val local = arrayOf(", s.hand)
        cards("val shared = arrayOf(", s.board)
        line("val stacks = longArrayOf(${s.stacks.joinToString(",")})")
        line("val bets = longArrayOf(${s.bets.joinToString(",")})")
        line("val live = intArrayOf(${s.online.joinToString(",") { if (it) "1" else "0" }})")
        if (s.bots.any { it }) line("val auto = intArrayOf(${s.bots.joinToString(",") { if (it) "1" else "0" }})")
        line("val flags = intArrayOf(${(0..2).joinToString(",") { if (!s.inHand[it]) "0" else if (s.folded[it]) "-1" else if (s.allIn[it]) "2" else "1" }})")
        line("val cursor = ${s.dealer}; val round = ${s.round}")
        line("val pool = ${s.pot}; val cost = ${s.toCall}")
        if (s.status == "finished") {
            line("val change = longArrayOf(${s.roundDelta.joinToString(",")})")
            s.revealed.forEachIndexed { i, hand -> if (hand.isNotEmpty()) cards("val ref${i + 1} = arrayOf(", hand) }
            line("val ready = intArrayOf(${s.ready.joinToString(",") { if (it) "1" else "0" }})")
            if (s.matchOver) line("// next(): reset(1000)")
        }
        if (s.lastAction.isNotEmpty()) s.lastAction.chunked(48).forEach { line("// $it") }
        val controls = when (s.status) {
            "waiting" -> listOf(Token("init()", command = "start"))
            "playing" -> listOf(Token("drop()", command = "fold"), Token("check()", command = "check"),
                Token("sync(${s.toCall})", command = "call"), Token("set(...)", command = "raise"),
                Token("flush(${s.stacks.getOrElse(session.seat - 1) { 0 }})", command = "allin"))
            "finished" -> listOf(Token("next()", command = "ready"))
            else -> emptyList()
        }
        controls.chunked(3).forEach { chunk -> rows.add(chunk.flatMapIndexed { i, token -> if (i == 0) listOf(token) else listOf(Token("  "), token) }) }
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
            override fun editorReleased(event: EditorFactoryEvent) { if (event.editor === editor) Disposer.dispose(this@TexasView) }
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
                hit?.command?.let { command -> editor.project?.let { texasAction(it, session, command) } }
                refresh()
            }
        }, this)
        for (key in listOf(KeyEvent.VK_ENTER, KeyEvent.VK_ESCAPE)) {
            object : DumbAwareAction("德州键盘操作") {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) { e.presentation.isEnabled = keyboardActive && !disposed && inlay?.isValid == true && inlay?.bounds != null }
                override fun actionPerformed(e: AnActionEvent) {
                    when (key) {
                        KeyEvent.VK_ENTER -> session.texasCommand(if (session.canTexasCommand("check")) "check" else "call")
                        KeyEvent.VK_ESCAPE -> Disposer.dispose(this@TexasView)
                    }
                    refresh()
                }
            }.registerCustomShortcutSet(CustomShortcutSet(KeyStroke.getKeyStroke(key, 0)), editor.contentComponent, this)
        }
        editor.contentComponent.requestFocusInWindow()
        inlay?.bounds?.let { editor.scrollingModel.scrollVertically(it.y) }
    }

    override fun refresh() {
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
                val enabled = token.command?.let(session::canTexasCommand) ?: true
                val bg = editor.colorsScheme.defaultBackground
                g.color = when {
                    token.card != null -> texasColor(token.card, bg.red + bg.green + bg.blue < 384)
                    token.command != null && enabled -> orange
                    token.command != null -> editor.colorsScheme.defaultForeground.let { Color(it.red, it.green, it.blue, 110) }
                    else -> editor.colorsScheme.defaultForeground
                }
                val baseline = bounds.y + g.fontMetrics.ascent
                g.drawString(token.text, bounds.x, baseline)

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
