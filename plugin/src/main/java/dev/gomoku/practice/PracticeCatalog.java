package dev.gomoku.practice;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Metadata only: listing papers must not decode every paper's charts on the UI thread. */
final class PracticeCatalog {
    record Entry(String id, String title, int count, boolean bundled) {
        @Override public String toString() { return title + (count > 0 ? " · " + count + " 题" : ""); }
    }
    static List<Entry> bundled() throws IOException {
        try (var input = PracticeCatalog.class.getResourceAsStream("/practice/papers/index.json")) {
            return input == null ? List.of() : parse(input.readNBytes(128 * 1024 + 1));
        }
    }
    static List<Entry> parse(byte[] bytes) throws IOException {
        if (bytes.length > 128 * 1024) throw new IOException("试卷目录过大。");
        try {
            var array = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonArray();
            if (array.size() > 100) throw new IllegalArgumentException("试卷目录最多 100 项。");
            var entries = new ArrayList<Entry>();
            var ids = new HashSet<String>();
            for (var element : array) {
                var object = element.getAsJsonObject();
                String id = object.get("id").getAsString(), title = object.get("title").getAsString();
                int count = object.get("questionCount").getAsInt();
                if (!PracticeSession.validId(id) || !ids.add(id) || title.isBlank() || title.length() > 200 || count < 1 || count > 500)
                    throw new IllegalArgumentException("试卷目录字段无效或重复。");
                entries.add(new Entry(id, title, count, true));
            }
            return List.copyOf(entries);
        } catch (RuntimeException e) { throw new IOException("无法读取试卷目录。", e); }
    }
    static List<Entry> entries(PracticeSession.SavedState saved) throws IOException {
        return merge(bundled(), saved);
    }
    static List<Entry> merge(List<Entry> bundled, PracticeSession.SavedState saved) {
        var entries = new HashMap<String, Entry>();
        for (var entry : bundled) entries.put(entry.id, entry);
        saved.titles.forEach((id, title) -> {
            if (PracticeSession.validId(id)) entries.putIfAbsent(id, new Entry(id, title, saved.counts.getOrDefault(id, 0), false));
        });
        return entries.values().stream().sorted(Comparator.comparing(Entry::title).reversed().thenComparing(Entry::id)).toList();
    }
    static InputStream open(String id) throws IOException {
        if (!PracticeSession.validId(id)) throw new IOException("试卷编号无效。");
        var input = PracticeCatalog.class.getResourceAsStream("/practice/papers/" + id + ".json");
        if (input == null) throw new IOException("安装包中没有这份试卷。");
        return input;
    }
}
