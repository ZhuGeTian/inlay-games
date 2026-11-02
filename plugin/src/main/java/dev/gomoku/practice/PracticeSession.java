package dev.gomoku.practice;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.*;
import com.intellij.openapi.components.*;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.event.*;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import dev.gomoku.novel.NovelReader;
import dev.gomoku.plugin.GomokuSession;
import java.io.*;
import java.nio.file.*;
import java.util.*;

@State(name = "MoyuPractice", storages = @Storage(value = "moyu-practice.xml", roamingType = RoamingType.DISABLED))
public final class PracticeSession implements PersistentStateComponent<PracticeSession.SavedState>, Disposable {
    public static final class SavedState {
        public String paperId = "";
        public boolean selected;
        public int fontSize;
        public Map<String, String> titles = new HashMap<>();
        public Map<String, Integer> counts = new HashMap<>();
        public Map<String, PracticeProgress> progress = new HashMap<>();
    }
    private SavedState state = new SavedState();
    private PracticePaper paper;
    private PracticeView view;
    private Editor anchorEditor;
    private RangeMarker anchor;
    private boolean loading, started, disposed;
    private long revision;

    public PracticeSession() {
        EditorFactory.getInstance().addEditorFactoryListener(new EditorFactoryListener() {
            @Override public void editorReleased(EditorFactoryEvent e) {
                if (e.getEditor() == anchorEditor) { hide(); clearAnchor(); }
            }
        }, this);
    }
    public static PracticeSession getInstance() { return ApplicationManager.getApplication().getService(PracticeSession.class); }
    static Path directory() { return PathManager.getConfigDir().resolve("moyu-practice/papers"); }
    static boolean validId(String id) { return id != null && id.matches("[0-9a-f]{64}"); }
    // IDE persistence may snapshot state off the EDT while the user answers a question.
    @Override public synchronized SavedState getState() {
        var copy = new SavedState();
        copy.paperId = state.paperId;
        copy.selected = state.selected;
        copy.fontSize = state.fontSize;
        copy.titles = new HashMap<>(state.titles);
        copy.counts = new HashMap<>(state.counts);
        state.progress.forEach((id, progress) -> copy.progress.put(id, progress.copy()));
        return copy;
    }
    @Override public synchronized void loadState(SavedState value) {
        state = value;
        if (value.fontSize != 0 && (value.fontSize < 8 || value.fontSize > 32)) value.fontSize = 0;
    }
    public boolean isSelected() { return state.selected; }
    public boolean isVisible() { return view != null; }
    public boolean isLoading() { return loading; }
    public boolean hasPaper() { return paper != null; }
    PracticePaper paper() { return paper; }
    synchronized PracticeProgress progress() { return state.progress.computeIfAbsent(paper.id(), id -> new PracticeProgress()); }
    public int getFontSize() { return state.fontSize; }
    public synchronized void setFontSize(int size) {
        if (size != 0 && (size < 8 || size > 32)) throw new IllegalArgumentException("字号必须是 0 或 8–32。");
        state.fontSize = size;
        refresh();
    }
    public void start(Project project) {
        if (started || disposed) return;
        started = true;
        if (validId(state.paperId)) load(directory().resolve(state.paperId + ".json"), false, false, project, null);
    }
    public void importFile(Path file, Project project, Editor editor) { load(file, true, true, project, editor); }
    public void openSample(Project project, Editor editor) { load(null, true, true, project, editor); }
    public void openPaper(String id, Project project, Editor editor) {
        if (!validId(id)) return;
        load(directory().resolve(id + ".json"), false, true, project, editor);
    }
    public void openBundled(String id, Project project, Editor editor) {
        if (!validId(id)) return;
        load(null, true, true, project, editor, id);
    }
    private void load(Path file, boolean cache, boolean reveal, Project project, Editor editor) {
        load(file, cache, reveal, project, editor, null);
    }
    private void load(Path file, boolean cache, boolean reveal, Project project, Editor editor, String bundledId) {
        if (loading || disposed) return;
        loading = true;
        long requested = ++revision;
        new Task.Backgroundable(project, "加载行测试卷", true) {
            PracticePaper parsed;
            @Override public void run(ProgressIndicator indicator) {
                try {
                    byte[] bytes;
                    if (file == null) {
                        try (var input = bundledId == null ? PracticeSession.class.getResourceAsStream("/practice/sample-practice.json") : PracticeCatalog.open(bundledId)) {
                            if (input == null) throw new IOException("示例题库不存在。");
                            bytes = PracticePaper.readBytes(input);
                        }
                    } else bytes = PracticePaper.readBytes(file);
                    indicator.checkCanceled();
                    parsed = PracticePaper.parse(bytes);
                    if (bundledId != null && !bundledId.equals(parsed.id())) throw new IOException("安装包内试卷校验失败。");
                    if (!cache && !file.getFileName().toString().equals(parsed.id() + ".json"))
                        throw new IOException("缓存内容已变化，请重新导入题库。");
                    indicator.checkCanceled();
                    if (cache) {
                        Files.createDirectories(directory());
                        Path temp = Files.createTempFile(directory(), "import-", ".tmp");
                        try {
                            Files.write(temp, bytes);
                            indicator.checkCanceled();
                            Files.move(temp, directory().resolve(parsed.id() + ".json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                        } finally { Files.deleteIfExists(temp); }
                    }
                } catch (IOException error) { throw new IllegalArgumentException(error.getMessage(), error); }
            }
            @Override public void onSuccess() {
                if (disposed || requested != revision || project != null && project.isDisposed()) return;
                accept(parsed);
                if (reveal) {
                    if (editor != null && !editor.isDisposed()) show(editor);
                    else Messages.showInfoMessage(project, "题库已导入。打开代码文件后选择“行测刷题 → 在此显示”。", "行测刷题");
                }
            }
            @Override public void onThrowable(Throwable error) {
                if (!disposed && (project == null || !project.isDisposed())) Messages.showErrorDialog(project,
                        "无法加载题库，原有试卷和进度未改变。\n" + error.getMessage(), "行测刷题");
            }
            @Override public void onFinished() { loading = false; }
        }.queue();
    }
    synchronized void accept(PracticePaper value) {
        hide();
        paper = value;
        state.paperId = value.id();
        state.titles.put(value.id(), value.title());
        state.counts.put(value.id(), value.questions().size());
        progress().normalize(value);
    }
    public synchronized void show(Editor editor) {
        if (disposed || paper == null || editor.isDisposed()) return;
        revision++;
        NovelReader.getInstance().deactivate();
        for (Editor candidate : EditorFactory.getInstance().getAllEditors()) {
            Project project = candidate.getProject();
            if (project != null && !project.isDisposed()) {
                GomokuSession game = project.getServiceIfCreated(GomokuSession.class);
                if (game != null) game.hideBoard();
            }
        }
        hide();
        state.selected = true;
        if (anchorEditor != editor || anchor == null || !anchor.isValid()) {
            clearAnchor();
            anchorEditor = editor;
            int offset = editor.getDocument().getLineEndOffset(editor.getCaretModel().getLogicalPosition().line);
            anchor = editor.getDocument().createRangeMarker(offset, offset);
        }
        var next = new PracticeView(editor, this);
        view = next;
        Disposer.register(this, next);
        next.attach(anchor.getStartOffset());
    }
    public void toggleVisibility(Editor editor) { if (isVisible()) hide(); else show(editor); }
    public synchronized void deactivate() { revision++; state.selected = false; hide(); }
    public void hide() { revision++; if (view != null) Disposer.dispose(view); }
    void closed(PracticeView closed) { if (view == closed) view = null; }
    private void clearAnchor() { if (anchor != null) anchor.dispose(); anchor = null; anchorEditor = null; }
    public void refresh() { if (view != null) view.refresh(); }
    public synchronized void move(int delta) {
        if (paper == null) return;
        progress().move(paper, delta);
        if (view != null) view.firstPage();
    }
    public synchronized void filter() {
        if (paper == null) return;
        progress().onlyWrong = !progress().onlyWrong;
        progress().normalize(paper);
        if (view != null) view.firstPage();
    }
    PracticePaper.Question question() { return paper.questions().get(progress().index); }
    boolean empty() { return progress().indices(paper).isEmpty(); }
    public synchronized void choose(String choice) {
        if (paper == null || empty()) return;
        progress().select(question(), choice);
        refresh();
    }
    public synchronized void submit() {
        if (paper == null || empty()) return;
        if (progress().submit(question()) && view != null) view.answerPage();
    }
    public synchronized void retry() {
        if (paper == null || empty()) return;
        progress().retry(question());
        if (view != null) view.firstPage();
    }
    @Override public void dispose() { disposed = true; revision++; hide(); clearAnchor(); }
}
