package dev.gomoku.practice;

import com.google.gson.*;
import org.junit.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.Assert.*;

public final class PracticeReviewTest {
    @Test public void referenceOverlayPreservesIdentityAndRejectsWrongIdsAndAnswers() throws Exception {
        var paper = PracticePaperTest.parse(PracticePaperTest.json());
        var entries = JsonParser.parseString("{\"sample-4\":{\"answer\":\"B\",\"explanation\":\"核对说明\",\"source\":\"非官方 https://example.com/answer\"}}").getAsJsonObject();
        var result = PracticeAnswers.apply(paper, entries);
        assertEquals(paper.id(), result.id()); assertEquals(paper.title(), result.title());
        assertSame(paper.questions().get(0), result.questions().get(0));
        var q = result.questions().get(3); assertEquals("B", q.answer()); assertEquals("参考答案", q.answerLabel());
        assertEquals(paper.questions().get(3).stem(), q.stem()); assertSame(paper.questions().get(3).options(), q.options());
        entries.getAsJsonObject("sample-4").addProperty("stem", "补正后的____题干");
        assertEquals("补正后的____题干", PracticeAnswers.apply(paper, entries).questions().get(3).stem());
        entries.getAsJsonObject("sample-4").addProperty("stem", " ");
        assertThrows(IOException.class, () -> PracticeAnswers.apply(paper, entries));
        entries.getAsJsonObject("sample-4").remove("stem");
        entries.getAsJsonObject("sample-4").addProperty("answer", "Z");
        assertThrows(IOException.class, () -> PracticeAnswers.apply(paper, entries));
        entries.getAsJsonObject("sample-4").addProperty("answer", "");
        entries.getAsJsonObject("sample-4").addProperty("source", "");
        assertThrows(IOException.class, () -> PracticeAnswers.apply(paper, entries));
        entries.remove("sample-4"); entries.add("missing", new JsonObject());
        assertThrows(IOException.class, () -> PracticeAnswers.apply(paper, entries));
    }
    @Test public void oldSubmittedAnswersAreRegradedButPendingAndUnsubmittedAreNotMistakes() throws Exception {
        var paper = PracticePaperTest.parse(PracticePaperTest.json()); var p = new PracticeProgress();
        p.submitted.put("sample-1", "A"); p.submitted.put("sample-4", "D"); p.choices.put("sample-2", "A"); p.page = -3;
        p.normalize(paper); assertEquals(List.of("sample-1"), p.mistakes); assertEquals(0, p.page);
        p.retry(paper.questions().get(0)); p.select(paper.questions().get(0), "B"); p.submit(paper.questions().get(0));
        p.normalize(paper); assertEquals(List.of("sample-1"), p.mistakes);
    }
    @Test public void mistakesUniteSectionsWithoutMixingIdenticalQuestionIdsAcrossPapers() throws Exception {
        var json = PracticePaperTest.json(); json.getAsJsonArray("questions").get(0).getAsJsonObject().addProperty("section", "政治理论");
        var paper = PracticePaperTest.parse(json); var state = new PracticeSession.SavedState();
        var whole = new PracticeProgress(); whole.mistakes.add("sample-1");
        var section = new PracticeProgress(); section.section = "政治理论"; section.submitted.put("sample-1", "A");
        state.progress.put(paper.id(), whole); state.progress.put(paper.id() + ":政治理论", section);
        var found = PracticeMistakes.entries(state, paper); assertEquals(1, found.size()); assertEquals("", found.get(0).section());
        var other = new PracticePaper("c".repeat(64), "另一卷", paper.questions());
        state.progress.put(other.id(), section.copy()); assertEquals(1, PracticeMistakes.entries(state, other).size());
        assertNotEquals(found.get(0).paperId(), PracticeMistakes.entries(state, other).get(0).paperId());
        assertTrue(section.mistakes.isEmpty()); // 汇总快照不修改正在作答的状态。
        state.progress.remove(paper.id()); found = PracticeMistakes.entries(state, paper);
        assertEquals("政治理论", found.get(0).section()); assertTrue(found.get(0).label().contains("第 1 题"));
    }
}
