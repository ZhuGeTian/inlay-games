package dev.gomoku.practice;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bundled reference answers augment exact paper hashes without changing cached paper identity. */
final class PracticeAnswers {
    static PracticePaper apply(PracticePaper paper) throws IOException {
        try (var input = PracticeAnswers.class.getResourceAsStream("/practice/answers/" + paper.id() + ".json")) {
            if (input == null) return paper;
            byte[] bytes = input.readNBytes(128 * 1024 + 1);
            if (bytes.length > 128 * 1024) throw new IOException("参考答案文件过大。");
            return apply(paper, JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (RuntimeException e) { throw new IOException("参考答案格式错误。", e); }
    }
    // 每个补充项必须对应原题，答案合法且注明来源；不覆盖题库已有答案。
    static PracticePaper apply(PracticePaper paper, JsonObject entries) throws IOException {
        var ids = new HashSet<String>();
        var questions = new ArrayList<PracticePaper.Question>();
        for (var q : paper.questions()) {
            ids.add(q.id());
            if (!entries.has(q.id())) { questions.add(q); continue; }
            var value = entries.getAsJsonObject(q.id());
            String answer = value.get("answer").getAsString(), explanation = value.get("explanation").getAsString(), source = value.get("source").getAsString();
            String stem = value.has("stem") ? value.get("stem").getAsString().strip() : q.stem();
            if (stem.isBlank() || stem.length() > 16000) throw new IOException("补正题干无效：" + q.id());
            if (!q.answer().isEmpty() || !answer.isEmpty() && (!q.validChoice(answer) || q.type().equals("multiple") && answer.length() < 2)
                    || source.isBlank() || source.length() > 4000 || explanation.isBlank() || explanation.length() > 40000)
                throw new IOException("参考答案字段无效：" + q.id());
            questions.add(new PracticePaper.Question(q.id(), q.type(), q.material(), q.materialImages(), stem, q.images(), q.options(), answer,
                    explanation, q.section(), source));
        }
        if (!ids.containsAll(entries.keySet())) throw new IOException("参考答案题号与原题库不匹配。");
        return new PracticePaper(paper.id(), paper.title(), List.copyOf(questions));
    }
}
