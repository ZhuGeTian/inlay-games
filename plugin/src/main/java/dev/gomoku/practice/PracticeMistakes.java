package dev.gomoku.practice;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import java.io.*;
import java.nio.file.Files;
import java.util.*;

final class PracticeMistakes {
    record Entry(String paperId, String questionId, String section, String label) {
        @Override public String toString() { return label; }
    }
    // 同一试卷整卷和板块错题取并集；同一道题只列一次，重做正确仍保留。
    static List<Entry> entries(PracticeSession.SavedState saved, PracticePaper paper) {
        var found = new LinkedHashMap<String, Entry>();
        saved.progress.keySet().stream().sorted().forEach(key -> {
            if (!key.equals(paper.id()) && !key.startsWith(paper.id() + ":")) return;
            var progress = saved.progress.get(key).copy();
            progress.normalize(paper);
            String section = key.equals(paper.id()) ? "" : key.substring(paper.id().length() + 1);
            if (!section.isEmpty() && !PracticeSections.NAMES.contains(section)) return;
            for (int i = 0; i < paper.questions().size(); i++) {
                var q = paper.questions().get(i);
                if (progress.mistakes.contains(q.id())) {
                    String stem = q.stem().replaceAll("\\s+", " ");
                    String label = paper.title() + " · 第 " + (i + 1) + " 题 · " + stem.substring(0, Math.min(48, stem.length()));
                    found.putIfAbsent(q.id(), new Entry(paper.id(), q.id(), section, label));
                }
            }
        });
        return List.copyOf(found.values());
    }
    // 读取和解析放后台；单卷丢失不影响其他卷，也不删除历史记录。
    static void show(Project project, Editor editor) {
        var session = PracticeSession.getInstance();
        var saved = session.getState();
        new Task.Backgroundable(project, "加载错题库", true) {
            final List<Entry> result = new ArrayList<>();
            final List<String> missing = new ArrayList<>();
            @Override public void run(ProgressIndicator indicator) {
                var ids = new TreeSet<String>();
                for (String key : saved.progress.keySet()) if (key.length() >= 64 && PracticeSession.validId(key.substring(0, 64))) ids.add(key.substring(0, 64));
                for (String id : ids) {
                    indicator.checkCanceled();
                    try {
                        var file = PracticeSession.directory().resolve(id + ".json");
                        byte[] bytes;
                        if (Files.isRegularFile(file)) bytes = PracticePaper.readBytes(file);
                        else try (var input = PracticeCatalog.open(id)) { bytes = PracticePaper.readBytes(input); }
                        var paper = PracticePaper.parse(bytes);
                        if (!paper.id().equals(id)) throw new IOException("题库校验失败");
                        result.addAll(entries(saved, paper));
                    } catch (IOException e) { missing.add(saved.titles.getOrDefault(id, id)); }
                }
                result.sort(Comparator.comparing(Entry::label));
            }
            @Override public void onSuccess() {
                if (project != null && project.isDisposed()) return;
                if (!missing.isEmpty()) Messages.showWarningDialog(project, "以下试卷暂时无法读取，错题记录仍保留：\n" + String.join("\n", missing), "错题库");
                if (result.isEmpty()) { Messages.showInfoMessage(project, "暂无可读取的错题。有答案的题提交答错后会自动收录。", "错题库"); return; }
                JBPopupFactory.getInstance().createPopupChooserBuilder(result).setTitle("错题库 · " + result.size() + " 题（输入年份或题干筛选）")
                        .setNamerForFiltering(Entry::label).setItemChosenCallback(entry -> {
                            if (project == null || !project.isDisposed()) session.openMistake(entry, project, editor);
                        }).createPopup().showInFocusCenter();
            }
        }.queue();
    }
}
