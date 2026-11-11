package dev.gomoku.practice;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class PracticeCatalogTest {
    @Test public void catalogMergesByHashAndListsNewestYearFirst() throws Exception {
        String a = "a".repeat(64), b = "b".repeat(64), c = "c".repeat(64);
        var entries = PracticeCatalog.parse(("[{\"id\":\"" + a + "\",\"title\":\"2025 试卷\",\"questionCount\":90},"
                + "{\"id\":\"" + b + "\",\"title\":\"2026 试卷\",\"questionCount\":90}]").getBytes(StandardCharsets.UTF_8));
        var saved = new PracticeSession.SavedState(); saved.titles.put(a, "同卷缓存"); saved.titles.put(c, "2024 自制试卷");
        var merged = PracticeCatalog.merge(entries, saved);
        assertEquals(3, merged.size()); assertEquals(b, merged.get(0).id()); assertEquals(a, merged.get(1).id());
        assertEquals(c, merged.get(2).id()); assertTrue(merged.get(0).bundled()); assertFalse(merged.get(2).bundled());
        assertThrows(IOException.class, () -> PracticeCatalog.open("../invalid"));
        assertThrows(IOException.class, () -> PracticeCatalog.parse("[{}]".getBytes(StandardCharsets.UTF_8)));
    }
    @Test public void packagedBanksMatchIndexAndKeepAllQuestionTypesMaterialsAndImages() throws Exception {
        for (var entry : PracticeCatalog.bundled()) {
            PracticePaper paper;
            try (var input = PracticeCatalog.open(entry.id())) { paper = PracticePaper.parse(PracticePaper.readBytes(input)); }
            assertEquals(entry.id(), paper.id()); assertEquals(entry.title(), paper.title()); assertEquals(entry.count(), paper.questions().size());
            if (entry.count() == 225) { checkVerbalBank(paper); continue; }
            assertEquals(90, paper.questions().size());
            assertEquals(4, paper.questions().stream().filter(q -> q.type().equals("boolean")).count());
            assertEquals(2, paper.questions().stream().filter(q -> q.type().equals("multiple")).count());
            assertEquals(84, paper.questions().stream().filter(q -> q.type().equals("single")).count());
            assertEquals(paper.title().startsWith("2026") ? 30 : 29, paper.questions().stream().filter(q -> !q.answer().isEmpty()).count());
            for (int i = 0; i < 30; i++) {
                var q = paper.questions().get(i);
                assertFalse(q.explanation().isEmpty()); assertFalse(q.answerSource().isBlank());
                assertEquals("参考答案", q.answerLabel());
            }
            assertTrue(paper.questions().subList(30, 90).stream().allMatch(q -> q.answer().isEmpty() && q.explanation().isEmpty()));
            for (int i = 70; i < 90; i++) {
                var q = paper.questions().get(i); assertFalse(q.material().isBlank());
                var first = paper.questions().get(70 + (i - 70) / 5 * 5);
                assertEquals(first.material(), q.material()); assertEquals(first.materialImages(), q.materialImages());
            }
            for (int i = 45; i < 50; i++) assertFalse(paper.questions().get(i).images().isEmpty());
        }
    }
    // 真实专项题库的分组、完整选项、答案来源和跨页文字必须保持完整。
    private static void checkVerbalBank(PracticePaper paper) {
        assertEquals(225, PracticeSections.count(paper, "言语理解"));
        var groups = new LinkedHashMap<String, Integer>();
        groups.put("fill", 114); groups.put("main", 53); groups.put("title", 6); groups.put("meaning", 3);
        groups.put("detail", 29); groups.put("sentence", 13); groups.put("order", 6); groups.put("next", 1);
        for (var group : groups.entrySet()) assertEquals((long) group.getValue(), paper.questions().stream()
                .filter(q -> q.id().startsWith("gd-verbal-" + group.getKey() + "-")).count());
        for (var q : paper.questions()) {
            assertEquals("single", q.type()); assertEquals(4, q.options().size());
            assertTrue(q.validChoice(q.answer())); assertTrue(q.explanation().length() > 20);
            assertTrue(q.answerSource().contains("PDF第")); assertFalse(q.stem().contains("研习社"));
        }
        var repaired = paper.questions().stream().filter(q -> q.id().equals("gd-verbal-main-042")).findFirst().orElseThrow();
        assertEquals("要选取能引起观众共鸣的纪录片主题", repaired.options().get(3).text());
        assertTrue(repaired.answerSource().contains("48549.html"));
        assertTrue(paper.questions().get(71).explanation().contains("＝“凝结”＋“集聚”"));
        assertTrue(paper.questions().get(8).stem().contains("结构上____新途径"));
        assertEquals("寻求", paper.questions().get(8).options().get(3).text().split(" ")[2]);
    }
}
