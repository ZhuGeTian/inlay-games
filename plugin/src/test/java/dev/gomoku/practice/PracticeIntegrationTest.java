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
        session.toggleDetails(); // Legacy button tests explicitly show the optional controls.
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
            click("segment+"); resultPages.append(view().visibleText());
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
        session.show(myFixture.getEditor()); assertEquals(1, view().pageCount());
        StringBuilder read = new StringBuilder();
        int count = view().pageCount();
        for (int i = 0; i < count; i++) {
            read.append(view().visibleText().replace("\n", "").replace("// ", ""));
            if (i + 1 < count) click("page+");
        }
        assertTrue(read.toString().contains("分行测试🌌。".repeat(350)));
        assertFalse(read.toString().contains("正确答案"));
        assertEquals(count - 1, view().pageIndex());
        int previousHeight = blocks().get(0).getHeightInPixels();
        session.setFontSize(24); assertEquals(1, view().pageCount()); assertTrue(blocks().get(0).getHeightInPixels() > previousHeight);
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
                String firstAnswer = session.question().answer();
                session.choose(firstAnswer); session.submit(); assertTrue(session.progress().result(session.question()).startsWith("正确"));
                session.move(8);
                String answer = session.question().answer();
                for (char letter : answer.toCharArray()) session.choose(String.valueOf(letter));
                session.submit(); assertEquals(answer, session.progress().submitted.get(session.question().id()));
                assertTrue(session.progress().mistakes.isEmpty());
            }
            var first = entries.get(0); session.openBundled(first.id(), getProject(), myFixture.getEditor()); awaitLoad();
            assertEquals(8, session.progress().index); assertEquals("BD", session.progress().submitted.get(session.question().id()));
            assertEquals(entries.size() + idsBefore.size(), session.getState().titles.size());
            assertEquals(entries.size() + idsBefore.size(), PracticeCatalog.entries(session.getState()).size());
        } finally { for (var cache : created) java.nio.file.Files.deleteIfExists(cache); }
    }
    // 缩小正文后仍能读完长材料、作答，且不会改动源文件。
    public void testVisibleLinesNeverTruncateQuestionButStillPaginateAnswers() throws Exception {
        var json = PracticePaperTest.json(); var q = json.getAsJsonArray("questions").get(0).getAsJsonObject();
        String material = "完整材料测试🌌。".repeat(80), explanation = "长解析仍分页。".repeat(40);
        q.addProperty("material", material); q.addProperty("explanation", explanation);
        session.accept(PracticePaperTest.parse(json)); Editor editor = myFixture.getEditor();
        String source = editor.getDocument().getText(); long stamp = editor.getDocument().getModificationStamp();
        session.show(editor); int height = blocks().get(0).getHeightInPixels();
        session.setVisibleLines(1);
        assertEquals(height, blocks().get(0).getHeightInPixels()); assertEquals(1, view().pageCount());
        assertTrue(view().visibleText().replace("\n", "").replace("// ", "").contains(material));
        assertEquals(4, view().optionHits().stream().map(PracticeView.Button::action).distinct().count());
        click("B"); click("submit"); assertTrue(session.progress().answerVisible); assertTrue(view().pageCount() > 1);
        StringBuilder text = new StringBuilder();
        for (int i = 0, pages = view().pageCount(); i < pages; i++) {
            assertEquals(1, view().visibleText().lines().filter(line -> !line.isEmpty()).count());
            text.append(view().visibleText().replace("\n", "").replace("// ", ""));
            if (i + 1 < pages) click("segment+");
        }
        assertTrue(text.toString().contains(explanation));
        session.setVisibleLines(3); assertEquals(0, session.progress().index);
        session.movePage(-1); assertFalse(session.progress().answerVisible); assertEquals(1, view().pageCount());
        assertTrue(view().visibleText().replace("\n", "").replace("// ", "").contains(material));
        assertEquals("B", session.progress().submitted.get(session.question().id()));
        assertEquals(source, editor.getDocument().getText()); assertEquals(stamp, editor.getDocument().getModificationStamp());
    }
    // 行数设置可序列化恢复，非法值保持原来的边界检查。
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
        assertTrue(session.progress().answerVisible); previous.actionPerformed(prevEvent); assertFalse(session.progress().answerVisible);
        while (session.canMovePage(1)) next.actionPerformed(nextEvent);
        next.update(nextEvent); assertFalse(nextEvent.getPresentation().isEnabled());
        assertTrue(view().buttons().isEmpty());
        session.loadState(XmlSerializer.deserialize(new org.jdom.Element("state"), PracticeSession.SavedState.class));
        assertFalse(session.isDetailsVisible());
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
        assertFalse(view().visibleText().contains("正确答案")); assertEquals(1, view().pageCount());
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
    // 隐藏、切换板块和持久化恢复均继续原段落及答案阶段。
    public void testResumePageAcrossHideSectionAndRestart() throws Exception {
        var json = PracticePaperTest.json(); var q = json.getAsJsonArray("questions").get(0).getAsJsonObject();
        q.addProperty("section", "言语理解"); q.addProperty("material", "续做材料。".repeat(80));
        q.addProperty("explanation", "续做解析。".repeat(80));
        var paper = PracticePaperTest.parse(json); session.accept(paper); session.setVisibleLines(2); session.show(myFixture.getEditor());
        session.movePage(1); session.moveSegment(2); String text = view().visibleText(); assertEquals(2, view().pageIndex());
        session.hide(); session.show(myFixture.getEditor()); assertEquals(2, view().pageIndex()); assertEquals(text, view().visibleText());
        session.selectSection("言语理解"); assertEquals(0, view().pageIndex());
        session.movePage(1); assertTrue(session.progress().answerVisible); session.moveSegment(2);
        String answerText = view().visibleText(); assertEquals(2, view().pageIndex());
        session.selectSection(""); assertEquals(2, view().pageIndex()); assertEquals(text, view().visibleText());
        session.selectSection("言语理解"); assertEquals(answerText, view().visibleText());
        var state = XmlSerializer.deserialize(XmlSerializer.serialize(session.getState()), PracticeSession.SavedState.class);
        session.hide(); session.loadState(state); session.accept(paper); session.show(myFixture.getEditor());
        assertTrue(session.progress().answerVisible); assertEquals(2, view().pageIndex()); assertEquals(answerText, view().visibleText());
        session.movePage(-1); assertFalse(session.progress().answerVisible); assertEquals(0, view().pageIndex());
    }
    // 实际绘制必须保持灰色，仅选中选项使用粗体。
    public void testSelectedOptionOnlyUsesBoldWithoutColorOrLabelChange() {
        var editor = myFixture.getEditor(); var scheme = editor.getColorsScheme();
        var oldComment = scheme.getAttributes(com.intellij.openapi.editor.DefaultLanguageHighlighterColors.LINE_COMMENT).clone();
        try {
            scheme.setAttributes(com.intellij.openapi.editor.DefaultLanguageHighlighterColors.LINE_COMMENT,
                    new com.intellij.openapi.editor.markup.TextAttributes(Color.GRAY, null, null, null, Font.PLAIN));
            session.show(editor); String before = view().visibleText(); click("A");
            assertEquals(before, view().visibleText()); assertTrue(view().optionFont("A").isBold()); assertFalse(view().optionFont("B").isBold());
            session.toggleDetails();
            var inlay = blocks().get(0); var image = new java.awt.image.BufferedImage(inlay.getWidthInPixels(), inlay.getHeightInPixels(), java.awt.image.BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            try { view().paint(inlay, (Graphics) graphics, new Rectangle(image.getWidth(), image.getHeight()), null); } finally { graphics.dispose(); }
            Color background = scheme.getDefaultBackground(); int ink = 0;
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
                Color pixel = new Color(image.getRGB(x, y));
                if (!pixel.equals(background)) {
                    // 灰色文字与背景混合后的色差不能超出背景本身；橙色选中会超出。
                    int spread = Math.max(pixel.getRed(), Math.max(pixel.getGreen(), pixel.getBlue())) - Math.min(pixel.getRed(), Math.min(pixel.getGreen(), pixel.getBlue()));
                    int backgroundSpread = Math.max(background.getRed(), Math.max(background.getGreen(), background.getBlue())) - Math.min(background.getRed(), Math.min(background.getGreen(), background.getBlue()));
                    assertTrue(spread <= backgroundSpread + 1); ink++;
                }
            }
            assertTrue(ink > 10);
        } finally { scheme.setAttributes(com.intellij.openapi.editor.DefaultLanguageHighlighterColors.LINE_COMMENT, oldComment); }
    }
    // 错题入口能从别的试卷跳回原卷原板块，重做正确仍保留历史。
    public void testOpenGlobalMistakeReturnsToOriginalPaperAndSection() throws Exception {
        var json = PracticePaperTest.json(); json.addProperty("title", "错题跳转测试" + System.nanoTime());
        json.getAsJsonArray("questions").get(0).getAsJsonObject().addProperty("section", "政治理论");
        byte[] bytes = json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8); var paper = PracticePaper.parse(bytes);
        var cache = PracticeSession.directory().resolve(paper.id() + ".json");
        java.nio.file.Files.createDirectories(cache.getParent()); java.nio.file.Files.write(cache, bytes);
        try {
            session.accept(paper); session.selectSection("政治理论"); session.show(myFixture.getEditor()); click("A"); click("page+");
            var entry = PracticeMistakes.entries(session.getState(), paper).get(0);
            session.accept(PracticePaper.parse(PracticePaperTest.sample())); session.show(myFixture.getEditor());
            session.openMistake(entry, getProject(), myFixture.getEditor()); awaitLoad();
            assertEquals(paper.id(), session.paper().id()); assertEquals("政治理论", session.getSection());
            assertFalse(session.progress().answerVisible); assertTrue(session.progress().onlyWrong); assertEquals(0, session.progress().index);
            click("retry"); click("B"); click("page+"); assertTrue(session.progress().result(session.question()).startsWith("正确"));
            assertEquals(1, PracticeMistakes.entries(session.getState(), paper).size());
            assertNotNull(ActionManager.getInstance().getAction("Moyu.Practice.Mistakes"));
        } finally { java.nio.file.Files.deleteIfExists(cache); }
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
    // 新旧配置首次均隐藏辅助区，手动恢复后的偏好不被下次加载覆盖。
    public void testCompactDefaultMigrationAndMinimalContextMenu() throws Exception {
        var old = new PracticeSession.SavedState(); old.detailsVisible = true;
        session.loadState(old); assertFalse(session.isDetailsVisible());
        session.show(myFixture.getEditor()); assertTrue(view().buttons().isEmpty());
        assertEquals("Moyu.Practice.ContextMenu", view().getContextMenuGroupId(blocks().get(0)));
        var group = (ActionGroup) ActionManager.getInstance().getAction("Moyu.Practice.ContextMenu");
        var names = java.util.Arrays.stream(group.getChildren(null)).map(a -> ActionManager.getInstance().getId(a)).toList();
        assertEquals(List.of("Moyu.Practice.PreviousPage", "Moyu.Practice.NextPage", "Moyu.Practice.A", "Moyu.Practice.B",
                "Moyu.Practice.C", "Moyu.Practice.D", "Moyu.Practice.Submit", "Moyu.Practice.Here", "Moyu.Practice.Hide"), names);
        session.choose("B"); session.submit();
        names = java.util.Arrays.stream(group.getChildren(null)).map(a -> ActionManager.getInstance().getId(a)).toList();
        assertTrue(names.contains("Moyu.Practice.Retry")); assertFalse(names.contains("Moyu.Practice.A"));
        session.toggleDetails();
        var state = XmlSerializer.deserialize(XmlSerializer.serialize(session.getState()), PracticeSession.SavedState.class);
        session.loadState(state); assertTrue(session.isDetailsVisible());
        assertNotNull(ActionManager.getInstance().getAction("Moyu.Practice.VisibleLines"));
    }
    // 同行选项的点击互不串位，缩窄或加大字体后自动换行且不裁剪题目。
    public void testCompactOptionsIndependentHitsAndNarrowWrapping() {
        session.setFontSize(12); session.show(myFixture.getEditor());
        var hits = view().optionHits(); assertEquals(4, hits.size());
        assertEquals(1, hits.stream().map(h -> h.bounds().y).distinct().count());
        for (String letter : List.of("A", "B", "C", "D")) {
            var box = view().optionHits().stream().filter(h -> h.action().equals(letter)).findFirst().orElseThrow().bounds();
            view().activateAt(box.x + 1, box.y + 1); assertEquals(letter, session.progress().choices.get(session.question().id()));
            assertEquals(1, view().pageCount());
        }
        int before = blocks().get(0).getHeightInPixels();
        EditorTestUtil.setEditorVisibleSize(myFixture.getEditor(), 18, 35); session.setFontSize(32); session.refresh();
        assertTrue(blocks().get(0).getHeightInPixels() > before); assertEquals(1, view().pageCount());
        assertEquals(4, view().optionHits().stream().map(PracticeView.Button::action).distinct().count());
        for (var hit : view().optionHits()) {
            assertTrue(hit.bounds().x >= 0); assertTrue(hit.bounds().x + hit.bounds().width <= blocks().get(0).getWidthInPixels());
            assertTrue(hit.bounds().y + hit.bounds().height <= blocks().get(0).getHeightInPixels());
        }
    }
    // 实际截图对应题保留身份并显示横线，绘制结果供人工检查。
    public void testRealFillBlankQuestionIsCompleteAndRenderPreview() throws Exception {
        var entry = PracticeCatalog.bundled().stream().filter(e -> e.title().startsWith("2026")).findFirst();
        if (entry.isEmpty()) return;
        try (var input = PracticeCatalog.open(entry.get().id())) { session.accept(PracticePaper.parse(PracticePaper.readBytes(input))); }
        session.toggleDetails(); session.setFontSize(14); session.setVisibleLines(1); session.move(15); session.show(myFixture.getEditor());
        assertEquals(entry.get().id(), session.paper().id()); assertTrue(view().visibleText().contains("既不____"));
        assertEquals(1, view().pageCount()); assertTrue(view().buttons().isEmpty());
        assertEquals(4, view().optionHits().size()); assertEquals(1, view().optionHits().stream().map(h -> h.bounds().y).distinct().count());
        session.choose("C"); assertTrue(view().optionFont("C").isBold());
        var inlay = blocks().get(0); var image = new java.awt.image.BufferedImage(inlay.getWidthInPixels(), inlay.getHeightInPixels(), java.awt.image.BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        try { view().paint(inlay, (Graphics) g, new Rectangle(image.getWidth(), image.getHeight()), null); } finally { g.dispose(); }
        var file = java.nio.file.Path.of("build/reports/practice-compact-preview.png"); java.nio.file.Files.createDirectories(file.getParent());
        javax.imageio.ImageIO.write(image, "png", file.toFile());
    }

    // 主动重定位可跨行/文件，保留答案阶段及段号，Ctrl+G不跟随光标移动。
    public void testShowHereMovesAnchorAndPreservesReviewState() throws Exception {
        var json = PracticePaperTest.json(); json.getAsJsonArray("questions").get(0).getAsJsonObject()
                .addProperty("explanation", "保留解析进度。".repeat(60));
        session.accept(PracticePaperTest.parse(json)); Editor editor = myFixture.getEditor();
        String original = editor.getDocument().getText(); long stamp = editor.getDocument().getModificationStamp();
        session.show(editor); session.choose("A"); session.submit(); session.setVisibleLines(1); session.moveSegment(2);
        String body = view().visibleText(); int page = view().pageIndex();
        var action = ActionManager.getInstance().getAction("Moyu.Practice.Here");
        var event = practiceEvent("Moyu.Practice.Here", editor); action.update(event);
        assertTrue(event.getPresentation().isEnabledAndVisible());
        editor.getCaretModel().moveToOffset(editor.getDocument().getLineStartOffset(1)); action.actionPerformed(event);
        assertEquals(1, blocks().size()); assertEquals(editor.getDocument().getLineEndOffset(1), blocks().get(0).getOffset());
        assertEquals(page, view().pageIndex()); assertEquals(body, view().visibleText()); assertTrue(session.progress().answerVisible);
        assertEquals("A", session.progress().submitted.get(session.question().id())); assertEquals(1, session.progress().mistakes.size());
        editor.getCaretModel().moveToOffset(0); toggle(); toggle();
        assertEquals(editor.getDocument().getLineEndOffset(1), blocks().get(0).getOffset());
        ActionManager.getInstance().getAction("Moyu.Practice.Show").actionPerformed(practiceEvent("Moyu.Practice.Show", editor));
        assertEquals(editor.getDocument().getLineEndOffset(0), blocks().get(0).getOffset());
        assertEquals(original, editor.getDocument().getText()); assertEquals(stamp, editor.getDocument().getModificationStamp());
        var file = myFixture.addFileToProject("another.txt", "first\nsecond\n");
        var other = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(getProject()).openTextEditor(
                new com.intellij.openapi.fileEditor.OpenFileDescriptor(getProject(), file.getVirtualFile()), false);
        assertNotNull(other); other.getCaretModel().moveToOffset(6);
        action.actionPerformed(practiceEvent("Moyu.Practice.Here", other)); assertTrue(blocks().isEmpty());
        var relocated = other.getInlayModel().getBlockElementsInRange(0, other.getDocument().getTextLength());
        assertEquals(1, relocated.size()); assertEquals(other.getDocument().getLineEndOffset(1), relocated.get(0).getOffset());
        assertEquals(page, session.progress().page); assertTrue(session.progress().answerVisible);
        var noEditor = practiceEvent("Moyu.Practice.Here", null); action.update(noEditor); assertFalse(noEditor.getPresentation().isVisible());
        session.deactivate(); action.update(event); assertFalse(event.getPresentation().isVisible());
    }

}
