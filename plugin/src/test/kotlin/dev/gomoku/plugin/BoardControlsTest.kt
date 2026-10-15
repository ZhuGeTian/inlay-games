package dev.gomoku.plugin

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.testFramework.EditorTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.event.MouseEvent
import javax.swing.KeyStroke

class BoardControlsTest : BasePlatformTestCase() {
    override fun tearDown() {
        try { com.intellij.ide.util.PropertiesComponent.getInstance(project).unsetValue("gomoku.fontSize") }
        finally { super.tearDown() }
    }

    fun testControlGIsControlOnDefaultAndMacKeymaps() {
        val shortcut = KeyboardShortcut(KeyStroke.getKeyStroke("control G"), null)
        for (name in listOf("\$default", "Mac OS X", "Mac OS X 10.5+")) {
            val keymap = KeymapManager.getInstance().getKeymap(name) ?: error("Missing keymap: $name")
            assertTrue("Ctrl+G missing in $name", keymap.getShortcuts("Gomoku.Toggle").contains(shortcut))
        }
    }

    fun testTogglePreservesGameAndDocument() {
        myFixture.configureByText(PlainTextFileType.INSTANCE, "val cache = 1\n")
        val editor = myFixture.editor
        val session = project.getService(GomokuSession::class.java)
        val state = session.state
        val source = editor.document.text
        val toggle = ActionManager.getInstance().getAction("Gomoku.Toggle")
        repeat(3) {
            session.toggleBoard(editor)
            assertTrue(session.isBoardVisible)
            assertEquals(1, editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength).size)
            session.toggleBoard(editor)
            assertFalse(session.isBoardVisible)
            assertTrue(editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength).isEmpty())
            assertTrue(ActionUtil.getActions(editor.contentComponent).contains(toggle))
            assertSame(state, session.state)
            assertEquals(source, editor.document.text)
        }
    }

    fun testFontSizeResizesExistingInlayAndPersists() {
        myFixture.configureByText(PlainTextFileType.INSTANCE, "val cache = 1\n")
        val editor = myFixture.editor
        val session = project.getService(GomokuSession::class.java)
        assertEquals(14, session.fontSize)
        session.fontSize = 8
        session.showBoard(editor)
        val inlay = editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength).single()
        val width = inlay.widthInPixels
        val height = inlay.heightInPixels
        session.fontSize = 32
        assertTrue(inlay.widthInPixels > width)
        assertTrue(inlay.heightInPixels > height)
        assertEquals(32, PropertiesComponent.getInstance(project).getInt("gomoku.fontSize", 0))
        for (invalid in listOf(7, 33, Int.MAX_VALUE)) {
            try {
                session.fontSize = invalid
                fail("Invalid font size was accepted: $invalid")
            } catch (_: IllegalArgumentException) {
                assertEquals(32, session.fontSize)
            }
        }
        session.toggleBoard(editor)
        session.toggleBoard(editor)
        val restored = editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength).single()
        assertEquals(inlay.widthInPixels, restored.widthInPixels)
        assertEquals("val cache = 1\n", editor.document.text)
    }

    fun testClickingCodeKeepsArrayVisibleAndReleasesKeyboard() {
        myFixture.configureByText(PlainTextFileType.INSTANCE, "val cache = 1\n")
        val editor = myFixture.editor
        EditorTestUtil.setEditorVisibleSize(editor, 120, 50)
        val session = project.getService(GomokuSession::class.java)
        session.showBoard(editor)
        val inlay = editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength).single()
        val enter = ActionUtil.getActions(editor.contentComponent).single {
            it.shortcutSet.shortcuts.contains(KeyboardShortcut(KeyStroke.getKeyStroke("ENTER"), null))
        }
        fun keyboardEnabled(): Boolean {
            val event = AnActionEvent.createFromAnAction(enter, null, "test", DataContext.EMPTY_CONTEXT)
            enter.update(event)
            return event.presentation.isEnabled
        }
        fun click(x: Int, y: Int) {
            editor.contentComponent.dispatchEvent(MouseEvent(editor.contentComponent, MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), 0, x, y, x, y, 1, false, MouseEvent.BUTTON1))
        }
        assertTrue(keyboardEnabled())
        click(1, 1)
        assertTrue(session.isBoardVisible)
        assertTrue(inlay.isValid)
        assertFalse(keyboardEnabled())
        assertEquals("val cache = 1\n", editor.document.text)
        val bounds = inlay.bounds ?: error("Missing inlay bounds")
        click(bounds.x + 1, bounds.y + 1)
        assertTrue(keyboardEnabled())
        session.toggleBoard(editor)
        assertFalse(session.isBoardVisible)
    }
}
