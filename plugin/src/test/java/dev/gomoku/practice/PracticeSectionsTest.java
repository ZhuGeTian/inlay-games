package dev.gomoku.practice;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public final class PracticeSectionsTest {
    // 两份实际题库按原题号划分；修改后的未知文件不套用旧题号映射。
    @Test public void bundledSectionsHaveExactBoundariesWithoutChangingIdentity() throws Exception {
        for (var entry : PracticeCatalog.bundled()) {
            try (var input = PracticeCatalog.open(entry.id())) {
                var paper = PracticePaper.parse(PracticePaper.readBytes(input));
                assertEquals(entry.id(), paper.id());
                if (entry.count() == 225) { assertEquals(225, PracticeSections.count(paper, "言语理解")); continue; }
                assertEquals(10, PracticeSections.count(paper, "政治理论"));
                assertEquals(5, PracticeSections.count(paper, "常识"));
                assertEquals(15, PracticeSections.count(paper, "言语理解"));
                assertEquals("政治理论", PracticeSections.section(paper, 9));
                assertEquals("常识", PracticeSections.section(paper, 10));
                assertEquals("常识", PracticeSections.section(paper, 14));
                assertEquals("言语理解", PracticeSections.section(paper, 15));
                assertEquals("言语理解", PracticeSections.section(paper, 29));
                assertEquals("", PracticeSections.section(paper, 30));
                var other = new PracticePaper("0".repeat(64), paper.title(), paper.questions());
                assertEquals(0, PracticeSections.count(other, "政治理论"));
            }
        }
    }
    // 不选答案也能先看答案，未提供答案的题不猜测、不计入错题。
    @Test public void cardSequenceSkipsOtherSectionsAndStopsAtLastAnswer() throws Exception {
        var json = PracticePaperTest.json(); var questions = json.getAsJsonArray("questions");
        questions.get(0).getAsJsonObject().addProperty("section", "言语理解与表达");
        questions.get(1).getAsJsonObject().addProperty("section", "常识判断");
        questions.get(3).getAsJsonObject().addProperty("section", "言语理解");
        var paper = PracticePaperTest.parse(json); var p = new PracticeProgress(); p.section = "言语理解";
        p.normalize(paper); assertEquals(List.of(0, 3), p.indices(paper));
        assertFalse(p.canAdvance(paper, -1));
        p.advance(paper, 1); assertTrue(p.answerVisible); assertEquals(0, p.index);
        assertTrue(p.submitted.isEmpty()); assertTrue(p.mistakes.isEmpty());
        p.advance(paper, 1); assertFalse(p.answerVisible); assertEquals(3, p.index);
        p.select(paper.questions().get(3), "D"); p.advance(paper, 1);
        assertTrue(p.answerVisible); assertTrue(p.result(paper.questions().get(3)).contains("未提供答案"));
        assertFalse(p.canAdvance(paper, 1)); p.advance(paper, 1); assertEquals(3, p.index);
        p.advance(paper, -1); assertFalse(p.answerVisible); p.advance(paper, -1); assertTrue(p.answerVisible); assertEquals(0, p.index);
        assertTrue(p.mistakes.isEmpty()); assertEquals("常识", paper.questions().get(1).section());
    }
    // 错题筛选保持在当前板块，归一化及复制保留阶段和隔离状态。
    @Test public void sectionAndWrongFiltersIntersect() throws Exception {
        var json = PracticePaperTest.json(); var qs = json.getAsJsonArray("questions");
        qs.get(0).getAsJsonObject().addProperty("section", "政治理论");
        qs.get(1).getAsJsonObject().addProperty("section", "常识");
        var paper = PracticePaperTest.parse(json); var p = new PracticeProgress();
        p.mistakes.add(paper.questions().get(0).id()); p.mistakes.add(paper.questions().get(1).id());
        p.section = "常识"; p.onlyWrong = true; p.normalize(paper);
        assertEquals(List.of(1), p.indices(paper)); assertEquals(1, p.index);
        p.advance(paper, 1); var copy = p.copy(); assertTrue(copy.answerVisible); assertEquals("常识", copy.section);
        copy.section = "言语理解"; copy.normalize(paper); assertTrue(copy.indices(paper).isEmpty()); assertFalse(copy.answerVisible);
        assertEquals("常识", p.section);
    }
}
