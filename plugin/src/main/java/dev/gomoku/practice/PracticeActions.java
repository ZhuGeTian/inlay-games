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
    public static final class Show extends DumbAwareAction {
        @Override public void actionPerformed(AnActionEvent e) {
            var session = PracticeSession.getInstance();
            Editor editor = editor(e);
            if (editor == null) Messages.showInfoMessage(e.getProject(), "请先打开一个代码或文本文件。", "行测刷题");
            else if (!session.hasPaper()) Messages.showInfoMessage(e.getProject(), "请先通过“选择试卷”打开试卷，也可以导入题库或打开示例。", "行测刷题");
            else session.show(editor);
        }
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
                    "输入正文显示行数 1–20（默认 6）；不含题号和按钮，超出内容可翻页查看。",
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
