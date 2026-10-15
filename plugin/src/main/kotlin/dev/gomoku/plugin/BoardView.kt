package dev.gomoku.plugin

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.editor.*
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.JBUI
import dev.gomoku.core.BOARD_SIZE
import dev.gomoku.core.BoardGeometry
import java.awt.*
import java.awt.event.KeyEvent
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

class BoardView(private val editor: Editor, private val session: GomokuSession) : EditorCustomElementRenderer, GameView {
    private var inlay: Inlay<BoardView>? = null
    private var selectedX = 7
    private var selectedY = 7
    private var disposed = false
    private var keyboardActive = true
    private val orange = Color(0xE89A4B)
    private val yellow = Color(0xE4D35E)
    private val font: Font get() = editor.colorsScheme.getFont(EditorFontType.PLAIN).deriveFont(JBUI.scale(session.fontSize).toFloat())
    private val geometry: BoardGeometry get() {
        val metrics = editor.contentComponent.getFontMetrics(font)
        val digit = metrics.charWidth('0')
        val padding = JBUI.scale(6)
        return BoardGeometry(digit * 2, padding + digit * 3 + digit / 2,
            padding + metrics.height + metrics.height / 2, metrics.height,
            metrics.height + metrics.height / 2 + padding)
    }

    override fun attach() {
        if (editor.isDisposed) return
        val offset = editor.document.getLineEndOffset(editor.caretModel.logicalPosition.line)
        inlay = editor.inlayModel.addBlockElement(offset, true, false, 0, this)
        if (inlay == null) { session.notice("当前编辑器不支持棋盘，请换一个文本文件"); return }
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorReleased(event: EditorFactoryEvent) {
                if (event.editor === editor) Disposer.dispose(this@BoardView)
            }
        }, this)
        editor.addEditorMouseListener(object : EditorMouseListener {
            override fun mousePressed(event: EditorMouseEvent) {
                val target = inlay ?: return
                val bounds = target.bounds ?: return
                val point = event.mouseEvent.point
                if (event.inlay !== target || !bounds.contains(point)) {
                    // Keep the array visible while returning keyboard input to the code editor.
                    keyboardActive = false
                    refresh()
                    return
                }
                if (!SwingUtilities.isLeftMouseButton(event.mouseEvent)) return
                keyboardActive = true
                event.consume()
                geometry.intersection(point.x - bounds.x, point.y - bounds.y)?.let { (x, y) ->
                    selectedX = x; selectedY = y
                    session.move(x, y)
                }
                refresh()
            }
        }, this)
        val keys = listOf(KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_ENTER, KeyEvent.VK_ESCAPE)
        for (key in keys) {
            object : DumbAwareAction("五子棋键盘操作") {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) { e.presentation.isEnabled = keyboardActive && !disposed && inlay?.isValid == true && inlay?.bounds != null }
                override fun actionPerformed(e: AnActionEvent) {
                    when (key) {
                        KeyEvent.VK_LEFT -> selectedX = maxOf(0, selectedX - 1)
                        KeyEvent.VK_RIGHT -> selectedX = minOf(14, selectedX + 1)
                        KeyEvent.VK_UP -> selectedY = maxOf(0, selectedY - 1)
                        KeyEvent.VK_DOWN -> selectedY = minOf(14, selectedY + 1)
                        KeyEvent.VK_ENTER -> session.move(selectedX, selectedY)
                        KeyEvent.VK_ESCAPE -> Disposer.dispose(this@BoardView)
                    }
                    refresh()
                }
            }.registerCustomShortcutSet(CustomShortcutSet(KeyStroke.getKeyStroke(key, 0)), editor.contentComponent, this)
        }
        editor.contentComponent.requestFocusInWindow()
        inlay?.bounds?.let { editor.scrollingModel.scrollVertically(it.y) }
    }

    override fun refresh() { if (!disposed && !editor.isDisposed) inlay?.takeIf { it.isValid }?.update() }
    override fun calcWidthInPixels(inlay: Inlay<*>) = geometry.width
    override fun calcHeightInPixels(inlay: Inlay<*>) = geometry.height
    override fun getContextMenuGroupId(inlay: Inlay<*>) = "Gomoku.Menu"

    override fun paint(inlay: Inlay<*>, graphics: Graphics, region: Rectangle, attributes: TextAttributes) {
        val g = graphics.create() as Graphics2D
        try {
            val geo = geometry
            val state = session.state
            g.translate(region.x, region.y)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = editor.colorsScheme.defaultBackground
            g.fillRect(0, 0, geo.width, geo.height)
            g.font = font
            g.color = editor.colorsScheme.defaultForeground
            val status = when {
                !session.connected -> session.status
                state.status == "waiting" -> "${session.seat}/-"
                state.status == "finished" -> "done=${state.winner}"
                !state.blackOnline || !state.whiteOnline -> "offline"
                else -> "${session.seat}/${state.turn}"
            }
            val padding = JBUI.scale(6)
            val baseline = padding + g.fontMetrics.ascent
            text(g, "/* cache: $status", padding, baseline, geo.width - 2 * padding)
            val digit = geo.cell / 2
            val firstColumn = geo.left - digit / 2
            for (y in 0 until BOARD_SIZE) {
                val rowBaseline = baseline + (y + 1) * geo.rowHeight
                g.drawString("[", firstColumn - digit, rowBaseline)
                // Draw each cell at its hit target so fractional font advances cannot accumulate.
                for (x in 0 until BOARD_SIZE) {
                    val column = firstColumn + x * geo.cell
                    val value = state.board[y * BOARD_SIZE + x]
                    g.color = when (value) {
                        1 -> orange
                        2 -> yellow
                        else -> editor.colorsScheme.defaultForeground
                    }
                    g.drawString(value.toString(), column, rowBaseline)
                    g.color = editor.colorsScheme.defaultForeground
                    g.drawString(if (x == BOARD_SIZE - 1) "]," else ",", column + digit, rowBaseline)
                }
            }
            g.drawString("*/", padding, baseline + (BOARD_SIZE + 1) * geo.rowHeight)
            if (keyboardActive) {
                val x = geo.left + selectedX * geo.cell - geo.cell / 4
                val y = baseline + (selectedY + 1) * geo.rowHeight + JBUI.scale(1)
                g.drawLine(x, y, x + geo.cell / 2, y)
            }
        } finally { g.dispose() }
    }

    private fun text(g: Graphics2D, value: String, x: Int, y: Int, width: Int) {
        var result = value
        while (result.isNotEmpty() && g.fontMetrics.stringWidth(result) > width) result = result.dropLast(1)
        if (result != value) result = result.dropLast(1) + "…"
        g.drawString(result, x, y)
    }
    override fun dispose() {
        disposed = true
        inlay?.takeIf { it.isValid }?.let { Disposer.dispose(it) }
        inlay = null
        session.boardClosed(this)
    }
}
