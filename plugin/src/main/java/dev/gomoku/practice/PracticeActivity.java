package dev.gomoku.practice;

import com.google.gson.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** 本地作答发件箱：先落盘，服务端确认后才删除。 */
final class PracticeActivity {
    private final Path directory;
    PracticeActivity(Path directory) { this.directory = directory; }

    // 冻结作答时的题目及答案，后续更新题库不会改写历史。
    static JsonObject snapshot(PracticePaper paper, int index, String selected) {
        var q = paper.questions().get(index);
        if (!q.validChoice(selected)) throw new IllegalArgumentException("无效作答");
        var value = new JsonObject();
        value.addProperty("eventId", UUID.randomUUID().toString());
        value.addProperty("occurredAt", System.currentTimeMillis());
        value.addProperty("paperId", paper.id()); value.addProperty("paperTitle", paper.title());
        value.addProperty("questionId", q.id()); value.addProperty("type", q.type());
        value.addProperty("section", PracticeSections.section(paper, index));
        value.addProperty("material", q.material()); value.addProperty("stem", q.stem());
        var options = new JsonArray(); q.options().forEach(o -> options.add(o.text())); value.add("options", options);
        value.addProperty("selected", selected); value.addProperty("answer", q.answer());
        value.addProperty("explanation", q.explanation()); value.addProperty("source", q.answerSource());
        value.addProperty("hasImages", !q.images().isEmpty() || !q.materialImages().isEmpty() || q.options().stream().anyMatch(o -> o.image() != null));
        return value;
    }
    // 原子替换并刷盘，避免退出或断网导致已提交作答丢失。
    synchronized void append(JsonObject value) throws IOException {
        Files.createDirectories(directory);
        try { Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------")); } catch (UnsupportedOperationException ignored) { }
        Path target = directory.resolve(value.get("eventId").getAsString() + ".json");
        Path temp = Files.createTempFile(directory, ".pending-", ".tmp");
        try {
            byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
            try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temp, target); }
        } finally { Files.deleteIfExists(temp); }
    }
    // 一次最多四条，最坏题干和解析体积仍低于服务端请求上限。
    synchronized JsonArray batch() throws IOException {
        var result = new JsonArray(); if (!Files.isDirectory(directory)) return result;
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().limit(4).toList()) {
                if (Files.size(file) > 512000) throw new IOException("本地作答文件过大");
                try { result.add(JsonParser.parseString(Files.readString(file)).getAsJsonObject()); }
                catch (RuntimeException e) { throw new IOException("本地作答文件无法读取，请保留文件并联系维护者", e); }
            }
        }
        return result;
    }
    // 只删除本次发送且被服务端明确确认的编号，异常响应保留整批数据。
    synchronized void acknowledge(JsonArray sent, JsonArray ids) throws IOException {
        var allowed = new HashSet<String>(); sent.forEach(e -> allowed.add(e.getAsJsonObject().get("eventId").getAsString()));
        var accepted = new HashSet<String>();
        for (var id : ids) {
            String text = id.getAsString();
            if (!allowed.contains(text) || !text.matches("[a-zA-Z0-9_-]{16,80}")) throw new IOException("同步确认无效");
            accepted.add(text);
        }
        if (accepted.isEmpty()) throw new IOException("服务器未确认作答，请稍后重试");
        for (String id : accepted) Files.deleteIfExists(directory.resolve(id + ".json"));
    }
    synchronized long count() throws IOException {
        if (!Files.isDirectory(directory)) return 0;
        try (var files = Files.list(directory)) { return files.filter(p -> p.getFileName().toString().endsWith(".json")).count(); }
    }
}
