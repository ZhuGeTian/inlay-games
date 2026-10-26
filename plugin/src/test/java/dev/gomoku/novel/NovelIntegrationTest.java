package dev.gomoku.novel;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.testFramework.EditorTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.xmlb.XmlSerializer;
import dev.gomoku.plugin.GomokuSession;
import java.lang.reflect.Field;
import java.util.List;

/** Uses only an in-memory, synthetic book; never imports or opens user novels/cache. */
public final class NovelIntegrationTest extends BasePlatformTestCase {
    private NovelReader reader;
    private NovelReader.ReaderState previousState;
    private Object previousBook;
    private NovelBook sample;

    @Override protected void setUp() throws Exception {
        super.setUp();
        myFixture.configureByText(PlainTextFileType.INSTANCE, "val source = 1\n");
        EditorTestUtil.setEditorVisibleSize(myFixture.getEditor(), 120, 50);
        reader = NovelReader.getInstance();
        previousState = reader.getState();
        previousBook = field(NovelReader.class, "book").get(reader);
        reader.setEnabled(false);
        sample = NovelBook.fromText("第一章 测试\n" + "这是为测试编写的示例文字。".repeat(80)
                + "\n第二章 测试\n" + "这是第二章的示例文字。".repeat(80));
        NovelReader.ReaderState state = new NovelReader.ReaderState();
        state.bookId = sample.id();
        state.title = "Synthetic test book";
        state.playing = false;
        state.enabled = false;
        reader.loadState(state);
        field(NovelReader.class, "book").set(reader, sample);
    }

    @Override protected void tearDown() throws Exception {
        try {
            if (reader != null) {
                reader.setEnabled(false);
                GomokuSession session = getProject().getServiceIfCreated(GomokuSession.class);
                if (session != null) session.hideBoard();
                field(NovelReader.class, "book").set(reader, previousBook);
                reader.loadState(previousState);
            }
        } finally { super.tearDown(); }
    }

    public void testRealInlayPagingAndChapterNavigationNeverEditDocument() {
        Editor editor = myFixture.getEditor();
        String source = editor.getDocument().getText();
        long stamp = editor.getDocument().getModificationStamp();
        reader.toggleVisibility(editor);
        assertEquals(1, blocks().size());
        assertTrue(blocks().get(0).getRenderer() instanceof NovelRenderer);
        ReadingWindow.Position start = reader.position();
        reader.turn(1);
        assertFalse(start.equals(reader.position()));
        reader.turn(-1);
        assertEquals(start, reader.position());
        int oldHeight = blocks().get(0).getHeightInPixels();
        reader.setDisplayLines(3);
        assertTrue(blocks().get(0).getHeightInPixels() > oldHeight);
        reader.selectChapter(sample.id(), 1);
        assertEquals(sample.chapters().get(1).page(), reader.page());
        assertEquals(source, editor.getDocument().getText());
        assertEquals(stamp, editor.getDocument().getModificationStamp());
    }

    public void testSharedShortcutRetainsHiddenModeAndConnectionAcrossGameSwitch() throws Exception {
        Editor editor = myFixture.getEditor();
        GomokuSession session = getProject().getService(GomokuSession.class);
        Field room = field(GomokuSession.class, "roomCode");
        Field connected = field(GomokuSession.class, "connected");
        Object oldRoom = room.get(session);
        Object oldConnected = connected.get(session);
        try {
            room.set(session, "TESTROOM");
            connected.set(session, true);
            session.showBoard(editor);
            assertTrue(session.isBoardVisible());
            reader.toggleVisibility(editor);
            assertFalse(session.isBoardVisible());
            assertEquals("TESTROOM", session.getRoomCode());
            assertTrue(session.getConnected());
            toggle();
            assertFalse(reader.isEnabled());
            assertTrue(reader.isSelected());
            assertTrue(blocks().isEmpty());
            toggle();
            assertTrue(reader.isEnabled());
            assertEquals(1, blocks().size());
            session.showBoard(editor);
            assertFalse(reader.isSelected());
            assertFalse(reader.isEnabled());
            toggle();
            assertFalse(session.isBoardVisible());
            toggle();
            assertTrue(session.isBoardVisible());
            assertFalse(reader.isEnabled());
            assertEquals(1, blocks().size());
            assertEquals("val source = 1\n", editor.getDocument().getText());
        } finally {
            room.set(session, oldRoom);
            connected.set(session, oldConnected);
        }
    }

