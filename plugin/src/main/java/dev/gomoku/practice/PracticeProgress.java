package dev.gomoku.practice;

import java.util.*;

/** Plain serializable state; choices remain hidden from scoring until submitted. */
public final class PracticeProgress {
    public int index;
    public boolean onlyWrong;
    public Map<String, String> choices = new HashMap<>();
    public Map<String, String> submitted = new HashMap<>();
    public List<String> mistakes = new ArrayList<>();

    public PracticeProgress copy() {
        var copy = new PracticeProgress();
        copy.index = index;
        copy.onlyWrong = onlyWrong;
        copy.choices = new HashMap<>(choices);
        copy.submitted = new HashMap<>(submitted);
        copy.mistakes = new ArrayList<>(mistakes);
        return copy;
    }

    public void normalize(PracticePaper paper) {
        var valid = new HashMap<String, PracticePaper.Question>();
        for (var q : paper.questions()) valid.put(q.id(), q);
        choices.entrySet().removeIf(e -> !valid.containsKey(e.getKey()) || !valid.get(e.getKey()).validChoice(e.getValue()));
        submitted.entrySet().removeIf(e -> !valid.containsKey(e.getKey()) || !valid.get(e.getKey()).validChoice(e.getValue()));
        mistakes.removeIf(id -> !valid.containsKey(id));
        index = Math.max(0, Math.min(index, paper.questions().size() - 1));
        if (onlyWrong && !mistakes.contains(paper.questions().get(index).id())) {
            for (int i = 0; i < paper.questions().size(); i++) {
                if (mistakes.contains(paper.questions().get(i).id())) { index = i; break; }
            }
        }
    }

    public List<Integer> indices(PracticePaper paper) {
        var result = new ArrayList<Integer>();
        for (int i = 0; i < paper.questions().size(); i++) {
            if (!onlyWrong || mistakes.contains(paper.questions().get(i).id())) result.add(i);
        }
        return result;
    }

    public void move(PracticePaper paper, int delta) {
        var indices = indices(paper);
        if (indices.isEmpty()) return;
        int position = Math.max(0, indices.indexOf(index));
        index = indices.get(Math.max(0, Math.min(indices.size() - 1, position + delta)));
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

    public void retry(PracticePaper.Question q) {
        choices.remove(q.id());
        submitted.remove(q.id());
    }

    public String result(PracticePaper.Question q) {
        String answer = submitted.get(q.id());
        if (answer == null) return "";
        if (q.answer().isEmpty()) return "已记录 " + answer + " · 未提供答案，暂不判分";
        return (answer.equals(q.answer()) ? "正确" : "错误") + " · 你的选择 " + answer + " · 正确答案 " + q.answer();
    }
}
