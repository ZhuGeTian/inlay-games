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
            assertEquals(90, paper.questions().size());
            assertEquals(4, paper.questions().stream().filter(q -> q.type().equals("boolean")).count());
            assertEquals(2, paper.questions().stream().filter(q -> q.type().equals("multiple")).count());
            assertEquals(84, paper.questions().stream().filter(q -> q.type().equals("single")).count());
            assertTrue(paper.questions().stream().allMatch(q -> q.answer().isEmpty() && q.explanation().isEmpty()));
            for (int i = 70; i < 90; i++) {
                var q = paper.questions().get(i); assertFalse(q.material().isBlank());
                var first = paper.questions().get(70 + (i - 70) / 5 * 5);
                assertEquals(first.material(), q.material()); assertEquals(first.materialImages(), q.materialImages());
            }
            for (int i = 45; i < 50; i++) assertFalse(paper.questions().get(i).images().isEmpty());
        }
    }
}