    public void testReaderSettingsRoundTripKeepsHiddenSelectionAndProgress() {
        reader.toggleVisibility(myFixture.getEditor());
        reader.turn(1);
        reader.setDisplayLines(4);
        reader.setIntervalSeconds(19);
        reader.toggleVisibility(myFixture.getEditor());
        NovelReader.ReaderState before = reader.getState();
        NovelReader.ReaderState restored = XmlSerializer.deserialize(
                XmlSerializer.serialize(before), NovelReader.ReaderState.class);
        reader.loadState(restored);
        assertFalse(reader.isEnabled());
        assertTrue(reader.isSelected());
        assertTrue(blocks().isEmpty());
        assertEquals(4, reader.getDisplayLines());
        assertEquals(19, reader.getIntervalSeconds());
        assertEquals(before.positions, restored.positions);
        assertEquals(before.offsets, restored.offsets);
        toggle();
        assertTrue(reader.isEnabled());
        assertEquals(1, blocks().size());
    }

    public void testFixedPositionSurvivesCaretPagingLayoutHideAndDocumentEdits() throws Exception {
        Editor editor = myFixture.getEditor();
        editor.getCaretModel().moveToOffset(4);
        reader.toggleVisibility(editor);
        reader.setFollowCaret(false);
        editor.getCaretModel().moveToOffset(10);
        refresh(editor);
        assertEquals(4, blocks().get(0).getOffset());
        reader.turn(1);
        reader.setDisplayLines(3);
        assertEquals(4, blocks().get(0).getOffset());
        toggle();
        editor.getCaretModel().moveToOffset(0);
        toggle();
        assertEquals(4, blocks().get(0).getOffset());
        WriteCommandAction.runWriteCommandAction(getProject(), () -> editor.getDocument().insertString(0, "//x\n"));
        refresh(editor);
        assertEquals(8, blocks().get(0).getOffset());
        WriteCommandAction.runWriteCommandAction(getProject(), () -> editor.getDocument().deleteString(0, editor.getDocument().getTextLength()));
        refresh(editor);
        assertEquals(0, blocks().get(0).getOffset());
    }

    public void testFollowCaretMenuDefaultPersistenceAndNewDocumentAnchor() throws Exception {
        Editor editor = myFixture.getEditor();
        reader.toggleVisibility(editor);
        editor.getCaretModel().moveToOffset(6);
        refresh(editor);
        assertEquals(6, blocks().get(0).getOffset());
        ToggleAction action = (ToggleAction) ActionManager.getInstance().getAction("InlayGames.Novel.FollowCaret");
        AnActionEvent event = AnActionEvent.createFromAnAction(action, null, "test", DataContext.EMPTY_CONTEXT);
        assertTrue(action.isSelected(event));
        action.setSelected(event, false);
        assertFalse(action.isSelected(event));
        NovelReader.ReaderState restored = XmlSerializer.deserialize(XmlSerializer.serialize(reader.getState()), NovelReader.ReaderState.class);
        assertFalse(restored.followCaret);
        Editor other = EditorFactory.getInstance().createEditor(EditorFactory.getInstance().createDocument("another file"), getProject());
        try {
            EditorTestUtil.setEditorVisibleSize(other, 120, 50);
            other.getCaretModel().moveToOffset(6);
            refresh(other);
            assertEquals(6, other.getInlayModel().getBlockElementsInRange(0, 12).get(0).getOffset());
            other.getCaretModel().moveToOffset(7);
            refresh(other);
            assertEquals(6, other.getInlayModel().getBlockElementsInRange(0, 12).get(0).getOffset());
            action.setSelected(event, true);
            assertEquals(7, other.getInlayModel().getBlockElementsInRange(0, 12).get(0).getOffset());
            assertTrue(field(NovelReader.class, "fixedPosition").get(reader) == null);
        } finally {
            reader.setEnabled(false);
            EditorFactory.getInstance().releaseEditor(other);
        }
    }

    private void refresh(Editor editor) throws Exception {
        var method = NovelReader.class.getDeclaredMethod("render", Editor.class);
        method.setAccessible(true);
        method.invoke(reader, editor);
    }

    public void testSharedShortcutDisabledOutsideEditorAndReaderMenuRegistered() {
        AnAction action = ActionManager.getInstance().getAction("Gomoku.Toggle");
        AnActionEvent event = AnActionEvent.createFromAnAction(action, null, "test", DataContext.EMPTY_CONTEXT);
        action.update(event);
        assertFalse(event.getPresentation().isEnabled());
        assertNotNull(ActionManager.getInstance().getAction("InlayGames.Novel.Menu"));
        assertNotNull(ActionManager.getInstance().getAction("InlayGames.Novel.Import"));
    }

    private void toggle() {
        AnAction action = ActionManager.getInstance().getAction("Gomoku.Toggle");
        DataContext context = id -> CommonDataKeys.EDITOR.is(id) ? myFixture.getEditor()
                : CommonDataKeys.PROJECT.is(id) ? getProject() : null;
        AnActionEvent event = AnActionEvent.createFromAnAction(action, null, "test", context);
        action.update(event);
        assertTrue(event.getPresentation().isEnabled());
        action.actionPerformed(event);
    }

    private List<Inlay<?>> blocks() {
        Editor editor = myFixture.getEditor();
        return editor.getInlayModel().getBlockElementsInRange(0, editor.getDocument().getTextLength());
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
