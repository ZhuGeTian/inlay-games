package dev.gomoku.practice;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.testFramework.EditorTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.xmlb.XmlSerializer;
import dev.gomoku.novel.NovelReader;
import dev.gomoku.plugin.GomokuSession;
import java.awt.*;
import java.lang.reflect.Field;
import java.util.List;

/** Synthetic local fixture; no user PDF, cache, network or real game room. */
public final class PracticeIntegrationTest extends BasePlatformTestCase {
    private PracticeSession session;
    private PracticeSession.SavedState saved;
    private PracticePaper previous;
    private NovelReader.ReaderState novelSaved;
    @Override protected void setUp() throws Exception {
        super.setUp();
        myFixture.configureByText(PlainTextFileType.INSTANCE, "val source = 1\nval second = 2\n");
        EditorTestUtil.setEditorVisibleSize(myFixture.getEditor(), 80, 35);
        session = PracticeSession.getInstance();
        saved = session.getState(); previous = session.paper();
        novelSaved = NovelReader.getInstance().getState();
        session.deactivate();
        session.loadState(new PracticeSession.SavedState());
        session.accept(PracticePaper.parse(PracticePaperTest.sample()));
        getProject().getService(GomokuSession.class);
    }
    @Override protected void tearDown() throws Exception {
        try {
            session.deactivate();
            getProject().getService(GomokuSession.class).hideBoard();
            var field = PracticeSession.class.getDeclaredField("paper"); field.setAccessible(true); field.set(session, previous);
            session.loadState(saved);
            NovelReader.getInstance().loadState(novelSaved);
        } finally { super.tearDown(); }
    }
    private PracticeView view() {
        return (PracticeView) myFixture.getEditor().getInlayModel().getBlockElementsInRange(0,
                myFixture.getEditor().getDocument().getTextLength()).get(0).getRenderer();
    }
    private void click(String action) {
        var button = view().buttons().stream().filter(b -> b.action().equals(action) && b.enabled()).findFirst().orElseThrow();
        var box = button.bounds(); view().activateAt(box.x + 1, box.y + 1);
    }
    private void toggle() {
        var action = ActionManager.getInstance().getAction("Gomoku.Toggle");
        DataContext context = id -> CommonDataKeys.EDITOR.is(id) ? myFixture.getEditor() : CommonDataKeys.PROJECT.is(id) ? getProject() : null;
        var event = AnActionEvent.createFromAnAction(action, null, "test", context);
        action.update(event); assertTrue(event.getPresentation().isEnabled()); action.actionPerformed(event);
    }
    public void testClickSelectSubmitRetryAndNavigationNeverChangeSource() {
        Editor editor = myFixture.getEditor();
        String original = editor.getDocument().getText(); long stamp = editor.getDocument().getModificationStamp();
        session.show(editor);
        assertFalse(view().visibleText().contains("正确答案"));
        click("A"); assertEquals("A", session.progress().choices.get("sample-1"));
        assertFalse(view().visibleText().contains("正确答案"));
        click("submit"); assertTrue(view().visibleText().contains("正确答案 B"));
        assertEquals(List.of("sample-1"), session.progress().mistakes);
        click("retry"); assertFalse(view().visibleText().contains("正确答案"));
        click("B"); click("submit"); assertTrue(session.progress().result(session.question()).startsWith("正确"));
        click("next"); assertEquals(1, session.progress().index);
        click("previous"); assertEquals(0, session.progress().index);
        assertEquals(original, editor.getDocument().getText()); assertEquals(stamp, editor.getDocument().getModificationStamp());
    }
    // 未知答案不判分，缩小区域后也能分页读到解析提示。
    public void testRealOptionHitAndUnknownAnswerAndEmptyWrongFilter() {
        session.show(myFixture.getEditor());
        click("filter"); assertTrue(view().visibleText().contains("还没有错题"));
        click("filter");
        var box = view().optionHits().stream().filter(h -> h.action().equals("B")).findFirst().orElseThrow().bounds();
        view().activateAt(box.x + 2, box.y + 2); assertEquals("B", session.progress().choices.get("sample-1"));
        session.move(3); click("D"); click("submit");
        assertTrue(view().visibleText().contains("未提供答案")); assertTrue(session.progress().mistakes.isEmpty());
        StringBuilder resultPages = new StringBuilder(view().visibleText());
        while (view().pageIndex() + 1 < view().pageCount()) {
            click("page+"); resultPages.append(view().visibleText());
        }
        assertTrue(resultPages.toString().contains("未提供解析"));
    }
    public void testFixedAnchorCtrlGAndGameSwitchKeepsRoom() throws Exception {
        Editor editor = myFixture.getEditor();
        var game = getProject().getService(GomokuSession.class);
        Field room = GomokuSession.class.getDeclaredField("roomCode"); room.setAccessible(true);
        Object priorRoom = room.get(game);
        try {
            room.set(game, "TESTROOM"); game.showBoard(editor);
            session.show(editor); assertFalse(game.isBoardVisible()); assertEquals("TESTROOM", game.getRoomCode());
            int offset = blocks().get(0).getOffset();
            editor.getCaretModel().moveToOffset(editor.getDocument().getTextLength()); session.refresh();
            assertEquals(offset, blocks().get(0).getOffset());
            toggle(); assertFalse(session.isVisible()); assertTrue(session.isSelected());
            editor.getCaretModel().moveToOffset(0); toggle(); assertEquals(offset, blocks().get(0).getOffset());
            WriteCommandAction.runWriteCommandAction(getProject(), () -> editor.getDocument().insertString(0, "//x\n"));
            assertEquals(offset + 4, blocks().get(0).getOffset());
            game.showBoard(editor); assertFalse(session.isSelected()); assertFalse(session.isVisible());
            assertEquals("TESTROOM", game.getRoomCode());
            session.show(editor); NovelReader.getInstance().setEnabled(true);
            assertFalse(session.isSelected()); assertFalse(session.isVisible());
        } finally { room.set(game, priorRoom); NovelReader.getInstance().setEnabled(false); }
    }
    public void testPaginationFontImagesAndPainting() throws Exception {
        var json = PracticePaperTest.json();
        json.getAsJsonArray("questions").get(0).getAsJsonObject().addProperty("material", "分行测试🌌。".repeat(350));
        session.accept(PracticePaperTest.parse(json));
        session.show(myFixture.getEditor()); assertTrue(view().pageCount() > 2);
        StringBuilder read = new StringBuilder();
        int count = view().pageCount();
        for (int i = 0; i < count; i++) {
            read.append(view().visibleText().replace("\n", "").replace("// ", ""));
            if (i + 1 < count) click("page+");
        }
        assertTrue(read.toString().contains("分行测试🌌。".repeat(350)));
        assertFalse(read.toString().contains("正确答案"));
        assertEquals(count - 1, view().pageIndex());
        session.setFontSize(24); assertTrue(view().pageCount() >= count);
        click("B"); click("submit"); assertTrue(view().visibleText().contains("正确答案"));
        session.move(2);
        var inlay = blocks().get(0);
        var image = new java.awt.image.BufferedImage(inlay.getWidthInPixels(), inlay.getHeightInPixels(), java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try { view().paint(inlay, (Graphics) graphics, new Rectangle(image.getWidth(), image.getHeight()), null); }
        finally { graphics.dispose(); }
        assertTrue(image.getWidth() > 100); assertNotNull(ActionManager.getInstance().getAction("Moyu.Practice.Import"));
    }
    public void testProgressPersistenceAndDifferentPaperIsolation() throws Exception {
        var first = session.paper();
        session.show(myFixture.getEditor()); session.choose("A"); session.submit(); session.move(1); session.setFontSize(19);
        var serialized = XmlSerializer.deserialize(XmlSerializer.serialize(session.getState()), PracticeSession.SavedState.class);
        session.loadState(serialized);
        assertEquals(1, session.progress().index); assertEquals("A", session.progress().submitted.get("sample-1"));
        var json = PracticePaperTest.json(); json.addProperty("title", "Different paper");
        session.accept(PracticePaperTest.parse(json)); assertEquals(0, session.progress().index); assertTrue(session.progress().submitted.isEmpty());
        session.accept(first); assertEquals(1, session.progress().index); assertEquals(19, session.getFontSize());
        assertEquals(List.of("sample-1"), session.progress().mistakes);
        var copy = session.getState(); copy.progress.get(first.id()).index = 0; assertEquals(1, session.progress().index);
    }
    public void testEditorReleaseWhileHiddenDropsAnchor() throws Exception {
        Editor other = EditorFactory.getInstance().createEditor(EditorFactory.getInstance().createDocument("other"), getProject());
        try { session.show(other); session.hide(); } finally { EditorFactory.getInstance().releaseEditor(other); }
        session.show(myFixture.getEditor()); assertEquals(1, blocks().size());
    }
    public void testBackgroundImportCachesExactBytesAndRestoresProgress() throws Exception {
        var json = PracticePaperTest.json(); json.addProperty("title", "Temporary import " + java.util.UUID.randomUUID());
        byte[] bytes = json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var imported = PracticePaper.parse(bytes);
        var source = java.nio.file.Files.createTempFile("moyu-practice-test-", ".json");
        var cache = PracticeSession.directory().resolve(imported.id() + ".json");
        try {
            java.nio.file.Files.write(source, bytes);
            session.importFile(source, getProject(), myFixture.getEditor()); awaitLoad();
            assertEquals(imported.id(), session.paper().id()); assertTrue(session.isVisible());
            assertTrue(java.util.Arrays.equals(bytes, java.nio.file.Files.readAllBytes(cache)));
            session.choose("A"); session.submit(); session.move(1); session.hide();
            session.openPaper(imported.id(), getProject(), myFixture.getEditor()); awaitLoad();
            assertEquals(1, session.progress().index); assertEquals("A", session.progress().submitted.get("sample-1"));
            assertTrue(java.util.Arrays.equals(bytes, java.nio.file.Files.readAllBytes(source)));
            // Changing modes while an import is queued must not reopen practice when it finishes.
            session.importFile(source, getProject(), myFixture.getEditor());
            session.deactivate(); awaitLoad(); assertFalse(session.isVisible()); assertFalse(session.isSelected());
        } finally { java.nio.file.Files.deleteIfExists(source); java.nio.file.Files.deleteIfExists(cache); }
    }
    public void testBooleanControlsAndMultiChoiceClicks() throws Exception {
        var json = PracticePaperTest.json(); var questions = json.getAsJsonArray("questions");
        var bool = questions.get(0).getAsJsonObject(); bool.addProperty("type", "boolean");
        bool.getAsJsonArray("options").remove(3); bool.getAsJsonArray("options").remove(2);
        var multi = questions.get(1).getAsJsonObject(); multi.addProperty("type", "multiple"); multi.addProperty("answer", "BD");
        session.accept(PracticePaperTest.parse(json)); session.show(myFixture.getEditor());
        assertTrue(view().buttons().stream().noneMatch(b -> b.action().equals("C") || b.action().equals("D")));
        click("B"); click("submit"); assertTrue(session.progress().result(session.question()).startsWith("正确"));
        click("next"); click("D"); click("B"); assertEquals("BD", session.progress().choices.get(session.question().id()));
        click("D"); assertEquals("B", session.progress().choices.get(session.question().id()));
        click("D"); click("submit"); assertTrue(session.progress().result(session.question()).startsWith("正确"));
    }
    public void testBundledPapersLoadSwitchAndRestoreOwnProgress() throws Exception {
        var entries = PracticeCatalog.bundled();
        if (entries.isEmpty()) return; // Public builds have no user-supplied banks.
        var idsBefore = new java.util.HashSet<>(session.getState().titles.keySet());
        var created = new java.util.ArrayList<java.nio.file.Path>();
        try {
            for (var entry : entries) {
                var cache = PracticeSession.directory().resolve(entry.id() + ".json");
                if (!java.nio.file.Files.exists(cache)) created.add(cache);
                session.openBundled(entry.id(), getProject(), myFixture.getEditor()); awaitLoad();
                assertEquals(entry.id(), session.paper().id()); assertEquals(entry.count(), session.paper().questions().size());
                assertEquals(0, session.progress().index);
                session.choose("A"); session.submit(); assertTrue(session.progress().result(session.question()).contains("未提供答案"));
                session.move(8); click("B"); click("D"); click("submit");
                assertEquals("BD", session.progress().submitted.get(session.question().id()));
                assertTrue(session.progress().mistakes.isEmpty());
            }
            var first = entries.get(0); session.openBundled(first.id(), getProject(), myFixture.getEditor()); awaitLoad();
            assertEquals(8, session.progress().index); assertEquals("BD", session.progress().submitted.get(session.question().id()));
            assertEquals(entries.size() + idsBefore.size(), session.getState().titles.size());
            assertEquals(entries.size() + idsBefore.size(), PracticeCatalog.entries(session.getState()).size());
        } finally { for (var cache : created) java.nio.file.Files.deleteIfExists(cache); }
    }
    // 缩小正文后仍能读完长材料、作答，且不会改动源文件。
    public void testVisibleLinesShrinkWithoutLosingContentOrAnswers() throws Exception {
        var json = PracticePaperTest.json();
        String material = "逐行阅读测试🌌。".repeat(80);
        json.getAsJsonArray("questions").get(0).getAsJsonObject().addProperty("material", material);
        session.accept(PracticePaperTest.parse(json));
        Editor editor = myFixture.getEditor();
        String source = editor.getDocument().getText();
        long stamp = editor.getDocument().getModificationStamp();
        session.show(editor);
        int height = blocks().get(0).getHeightInPixels(), count = view().pageCount();
        click("B"); click("page+");
        session.setVisibleLines(1);
        assertTrue(blocks().get(0).getHeightInPixels() < height);
        assertTrue(view().pageCount() > count);
        assertEquals(0, view().pageIndex());
        assertEquals("B", session.progress().choices.get(session.question().id()));
        StringBuilder text = new StringBuilder();
        for (int i = 0, pages = view().pageCount(); i < pages; i++) {
            assertEquals(1, view().visibleText().lines().filter(line -> !line.isEmpty()).count());
            text.append(view().visibleText().replace("\n", "").replace("// ", ""));
            if (i + 1 < pages) click("page+");
        }
        assertTrue(text.toString().contains(material));
        click("submit");
        assertEquals("B", session.progress().submitted.get(session.question().id()));
        assertTrue(session.progress().result(session.question()).startsWith("正确"));
        session.setVisibleLines(3);
        assertEquals(0, session.progress().index);
        assertEquals("B", session.progress().submitted.get(session.question().id()));
        assertEquals(source, editor.getDocument().getText());
        assertEquals(stamp, editor.getDocument().getModificationStamp());
        assertNotNull(ActionManager.getInstance().getAction("Moyu.Practice.VisibleLines"));
    }
    // 行数设置可序列化恢复，旧配置和损坏配置使用紧凑默认值。
    public void testVisibleLinesPersistenceAndInvalidValues() {
        assertEquals(6, session.getVisibleLines());
        session.setVisibleLines(3);
        var savedState = session.getState(); savedState.visibleLines = 12;
        assertEquals(3, session.getVisibleLines());
        var serialized = XmlSerializer.deserialize(XmlSerializer.serialize(session.getState()), PracticeSession.SavedState.class);
        session.setVisibleLines(20); session.loadState(serialized);
        assertEquals(3, session.getVisibleLines());
        session.loadState(XmlSerializer.deserialize(new org.jdom.Element("state"), PracticeSession.SavedState.class));
        assertEquals(6, session.getVisibleLines());
        for (int invalid : new int[]{-1, 0, 21, Integer.MAX_VALUE}) {
            try { session.setVisibleLines(invalid); fail("Invalid line count accepted"); }
            catch (IllegalArgumentException expected) { assertEquals(6, session.getVisibleLines()); }
            var damaged = new PracticeSession.SavedState(); damaged.visibleLines = invalid;
            session.loadState(damaged); assertEquals(6, session.getVisibleLines());
        }
    }
    // 最小正文高度下图片仍可绘制，底部按钮不会被裁掉。
    public void testOneLineImagePaginationAndFooterBounds() {
        session.setVisibleLines(1); session.show(myFixture.getEditor()); session.move(2);
        boolean sawImage = false;
        for (int i = 0, pages = view().pageCount(); i < pages; i++) {
            sawImage |= view().visibleText().contains("[图片]");
            var inlay = blocks().get(0);
            var image = new java.awt.image.BufferedImage(inlay.getWidthInPixels(), inlay.getHeightInPixels(), java.awt.image.BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            try { view().paint(inlay, (Graphics) graphics, new Rectangle(image.getWidth(), image.getHeight()), null); }
            finally { graphics.dispose(); }
            for (var button : view().buttons()) assertTrue(button.bounds().y + button.bounds().height <= image.getHeight());
            if (i + 1 < pages) click("page+");
        }
        assertTrue(sawImage);
        click("B"); click("submit"); assertTrue(session.progress().submitted.containsKey(session.question().id()));
    }
    // 生成真实注册动作的编辑器上下文，检查快捷键与右键菜单共用的逻辑。
    private AnActionEvent practiceEvent(String id, Editor editor) {
        var action = ActionManager.getInstance().getAction(id);
        DataContext context = key -> CommonDataKeys.EDITOR.is(key) ? editor : CommonDataKeys.PROJECT.is(key) ? getProject() : null;
        return AnActionEvent.createFromAnAction(action, null, "test", context);
    }
    // 隐藏辅助区后正文和页码不变，旧按钮区域失效，恢复时回到原布局。
    public void testDetailsToggleShrinksAndPreservesCurrentPage() {
        session.setVisibleLines(2);
        Editor editor = myFixture.getEditor();
        String source = editor.getDocument().getText(); long stamp = editor.getDocument().getModificationStamp();
        session.show(editor); click("B"); click("page+");
        int page = view().pageIndex(), pages = view().pageCount(), height = blocks().get(0).getHeightInPixels();
        String body = view().visibleText();
        var next = view().buttons().stream().filter(b -> b.action().equals("next")).findFirst().orElseThrow().bounds();
        var action = ActionManager.getInstance().getAction("Moyu.Practice.Details");
        var event = practiceEvent("Moyu.Practice.Details", editor);
        action.update(event); assertTrue(event.getPresentation().isEnabled()); action.actionPerformed(event);
        assertFalse(session.isDetailsVisible()); assertTrue(view().buttons().isEmpty());
        assertTrue(blocks().get(0).getHeightInPixels() < height);
        assertEquals(page, view().pageIndex()); assertEquals(pages, view().pageCount()); assertEquals(body, view().visibleText());
        view().activateAt(next.x + 1, next.y + 1); assertEquals(0, session.progress().index);
        assertEquals("B", session.progress().choices.get(session.question().id()));
        action.actionPerformed(event);
        assertTrue(session.isDetailsVisible()); assertFalse(view().buttons().isEmpty());
        assertEquals(height, blocks().get(0).getHeightInPixels()); assertEquals(body, view().visibleText());
        assertEquals(source, editor.getDocument().getText()); assertEquals(stamp, editor.getDocument().getModificationStamp());
    }
    // 隐藏后选项命中随正文上移，右键仍能提交和翻页，设置可恢复。
    public void testHiddenDetailsOptionsMenusAndPersistence() {
        Editor editor = myFixture.getEditor(); session.show(editor);
        int oldY = view().optionHits().stream().filter(b -> b.action().equals("B")).findFirst().orElseThrow().bounds().y;
        session.toggleDetails();
        var box = view().optionHits().stream().filter(b -> b.action().equals("B")).findFirst().orElseThrow().bounds();
        assertTrue(box.y < oldY); view().activateAt(box.x + 1, box.y + 1);
        ActionManager.getInstance().getAction("Moyu.Practice.Submit").actionPerformed(practiceEvent("Moyu.Practice.Submit", editor));
        assertEquals("B", session.progress().submitted.get(session.question().id()));
        var serialized = XmlSerializer.deserialize(XmlSerializer.serialize(session.getState()), PracticeSession.SavedState.class);
        session.loadState(serialized); assertFalse(session.isDetailsVisible());
        toggle(); assertFalse(session.isVisible()); toggle(); assertTrue(session.isVisible()); assertTrue(view().buttons().isEmpty());
        session.retry(); session.setVisibleLines(1);
        var previous = ActionManager.getInstance().getAction("Moyu.Practice.PreviousPage");
        var prevEvent = practiceEvent("Moyu.Practice.PreviousPage", editor);
        previous.update(prevEvent); assertFalse(prevEvent.getPresentation().isEnabled());
        var next = ActionManager.getInstance().getAction("Moyu.Practice.NextPage");
        var nextEvent = practiceEvent("Moyu.Practice.NextPage", editor);
        next.update(nextEvent); assertTrue(nextEvent.getPresentation().isEnabled()); next.actionPerformed(nextEvent);
        assertEquals(1, view().pageIndex()); previous.actionPerformed(prevEvent); assertEquals(0, view().pageIndex());
        while (session.canMovePage(1)) next.actionPerformed(nextEvent);
        next.update(nextEvent); assertFalse(nextEvent.getPresentation().isEnabled());
        assertTrue(view().buttons().isEmpty());
        session.loadState(XmlSerializer.deserialize(new org.jdom.Element("state"), PracticeSession.SavedState.class));
        assertTrue(session.isDetailsVisible());
    }
    // 局部 Ctrl+D 优先级仅在刷题视图存活时生效，其他编辑器不会触发。
    public void testDetailsShortcutScopeAndDisposal() {
        Editor editor = myFixture.getEditor();
        var action = ActionManager.getInstance().getAction("Moyu.Practice.Details");
        assertTrue(java.util.Arrays.stream(action.getShortcutSet().getShortcuts()).anyMatch(s ->
                s instanceof KeyboardShortcut key && key.getFirstKeyStroke().equals(javax.swing.KeyStroke.getKeyStroke("control D"))));
        var event = practiceEvent("Moyu.Practice.Details", editor);
        action.update(event); assertFalse(event.getPresentation().isEnabled());
        session.show(editor);
        var nonEditorEvent = practiceEvent("Moyu.Practice.Details", null);
        action.update(nonEditorEvent); assertFalse(nonEditorEvent.getPresentation().isEnabled());
        action.actionPerformed(nonEditorEvent); assertTrue(session.isDetailsVisible());
        assertTrue(com.intellij.openapi.actionSystem.ex.ActionUtil.getActions(editor.getContentComponent()).contains(action));
        Editor other = EditorFactory.getInstance().createEditor(EditorFactory.getInstance().createDocument("other"), getProject());
        try {
            var otherEvent = practiceEvent("Moyu.Practice.Details", other);
            action.update(otherEvent); assertFalse(otherEvent.getPresentation().isEnabled());
            action.actionPerformed(otherEvent); assertTrue(session.isDetailsVisible());
        } finally { EditorFactory.getInstance().releaseEditor(other); }
        session.hide();
        action.update(event); assertFalse(event.getPresentation().isEnabled());
        assertFalse(com.intellij.openapi.actionSystem.ex.ActionUtil.getActions(editor.getContentComponent()).contains(action));
        session.show(editor); assertTrue(com.intellij.openapi.actionSystem.ex.ActionUtil.getActions(editor.getContentComponent()).contains(action));
        NovelReader.getInstance().setEnabled(true);
        action.update(event); assertFalse(event.getPresentation().isEnabled());
        assertFalse(com.intellij.openapi.actionSystem.ex.ActionUtil.getActions(editor.getContentComponent()).contains(action));
    }
    // 板块模式可直接翻答案，长题段落完整可读，答案阶段不夹带题目。
    public void testSectionCardsAndLongContentKeepNextPageMeaning() throws Exception {
        var json = PracticePaperTest.json(); var qs = json.getAsJsonArray("questions");
        String material = "板块长材料。".repeat(70);
        qs.get(0).getAsJsonObject().addProperty("section", "言语理解");
        qs.get(0).getAsJsonObject().addProperty("material", material);
        qs.get(3).getAsJsonObject().addProperty("section", "言语理解");
        session.accept(PracticePaperTest.parse(json)); session.selectSection("言语理解");
        session.setVisibleLines(2); session.show(myFixture.getEditor());
        assertFalse(view().visibleText().contains("正确答案")); assertTrue(view().pageCount() > 2);
        StringBuilder read = new StringBuilder();
        for (int i = 0, n = view().pageCount(); i < n; i++) {
            read.append(view().visibleText().replace("\n", "").replace("// ", ""));
            if (i + 1 < n) click("segment+");
        }
        assertTrue(read.toString().contains(material)); assertFalse(session.progress().answerVisible);
        click("page+"); assertTrue(session.progress().answerVisible); assertEquals(0, session.progress().index);
        assertTrue(view().visibleText().contains("正确答案：B")); assertFalse(view().visibleText().contains("板块长材料"));
        assertTrue(session.progress().submitted.isEmpty()); assertTrue(session.progress().mistakes.isEmpty());
        click("page+"); assertEquals(3, session.progress().index); assertFalse(session.progress().answerVisible);
        click("page+"); assertTrue(view().visibleText().contains("未提供答案")); assertFalse(session.canMovePage(1));
        assertTrue(session.progress().mistakes.isEmpty());
        session.toggleDetails(); assertTrue(view().buttons().isEmpty());
        ActionManager.getInstance().getAction("Moyu.Practice.PreviousPage").actionPerformed(practiceEvent("Moyu.Practice.PreviousPage", myFixture.getEditor()));
        assertFalse(session.progress().answerVisible); assertEquals(3, session.progress().index);
    }
    // 各板块与整卷分别保存作答和答案阶段，切换/重启不会覆盖整卷进度。
    public void testSectionProgressIsolatedAndRestored() throws Exception {
        var json = PracticePaperTest.json(); var qs = json.getAsJsonArray("questions");
        qs.get(0).getAsJsonObject().addProperty("section", "政治理论");
        qs.get(1).getAsJsonObject().addProperty("section", "常识");
        session.accept(PracticePaperTest.parse(json)); session.show(myFixture.getEditor());
        session.move(2); session.choose("A");
        session.selectSection("政治理论"); assertEquals(0, session.progress().index);
        click("A"); click("page+"); assertTrue(session.progress().result(session.question()).startsWith("错误"));
        session.selectSection("常识"); assertEquals(1, session.progress().index); assertFalse(session.progress().answerVisible);
        assertTrue(session.progress().submitted.isEmpty());
        session.selectSection("政治理论"); assertTrue(session.progress().answerVisible);
        assertEquals("A", session.progress().submitted.get(session.question().id()));
        var serialized = XmlSerializer.deserialize(XmlSerializer.serialize(session.getState()), PracticeSession.SavedState.class);
        session.selectSection(""); assertEquals(2, session.progress().index); assertEquals("A", session.progress().choices.get(session.question().id()));
        session.loadState(serialized); session.show(myFixture.getEditor());
        assertEquals("政治理论", session.getSection()); assertTrue(session.progress().answerVisible);
        assertTrue(view().visibleText().contains("正确答案"));
        session.selectSection("言语理解"); assertEquals("政治理论", session.getSection());
        assertNotNull(ActionManager.getInstance().getAction("Moyu.Practice.Sections"));
    }
    private void awaitLoad() {
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (session.isLoading() && System.nanoTime() < deadline) {
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
            java.util.concurrent.locks.LockSupport.parkNanos(10_000_000L);
        }
        assertFalse("Import did not finish", session.isLoading());
    }
    private List<Inlay<?>> blocks() { return myFixture.getEditor().getInlayModel().getBlockElementsInRange(0, myFixture.getEditor().getDocument().getTextLength()); }
}
