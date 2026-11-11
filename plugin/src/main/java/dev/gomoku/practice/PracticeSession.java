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
        public int visibleLines = 6;
        public boolean detailsVisible = true;
        public String section = "";
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
    // 复制快照，避免后台持久化读取正在作答的可变数据。
    @Override public synchronized SavedState getState() {
        var copy = new SavedState();
        copy.paperId = state.paperId;
        copy.selected = state.selected;
        copy.fontSize = state.fontSize;
        copy.visibleLines = state.visibleLines;
        copy.detailsVisible = state.detailsVisible;
        copy.section = state.section;
        copy.titles = new HashMap<>(state.titles);
        copy.counts = new HashMap<>(state.counts);
        state.progress.forEach((id, progress) -> copy.progress.put(id, progress.copy()));
        return copy;
    }
    // 恢复设置，旧版本缺失字段与非法显示行数使用默认值。
    @Override public synchronized void loadState(SavedState value) {
        state = value;
        if (value.section == null || !PracticeSections.NAMES.contains(value.section)) value.section = "";
        if (value.fontSize != 0 && (value.fontSize < 8 || value.fontSize > 32)) value.fontSize = 0;
        if (value.visibleLines < 1 || value.visibleLines > 20) value.visibleLines = 6;
    }
    public boolean isSelected() { return state.selected; }
    public boolean isVisible() { return view != null; }
    // 仅显示刷题的编辑器接管辅助信息快捷键。
    public boolean isVisibleIn(Editor editor) { return view != null && anchorEditor == editor; }
    // 读取顶部信息和底部操作区的共同显隐状态。
    public boolean isDetailsVisible() { return state.detailsVisible; }
    // 切换辅助信息并收缩布局，保留当前题页与作答。
    public synchronized void toggleDetails() {
        state.detailsVisible = !state.detailsVisible;
        refresh();
    }
    // 板块模式按题目/答案切换，整卷模式保持原来的内容分页。
    public synchronized void movePage(int delta) {
        if (view == null) return;
        if (isSectionMode()) { progress().advance(paper, delta); view.firstPage(); }
        else view.movePage(delta);
    }
    // 禁用已经到达边界的翻页操作。
    public boolean canMovePage(int delta) {
        return view != null && (isSectionMode() ? progress().canAdvance(paper, delta) : canMoveSegment(delta));
    }
    // 长题与长解析的段落翻页独立于题目/答案阶段。
    public void moveSegment(int delta) { if (view != null) view.movePage(delta); }
    // 判断当前阶段是否还有内容段落。
    public boolean canMoveSegment(int delta) { return view != null && view.pageIndex() + delta >= 0 && view.pageIndex() + delta < view.pageCount(); }
    public boolean isSectionMode() { return !state.section.isEmpty(); }
    public String getSection() { return state.section; }
    // 每个板块有独立进度，切换不会覆盖原整卷作答。
    public synchronized void selectSection(String section) {
        if (paper == null || (!section.isEmpty() && (!PracticeSections.NAMES.contains(section) || PracticeSections.count(paper, section) == 0))) return;
        state.section = section;
        progress().normalize(paper);
        if (view != null) view.firstPage();
    }
    public boolean isLoading() { return loading; }
    public boolean hasPaper() { return paper != null; }
    PracticePaper paper() { return paper; }
    // 保留旧整卷键，板块另用复合键以兼容原有进度文件。
    synchronized PracticeProgress progress() {
        var result = state.progress.computeIfAbsent(paper.id() + (isSectionMode() ? ":" + state.section : ""), id -> new PracticeProgress());
        result.section = state.section;
        return result;
    }
    public int getFontSize() { return state.fontSize; }
    // 获取正文每页最多显示的行数，不含标题和按钮。
    public int getVisibleLines() { return state.visibleLines; }
    // 保存显示行数并重新分页，保留当前题目和作答。
    public synchronized void setVisibleLines(int lines) {
        if (lines < 1 || lines > 20) throw new IllegalArgumentException("显示行数必须是 1–20。");
        state.visibleLines = lines;
        if (view != null) view.firstPage();
    }
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
    // 加载试卷时保留可用的板块选择，未分类题库回到整卷。
    synchronized void accept(PracticePaper value) {
        hide();
        paper = value;
        if (isSectionMode() && PracticeSections.count(value, state.section) == 0) state.section = "";
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
        if (paper == null || empty() || isSectionMode() && progress().answerVisible) return;
        progress().select(question(), choice);
        refresh();
    }
    // 板块提交后进入独立答案页，整卷沿用原答案定位。
    public synchronized void submit() {
        if (paper == null || empty()) return;
        if (progress().submit(question()) && view != null) {
            if (isSectionMode()) { progress().answerVisible = true; view.firstPage(); }
            else view.answerPage();
        }
    }
    public synchronized void retry() {
        if (paper == null || empty()) return;
        progress().retry(question());
        if (view != null) view.firstPage();
    }
    @Override public void dispose() { disposed = true; revision++; hide(); clearAnchor(); }
}
