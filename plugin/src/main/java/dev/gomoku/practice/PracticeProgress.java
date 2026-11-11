package dev.gomoku.practice;

import java.util.*;

/** Plain serializable state; choices remain hidden from scoring until submitted. */
public final class PracticeProgress {
    public int index;
    public int page;
    public boolean onlyWrong;
    public String section = "";
    public boolean answerVisible;
    public Map<String, String> choices = new HashMap<>();
    public Map<String, String> submitted = new HashMap<>();
    public List<String> mistakes = new ArrayList<>();

    // 创建含板块与答案阶段的独立持久化快照。
    public PracticeProgress copy() {
        var copy = new PracticeProgress();
        copy.index = index;
        copy.page = page;
        copy.onlyWrong = onlyWrong;
        copy.section = section;
        copy.answerVisible = answerVisible;
        copy.choices = new HashMap<>(choices);
        copy.submitted = new HashMap<>(submitted);
        copy.mistakes = new ArrayList<>(mistakes);
        return copy;
    }

    // 恢复进度时同时限制板块与错题范围。
    public void normalize(PracticePaper paper) {
        var valid = new HashMap<String, PracticePaper.Question>();
        for (var q : paper.questions()) valid.put(q.id(), q);
        choices.entrySet().removeIf(e -> !valid.containsKey(e.getKey()) || !valid.get(e.getKey()).validChoice(e.getValue()));
        submitted.entrySet().removeIf(e -> !valid.containsKey(e.getKey()) || !valid.get(e.getKey()).validChoice(e.getValue()));
        mistakes.removeIf(id -> !valid.containsKey(id));
        index = Math.max(0, Math.min(index, paper.questions().size() - 1));
        if (section == null) section = "";
        page = Math.max(0, page);
        // 新增参考答案后，已提交记录也纳入历史错题，未提交选择不判分。
        for (var q : paper.questions()) {
            String choice = submitted.get(q.id());
            if (choice != null && !q.answer().isEmpty() && !choice.equals(q.answer()) && !mistakes.contains(q.id())) mistakes.add(q.id());
        }
        var available = indices(paper);
        if (!available.contains(index)) { if (!available.isEmpty()) index = available.get(0); answerVisible = false; page = 0; }
    }

    // 板块筛选与错题筛选取交集，保留原试卷顺序。
    public List<Integer> indices(PracticePaper paper) {
        var result = new ArrayList<Integer>();
        for (int i = 0; i < paper.questions().size(); i++) {
            if ((section.isEmpty() || section.equals(PracticeSections.section(paper, i)))
                    && (!onlyWrong || mistakes.contains(paper.questions().get(i).id()))) result.add(i);
        }
        return result;
    }

    // 切换题目时先展示题目阶段，避免提前透露答案。
    public void move(PracticePaper paper, int delta) {
        var indices = indices(paper);
        if (indices.isEmpty()) return;
        int position = Math.max(0, indices.indexOf(index));
        index = indices.get(Math.max(0, Math.min(indices.size() - 1, position + delta)));
        answerVisible = false;
        page = 0;
    }

    // 下一页依次展示答案与下一题，上一页按相反顺序返回。
    public void advance(PracticePaper paper, int delta) {
        if (!canAdvance(paper, delta)) return;
        page = 0;
        if (delta > 0) {
            if (!answerVisible) { submit(paper.questions().get(index)); answerVisible = true; }
            else move(paper, 1);
        } else if (answerVisible) answerVisible = false;
        else { move(paper, -1); answerVisible = true; }
    }

    // 首题题目与末题答案之间才允许跨阶段翻页。
    public boolean canAdvance(PracticePaper paper, int delta) {
        var available = indices(paper);
        int at = available.indexOf(index);
        return at >= 0 && (delta > 0 ? !answerVisible || at + 1 < available.size() : answerVisible || at > 0);
    }

    public void select(PracticePaper.Question q, String choice) {
        if (choice == null || !choice.matches("[A-D]") || !q.validChoice(choice) || submitted.containsKey(q.id())) return;
        if (q.type().equals("multiple")) {
            String previous = choices.getOrDefault(q.id(), "");
            String next = previous.contains(choice) ? previous.replace(choice, "") : PracticePaper.canonical(previous + choice);
            if (next.isEmpty()) choices.remove(q.id()); else choices.put(q.id(), next);
        } else choices.put(q.id(), choice);
    }

    public boolean submit(PracticePaper.Question q) {
        String choice = choices.get(q.id());
        if (!q.validChoice(choice) || submitted.containsKey(q.id())) return false;
        submitted.put(q.id(), choice);
        if (!q.answer().isEmpty() && !q.answer().equals(choice) && !mistakes.contains(q.id())) mistakes.add(q.id());
        return true;
    }

    // 重做清除本次选择并回到题目阶段，保留历史错题。
    public void retry(PracticePaper.Question q) {
        choices.remove(q.id());
        submitted.remove(q.id());
        answerVisible = false;
        page = 0;
    }

    public String result(PracticePaper.Question q) {
        String answer = submitted.get(q.id());
        if (answer == null) return "";
        if (q.answer().isEmpty()) return "已记录 " + answer + " · 未提供答案，暂不判分";
        return (answer.equals(q.answer()) ? "正确" : "错误") + " · 你的选择 " + answer + " · " + q.answerLabel() + " " + q.answer();
    }
}
