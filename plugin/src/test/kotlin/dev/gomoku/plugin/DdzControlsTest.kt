package dev.gomoku.plugin

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.testFramework.EditorTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.JBUI
import dev.gomoku.core.DdzServerMessage
import dev.gomoku.core.DdzState
import java.awt.event.MouseEvent
import javax.swing.KeyStroke

class DdzControlsTest : BasePlatformTestCase() {
    override fun tearDown() {
        try { com.intellij.ide.util.PropertiesComponent.getInstance(project).unsetValue("gomoku.fontSize") }
        finally { super.tearDown() }
    }

    private fun session(): GomokuSession {
        myFixture.configureByText(PlainTextFileType.INSTANCE, "val source = 1\n")
        EditorTestUtil.setEditorVisibleSize(myFixture.editor, 140, 60)
        return project.getService(GomokuSession::class.java).also {
            it.selectedCards.clear()
            it.receiveDdz(DdzServerMessage("welcome", roomCode = "AABBCCDD", seat = 1, token = "x".repeat(43),
                state = DdzState(status = "playing", hand = (0..19).toList(), counts = listOf(20,17,17),
                    bottom = listOf(17,18,19), landlord = 1, turn = 1, bid = 3, round = 1, revision = it.ddzState.revision + 1,
                    online = listOf(true,true,true))))
            it.showBoard(myFixture.editor)
        }
    }

    private fun action(key: String) = ActionUtil.getActions(myFixture.editor.contentComponent).single {
        it.shortcutSet.shortcuts.contains(KeyboardShortcut(KeyStroke.getKeyStroke(key), null))
    }
    private fun enabled(action: AnAction): Boolean {
        val e = AnActionEvent.createFromAnAction(action, null, "test", DataContext.EMPTY_CONTEXT)
        action.update(e)
        return e.presentation.isEnabled
    }
    private fun press(key: String) {
        val action = action(key)
        assertTrue(enabled(action))
        action.actionPerformed(AnActionEvent.createFromAnAction(action, null, "test", DataContext.EMPTY_CONTEXT))
    }
    private fun click(x: Int, y: Int) {
        val c = myFixture.editor.contentComponent
        c.dispatchEvent(MouseEvent(c, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, x, y, x, y, 1, false, MouseEvent.BUTTON1))
    }

    fun testSelectionKeyboardAndHideKeepSourceAndHand() {
        val session = session()
        press("SPACE")
        press("RIGHT")
        press("SPACE")
        assertEquals(setOf(0,1), session.selectedCards)
        assertTrue(session.canDdzCommand("play"))
        assertFalse(session.canDdzCommand("pass"))
        val hand = session.ddzState.hand
        session.toggleBoard(myFixture.editor)
        assertFalse(session.isBoardVisible)
        session.toggleBoard(myFixture.editor)
        assertTrue(session.isBoardVisible)
        assertEquals(hand, session.ddzState.hand)
        assertEquals(setOf(0,1), session.selectedCards)
        assertEquals("val source = 1\n", myFixture.editor.document.text)
        click(1,1)
        assertTrue(session.isBoardVisible)
        assertFalse(enabled(action("ENTER")))
        assertFalse(enabled(action("SPACE")))
    }

