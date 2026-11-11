package dev.gomoku.practice;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileChooser.*;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.ui.*;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import java.nio.file.Path;
import java.util.*;
import javax.swing.*;
import java.awt.Dimension;

public final class PracticeActions {
    private static Editor editor(AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        return editor != null ? editor : e.getProject() == null ? null : FileEditorManager.getInstance(e.getProject()).getSelectedTextEditor();
    }
    public static final class ContextMenu extends DefaultActionGroup {
        @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        // 题目内只显示当前阶段需要的基础操作，设置和题库管理留在完整菜单。
        @Override public AnAction[] getChildren(AnActionEvent event) {
            var session = PracticeSession.getInstance();
            var names = new ArrayList<String>();
            if (session.hasPaper() && !session.empty()) {
                names.add("PreviousPage"); names.add("NextPage");
                if (session.canMoveSegment(-1) || session.canMoveSegment(1)) {
                    names.add("PreviousSegment"); names.add("NextSegment");
                }
                if (!session.progress().answerVisible && !session.progress().submitted.containsKey(session.question().id())) {
                    for (int i = 0; i < session.question().options().size(); i++) names.add(String.valueOf((char) ('A' + i)));
                    names.add("Submit");
                }
                if (session.progress().submitted.containsKey(session.question().id())) names.add("Retry");
                var q = session.question();
                if (!q.images().isEmpty() || !q.materialImages().isEmpty() || q.options().stream().anyMatch(o -> o.image() != null)) names.add("Images");
            }
            names.add("Here"); names.add("Hide");
            return names.stream().map(name -> ActionManager.getInstance().getAction("Moyu.Practice." + name)).toArray(AnAction[]::new);
        }
    }
    public abstract static class LoadAction extends DumbAwareAction {
        @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        @Override public void update(AnActionEvent e) { e.getPresentation().setEnabled(!PracticeSession.getInstance().isLoading()); }
    }
    public static final class Import extends LoadAction {
        @Override public void actionPerformed(AnActionEvent e) {
            var descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("json");
            descriptor.setTitle("导入行测 JSON 题库");
            Editor target = editor(e);
            FileChooser.chooseFile(descriptor, e.getProject(), null, file ->
                    PracticeSession.getInstance().importFile(Path.of(file.getPath()), e.getProject(), target));
        }
    }
    public static final class Sample extends LoadAction {
        @Override public void actionPerformed(AnActionEvent e) { PracticeSession.getInstance().openSample(e.getProject(), editor(e)); }
    }
    public static final class Papers extends LoadAction {
        @Override public void actionPerformed(AnActionEvent e) {
            var session = PracticeSession.getInstance();
            java.util.List<PracticeCatalog.Entry> entries;
            try { entries = PracticeCatalog.entries(session.getState()); }
            catch (java.io.IOException error) { Messages.showErrorDialog(e.getProject(), error.getMessage(), "选择试卷"); return; }
            if (entries.isEmpty()) { Messages.showInfoMessage(e.getProject(), "请先导入题库或打开示例试卷。", "选择试卷"); return; }
            Editor target = editor(e);
            JBPopupFactory.getInstance().createPopupChooserBuilder(entries).setTitle("选择试卷（输入年份或名称筛选）")
                    .setNamerForFiltering(PracticeCatalog.Entry::title).setItemChosenCallback(entry -> {
                        if (e.getProject() != null && e.getProject().isDisposed()) return;
                        if (entry.bundled()) session.openBundled(entry.id(), e.getProject(), target);
                        else session.openPaper(entry.id(), e.getProject(), target);
                    }).createPopup().showInFocusCenter();
        }
    }
    public static class Show extends DumbAwareAction {
        @Override public void actionPerformed(AnActionEvent e) {
            var session = PracticeSession.getInstance();
            Editor editor = editor(e);
            if (editor == null) Messages.showInfoMessage(e.getProject(), "请先打开一个代码或文本文件。", "行测刷题");
            else if (!session.hasPaper()) Messages.showInfoMessage(e.getProject(), "请先通过“选择试卷”打开试卷，也可以导入题库或打开示例。", "行测刷题");
            else session.showHere(editor);
        }
    }
    public static final class Here extends Show {
        @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        // 仅刷题模式在编辑器右键中提供直接入口，不占用其他模式的菜单。
        @Override public void update(AnActionEvent e) {
            var session = PracticeSession.getInstance();
            e.getPresentation().setEnabledAndVisible(session.hasPaper() && session.isSelected()
                    && e.getData(CommonDataKeys.EDITOR) != null);
        }
    }
    public static final class Mistakes extends LoadAction {
        @Override public void actionPerformed(AnActionEvent e) { PracticeMistakes.show(e.getProject(), editor(e)); }
    }
    public static final class Sections extends LoadAction {
        // 先选择试卷，板块列表只展示该卷实际拥有的题目。
        @Override public void update(AnActionEvent e) {
            super.update(e);
            e.getPresentation().setEnabled(e.getPresentation().isEnabled() && PracticeSession.getInstance().hasPaper());
        }
        // 在当前试卷切换板块，整卷与各板块分别恢复进度。
        @Override public void actionPerformed(AnActionEvent e) {
            var session = PracticeSession.getInstance();
            if (!session.hasPaper()) return;
            var paper = session.paper();
            var choices = new LinkedHashMap<String, String>();
            choices.put("整卷练习 · " + paper.questions().size() + " 题", "");
            for (String name : PracticeSections.NAMES) {
                int count = PracticeSections.count(paper, name);
                if (count > 0) choices.put(name + " · " + count + " 题", name);
            }
            Editor target = editor(e);
            JBPopupFactory.getInstance().createPopupChooserBuilder(new ArrayList<>(choices.keySet()))
                    .setTitle("板块练习 · " + paper.title()).setItemChosenCallback(label -> {
                        if (session.paper() != paper || e.getProject() != null && e.getProject().isDisposed()) return;
                        session.selectSection(choices.get(label));
                        if (target != null && !target.isDisposed()) session.show(target);
                    }).createPopup().showInFocusCenter();
        }
    }
    public static final class Dashboard extends DumbAwareAction {
        @Override public void actionPerformed(AnActionEvent e) { com.intellij.ide.BrowserUtil.browse(PracticeSync.URL); }
    }
    public static final class ConnectDashboard extends DumbAwareAction {
        @Override public void actionPerformed(AnActionEvent e) {
            String code = Messages.showInputDialog(e.getProject(), "先在个人看板登录并生成一次性配对码。连接后会上传本机新版作答记录、题目和答案；不上传代码或小说。\n看板：" + PracticeSync.URL, "连接个人看板", null);
            if (code == null) return;
            String clean = code.trim().toUpperCase(java.util.Locale.ROOT);
            if (!clean.matches("[A-F0-9]{12}")) { Messages.showErrorDialog(e.getProject(), "请输入12位配对码。", "连接个人看板"); return; }
            PracticeSync.getInstance().connect(clean, text -> { if(e.getProject()==null || !e.getProject().isDisposed()) Messages.showInfoMessage(e.getProject(), text, "个人看板"); });
        }
    }
    public static final class SyncDashboard extends DumbAwareAction {
        @Override public void actionPerformed(AnActionEvent e) { PracticeSync.getInstance().syncNow(text -> { if(e.getProject()==null || !e.getProject().isDisposed()) Messages.showInfoMessage(e.getProject(), text, "同步状态"); }); }
    }
    public static final class DisconnectDashboard extends DumbAwareAction {
        @Override public void actionPerformed(AnActionEvent e) { PracticeSync.getInstance().disconnect(text -> { if(e.getProject()==null || !e.getProject().isDisposed()) Messages.showInfoMessage(e.getProject(), text, "个人看板"); }); }
    }
    public abstract static class QuestionAction extends DumbAwareAction {
        @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        @Override public void update(AnActionEvent e) {
            var session = PracticeSession.getInstance();
            e.getPresentation().setEnabled(session.hasPaper() && session.isSelected() && session.isVisible());
        }
    }
    public static final class Previous extends QuestionAction { @Override public void actionPerformed(AnActionEvent e) { PracticeSession.getInstance().move(-1); } }
    public static final class Details extends QuestionAction {
        // Ctrl+D 只在当前刷题编辑器中切换辅助区，其他位置让出快捷键。
        @Override public void update(AnActionEvent e) {
            super.update(e);
            var session = PracticeSession.getInstance();
            e.getPresentation().setEnabled(e.getPresentation().isEnabled() && session.isVisibleIn(e.getData(CommonDataKeys.EDITOR)));
            e.getPresentation().setText(session.isDetailsVisible() ? "隐藏试卷信息和操作栏" : "显示试卷信息和操作栏");
        }
        // 即使动作被直接调用，也只修改当前显示的刷题面板。
        @Override public void actionPerformed(AnActionEvent e) {
            var session = PracticeSession.getInstance();
            if (session.isVisibleIn(e.getData(CommonDataKeys.EDITOR))) session.toggleDetails();
        }
    }
    public static final class PreviousPage extends QuestionAction {
        // 在首屏禁用上一页，隐藏辅助区时仍可从菜单访问。
        @Override public void update(AnActionEvent e) {
            super.update(e);
            e.getPresentation().setEnabled(e.getPresentation().isEnabled() && PracticeSession.getInstance().canMovePage(-1));
        }
        // 翻阅当前题的上一页。
        @Override public void actionPerformed(AnActionEvent e) { PracticeSession.getInstance().movePage(-1); }
    }
    public static final class NextPage extends QuestionAction {
        // 在末屏禁用下一页，避免菜单越过题目边界。
        @Override public void update(AnActionEvent e) {
            super.update(e);
            e.getPresentation().setEnabled(e.getPresentation().isEnabled() && PracticeSession.getInstance().canMovePage(1));
        }
        // 翻阅当前题的下一页。
        @Override public void actionPerformed(AnActionEvent e) { PracticeSession.getInstance().movePage(1); }
    }
    public static final class PreviousSegment extends QuestionAction {
        // 解析的段落翻页与题目/答案切换分开。
        @Override public void update(AnActionEvent e) {
            super.update(e); var session = PracticeSession.getInstance();
            e.getPresentation().setEnabled(e.getPresentation().isEnabled() && session.canMoveSegment(-1));
        }
        @Override public void actionPerformed(AnActionEvent e) { PracticeSession.getInstance().moveSegment(-1); }
    }
    public static final class NextSegment extends QuestionAction {
        // 当前题目或解析的剩余内容可逐段读完。
        @Override public void update(AnActionEvent e) {
            super.update(e); var session = PracticeSession.getInstance();
            e.getPresentation().setEnabled(e.getPresentation().isEnabled() && session.canMoveSegment(1));
        }
        @Override public void actionPerformed(AnActionEvent e) { PracticeSession.getInstance().moveSegment(1); }
    }
    public static final class Next extends QuestionAction { @Override public void actionPerformed(AnActionEvent e) { PracticeSession.getInstance().move(1); } }
    public static final class Submit extends QuestionAction { @Override public void actionPerformed(AnActionEvent e) { PracticeSession.getInstance().submit(); } }
    public static final class Retry extends QuestionAction { @Override public void actionPerformed(AnActionEvent e) { PracticeSession.getInstance().retry(); } }
    public static final class Filter extends QuestionAction {
        @Override public void actionPerformed(AnActionEvent e) { PracticeSession.getInstance().filter(); }
        @Override public void update(AnActionEvent e) {
            super.update(e);
            var session = PracticeSession.getInstance();
            e.getPresentation().setText(session.hasPaper() && session.progress().onlyWrong ? "返回全部题" : "只看错题");
        }
    }
    public static final class Hide extends QuestionAction { @Override public void actionPerformed(AnActionEvent e) { PracticeSession.getInstance().hide(); } }
    public static final class A extends QuestionAction { @Override public void actionPerformed(AnActionEvent e) { PracticeSession.getInstance().choose("A"); } }
    public static final class B extends QuestionAction { @Override public void actionPerformed(AnActionEvent e) { PracticeSession.getInstance().choose("B"); } }
    public static final class C extends QuestionAction {
        @Override public void actionPerformed(AnActionEvent e) { PracticeSession.getInstance().choose("C"); }
        @Override public void update(AnActionEvent e) { super.update(e); var s = PracticeSession.getInstance(); e.getPresentation().setEnabled(e.getPresentation().isEnabled() && !s.empty() && s.question().options().size() > 2); }
    }
    public static final class D extends QuestionAction {
        @Override public void actionPerformed(AnActionEvent e) { PracticeSession.getInstance().choose("D"); }
        @Override public void update(AnActionEvent e) { super.update(e); var s = PracticeSession.getInstance(); e.getPresentation().setEnabled(e.getPresentation().isEnabled() && !s.empty() && s.question().options().size() > 3); }
    }
    public static final class Images extends QuestionAction {
        @Override public void actionPerformed(AnActionEvent e) {
            var session = PracticeSession.getInstance();
            if (!session.hasPaper() || session.empty()) return;
            var q = session.question();
            var images = new ArrayList<java.awt.image.BufferedImage>();
            images.addAll(q.materialImages()); images.addAll(q.images());
            q.options().forEach(option -> { if (option.image() != null) images.add(option.image()); });
            if (images.isEmpty()) { Messages.showInfoMessage(e.getProject(), "本题没有图片。", "题目原图"); return; }
            new DialogWrapper(e.getProject()) {
                { setTitle("题目原图（按材料、题干、选项顺序）"); setOKButtonText("关闭"); init(); }
                @Override protected JComponent createCenterPanel() {
                    var panel = new JPanel(); panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
                    for (var image : images) panel.add(new JLabel(new ImageIcon(image)));
                    var scroll = new JScrollPane(panel);
                    scroll.setPreferredSize(new Dimension(760, 520));
                    return scroll;
                }
                @Override protected Action[] createActions() { return new Action[] {getOKAction()}; }
            }.show();
        }
    }
    public static final class VisibleLines extends DumbAwareAction {
        // 调整正文展示高度，取消对话框时保留原设置。
        @Override public void actionPerformed(AnActionEvent e) {
            var session = PracticeSession.getInstance();
            String value = Messages.showInputDialog(e.getProject(),
                    "输入解析每段行数 1–20（默认 6）；题目始终完整显示，行数不足会自动撑开。",
                    "刷题显示行数", null, Integer.toString(session.getVisibleLines()), new InputValidator() {
                        // 仅接受范围内的整数，避免无效设置进入布局。
                        @Override public boolean checkInput(String text) {
                            try { int lines = Integer.parseInt(text.trim()); return lines >= 1 && lines <= 20; }
                            catch (NumberFormatException ignored) { return false; }
                        }
                        // 输入有效时才允许提交。
                        @Override public boolean canClose(String text) { return checkInput(text); }
                    });
            if (value != null) session.setVisibleLines(Integer.parseInt(value.trim()));
        }
    }
    public static final class FontSize extends DumbAwareAction {
        @Override public void actionPerformed(AnActionEvent e) {
            var session = PracticeSession.getInstance();
            String value = Messages.showInputDialog(e.getProject(), "输入字号 8–32；输入 0 跟随 IDE 编辑器字号。", "行测字号", null,
                    Integer.toString(session.getFontSize()), new InputValidator() {
                        @Override public boolean checkInput(String text) {
                            try { int size = Integer.parseInt(text.trim()); return size == 0 || size >= 8 && size <= 32; }
                            catch (NumberFormatException ignored) { return false; }
                        }
                        @Override public boolean canClose(String text) { return checkInput(text); }
                    });
            if (value != null) session.setFontSize(Integer.parseInt(value.trim()));
        }
    }
}
