package dev.gomoku.practice;

import com.google.gson.*;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class PracticePaperTest {
    static byte[] sample() throws IOException {
        try (var input = PracticePaperTest.class.getResourceAsStream("/practice/sample-practice.json")) {
            return PracticePaper.readBytes(Objects.requireNonNull(input));
        }
    }
    static JsonObject json() throws IOException { return JsonParser.parseString(new String(sample(), StandardCharsets.UTF_8)).getAsJsonObject(); }
    static PracticePaper parse(JsonObject json) throws IOException { return PracticePaper.parse(json.toString().getBytes(StandardCharsets.UTF_8)); }
    @Test public void sampleIncludesMaterialsImageAndUnknownAnswer() throws Exception {
        var paper = PracticePaper.parse(sample());
        assertEquals(4, paper.questions().size());
        assertEquals("B", paper.questions().get(0).answer());
        assertFalse(paper.questions().get(1).material().isEmpty());
        assertEquals(280, paper.questions().get(2).images().get(0).getWidth());
        assertEquals("", paper.questions().get(3).answer());
        assertEquals(paper.id(), PracticePaper.parse(sample()).id());
        assertTrue(PracticeSession.validId(paper.id()));
    }
    @Test public void rejectsInvalidJsonAndShape() throws Exception {
        assertThrows(IOException.class, () -> PracticePaper.parse("{bad".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> PracticePaper.parse(new byte[]{(byte) 0xff}));
        assertThrows(IOException.class, () -> PracticePaper.parse("[".repeat(30).getBytes(StandardCharsets.UTF_8)));
        var json = json(); json.addProperty("formatVersion", 2);
        assertThrows(IOException.class, () -> parse(json));
        var duplicate = json(); var questions = duplicate.getAsJsonArray("questions"); questions.add(questions.get(0));
        assertThrows(IOException.class, () -> parse(duplicate));
        var answer = json(); answer.getAsJsonArray("questions").get(0).getAsJsonObject().addProperty("answer", "AB");
        assertThrows(IOException.class, () -> parse(answer));
        var options = json(); options.getAsJsonArray("questions").get(0).getAsJsonObject().getAsJsonArray("options").remove(0);
        assertThrows(IOException.class, () -> parse(options));
        var empty = json(); empty.add("questions", new JsonArray());
        assertThrows(IOException.class, () -> parse(empty));
    }
    @Test public void rejectsInvalidOrOversizedPicturesAndFiles() throws Exception {
        var json = json();
        var images = json.getAsJsonArray("questions").get(2).getAsJsonObject().getAsJsonArray("images");
        images.set(0, new JsonPrimitive("https://example.com/image.png"));
        assertThrows(IOException.class, () -> parse(json));
        images.set(0, new JsonPrimitive(Base64.getEncoder().encodeToString("garbage".getBytes(StandardCharsets.UTF_8))));
        assertThrows(IOException.class, () -> parse(json));
        var huge = new java.awt.image.BufferedImage(2001, 2000, java.awt.image.BufferedImage.TYPE_BYTE_BINARY);
        var out = new ByteArrayOutputStream(); javax.imageio.ImageIO.write(huge, "png", out);
        images.set(0, new JsonPrimitive(Base64.getEncoder().encodeToString(out.toByteArray())));
        assertThrows(IOException.class, () -> parse(json));
        assertThrows(IOException.class, () -> PracticePaper.parse(new byte[PracticePaper.MAX_BYTES + 1]));
        Path file = Files.createTempFile("practice-oversize-", ".json");
        try {
            try (var f = new RandomAccessFile(file.toFile(), "rw")) { f.setLength(PracticePaper.MAX_BYTES + 1L); }
            assertThrows(IOException.class, () -> PracticePaper.readBytes(file));
        } finally { Files.delete(file); }
    }
    @Test public void answerRequiresSubmissionAndUnknownNeverCountsAsWrong() throws Exception {
        var paper = PracticePaper.parse(sample()); var p = new PracticeProgress(); var q = paper.questions().get(0);
        assertFalse(p.submit(q));
        p.select(q, "A"); assertEquals("", p.result(q)); assertTrue(p.mistakes.isEmpty());
        assertTrue(p.submit(q)); assertTrue(p.result(q).startsWith("错误")); assertEquals(List.of(q.id()), p.mistakes);
        p.select(q, "B"); assertEquals("A", p.choices.get(q.id())); assertFalse(p.submit(q));
        p.retry(q); assertEquals("", p.result(q)); p.select(q, "B"); p.submit(q);
        assertTrue(p.result(q).startsWith("正确")); assertEquals(List.of(q.id()), p.mistakes);
        var unknown = paper.questions().get(3); p.select(unknown, "D"); p.submit(unknown);
        assertTrue(p.result(unknown).contains("未提供答案")); assertEquals(1, p.mistakes.size());
    }
    @Test public void wrongFilterAndCopyKeepPaperProgressIndependent() throws Exception {
        var paper = PracticePaper.parse(sample()); var p = new PracticeProgress();
        p.move(paper, 100); assertEquals(3, p.index); p.move(paper, -100); assertEquals(0, p.index);
        p.onlyWrong = true; assertTrue(p.indices(paper).isEmpty()); p.move(paper, 1); assertEquals(0, p.index);
        var q = paper.questions().get(2); p.select(q, "D"); p.submit(q); p.normalize(paper); assertEquals(2, p.index);
        p.move(paper, -1); assertEquals(2, p.index); assertEquals(List.of(2), p.indices(paper));
        var copy = p.copy(); copy.retry(q); assertEquals("D", p.submitted.get(q.id()));
        p.choices.put("absent", "Z"); p.index = -1; p.normalize(paper); assertFalse(p.choices.containsKey("absent")); assertEquals(2, p.index);
    }
    @Test public void booleanAndMultipleSelectionValidationAndExactScoring() throws Exception {
        var json = json(); var questions = json.getAsJsonArray("questions");
        var bool = questions.get(0).getAsJsonObject(); bool.addProperty("type", "boolean");
        bool.getAsJsonArray("options").remove(3); bool.getAsJsonArray("options").remove(2);
        var multi = questions.get(1).getAsJsonObject(); multi.addProperty("type", "multiple"); multi.addProperty("answer", "DB");
        var paper = parse(json); var p = new PracticeProgress();
        var q = paper.questions().get(0); assertEquals("boolean", q.type());
        p.select(q, "C"); assertTrue(p.choices.isEmpty()); p.select(q, "A"); p.select(q, "B"); p.submit(q);
        assertTrue(p.result(q).startsWith("正确"));
        q = paper.questions().get(1); assertEquals("BD", q.answer());
        p.select(q, "D"); p.select(q, "B"); assertEquals("BD", p.choices.get(q.id()));
        p.select(q, "D"); assertEquals("B", p.choices.get(q.id())); p.submit(q);
        assertTrue(p.result(q).startsWith("错误")); // Partial selection scores no credit.
        p.retry(q); p.select(q, "D"); p.select(q, "B"); p.select(q, "A"); p.submit(q);
        assertTrue(p.result(q).startsWith("错误")); // Extra selection scores no credit.
        p.retry(q); p.select(q, "D"); p.select(q, "B"); p.submit(q); assertTrue(p.result(q).startsWith("正确"));
        p.normalize(paper); assertEquals("BD", p.submitted.get(q.id()));
        p.retry(q); p.select(q, "B"); p.select(q, "B"); assertFalse(p.submit(q));
        multi.addProperty("answer", "BB"); assertThrows(IOException.class, () -> parse(json));
        multi.addProperty("answer", "BD"); bool.addProperty("answer", "C"); assertThrows(IOException.class, () -> parse(json));
    }

}