    fun testMouseSelectsSecondRowAtEveryFontSizeAndNewDealClearsSelection() {
        val session = session()
        val editor = myFixture.editor
        for (size in listOf(8,14,32)) {
            session.fontSize = size
            session.selectedCards.clear()
            val inlay = editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength).single()
            val bounds = inlay.bounds ?: error("No bounds")
            val font = editor.colorsScheme.getFont(EditorFontType.PLAIN).deriveFont(JBUI.scale(size).toFloat())
            val metrics = editor.contentComponent.getFontMetrics(font)
            val padding = JBUI.scale(6)
            // Header and owner are rows 0/1; row 3 is the second group of ten cards.
            val x = bounds.x + padding + metrics.stringWidth(" ".repeat("val cache = arrayOf(".length)) + 1
            val y = bounds.y + padding + 3 * metrics.height + metrics.height / 2
            click(x,y)
            assertEquals(setOf(10), session.selectedCards)
            click(x,y)
            assertTrue(session.selectedCards.isEmpty())
        }
        press("SPACE")
        assertFalse(session.selectedCards.isEmpty())
        session.receiveDdz(DdzServerMessage("state", state = session.ddzState.copy(round = 2, revision = session.ddzState.revision + 1)))
        assertTrue(session.selectedCards.isEmpty())
        assertEquals("val source = 1\n", editor.document.text)
    }

    fun testPhasesOfflineAndMalformedStateValidation() {
        val session = session()
        session.receiveDdz(DdzServerMessage("state", state = session.ddzState.copy(status = "bidding", bid = 1)))
        assertTrue(session.canDdzCommand("bid", 0))
        assertFalse(session.canDdzCommand("bid", 1))
        assertTrue(session.canDdzCommand("bid", 2))
        assertFalse(session.canDdzCommand("bid", 4))
        assertFalse(session.canDdzCommand("play"))
        session.receiveDdz(DdzServerMessage("state", state = session.ddzState.copy(online = listOf(true,false,true))))
        assertFalse(session.canDdzCommand("bid", 3))
        val good = session.ddzState
        assertTrue(validDdzState(good))
        assertFalse(validDdzState(good.copy(hand = listOf(0,0))))
        assertFalse(validDdzState(good.copy(hand = listOf(54))))
        assertFalse(validDdzState(good.copy(online = emptyList())))
        assertFalse(validDdzState(good.copy(counts = listOf(21,0,0))))
        assertFalse(validDdzState(good.copy(ready = emptyList())))
    }

    fun testEndpointSwitchDoesNotDuplicateGamePath() {
        assertEquals("wss://zhugetian.com/gomoku/ddz/v2/ws", gameEndpoint(DEFAULT_SERVER, true))
        assertEquals(DEFAULT_SERVER, gameEndpoint("wss://zhugetian.com/gomoku/ddz/v2/ws", false))
        assertEquals("wss://zhugetian.com/gomoku/ddz/v2/ws", gameEndpoint("wss://zhugetian.com/gomoku/ddz/ws", true))
        assertEquals("wss://example.com/custom/ddz/v2/ws?q=1", gameEndpoint("wss://example.com/custom/ws?q=1", true))
        assertEquals("wss://example.com/custom/ddz/v2/ws", gameEndpoint("wss://example.com/custom/ddz/v2/ws", true))
        assertEquals("ws://localhost:8092/gomoku/ddz/v2/ws", gameEndpoint("ws://localhost:8092/gomoku/ws", true))
    }
    fun testStartWithOneOrTwoHumansIsHostOnlyAndStopsAfterDealing() {
        val session = session()
        fun waiting(seat: Int, online: List<Boolean>) {
            session.receiveDdz(DdzServerMessage("welcome", roomCode = "AABBCCDD", seat = seat, token = "x".repeat(43),
                state = DdzState(online = online, revision = session.ddzState.revision + 1)))
        }
        waiting(1, listOf(true,false,false))
        assertTrue(session.canDdzCommand("start"))
        assertFalse(session.canDdzCommand("bid", 1))
        waiting(1, listOf(true,true,false))
        assertTrue(session.canDdzCommand("start"))
        waiting(2, listOf(true,true,false))
        assertFalse(session.canDdzCommand("start"))
        waiting(1, listOf(true,true,true))
        assertFalse(session.canDdzCommand("start"))
        session.receiveDdz(DdzServerMessage("state", state = session.ddzState.copy(status = "bidding", turn = 1,
            bots = listOf(false,true,true))))
        assertFalse(session.canDdzCommand("start"))
        assertTrue(session.canDdzCommand("bid", 1))
        assertFalse(validDdzState(session.ddzState.copy(bots = emptyList())))
    }

    fun testLetterAndJokerLabelsHaveCorrectMouseTargetsAtEverySize() {
        val session = session()
        val hand = listOf(32,36,40,44,48,52,53)
        assertEquals(listOf("J","Q","K","A","2","Xiao","Da"), hand.map(dev.gomoku.core.DdzRules::label))
        session.receiveDdz(DdzServerMessage("state", state = session.ddzState.copy(hand = hand, counts = listOf(7,17,17))))
        val editor = myFixture.editor
        for (size in listOf(8,14,32)) {
            session.fontSize = size
            session.selectedCards.clear()
            val inlay = editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength).single()
            val bounds = inlay.bounds ?: error("No bounds")
            val metrics = editor.contentComponent.getFontMetrics(editor.colorsScheme.getFont(EditorFontType.PLAIN).deriveFont(JBUI.scale(size).toFloat()))
            var x = bounds.x + JBUI.scale(6) + metrics.stringWidth("val cache = arrayOf(")
            val y = bounds.y + JBUI.scale(6) + 2 * metrics.height + metrics.height / 2
            for (card in hand) {
                val label = dev.gomoku.core.DdzRules.label(card).padStart(2)
                click(x + metrics.stringWidth(label) / 2, y)
                assertTrue("Cannot select $label at $size", card in session.selectedCards)
                x += metrics.stringWidth(label) + metrics.stringWidth(",")
            }
            assertEquals(hand.toSet(), session.selectedCards)
        }
        assertEquals("val source = 1\n", editor.document.text)
    }

}
