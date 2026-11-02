package dev.gomoku.practice;

import com.google.gson.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Validated local question bank. Images are embedded; no paths or URLs are fetched. */
public record PracticePaper(String id, String title, List<Question> questions) {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    public record Option(String text, BufferedImage image) {}
    public record Question(String id, String material, List<BufferedImage> materialImages, String stem,
                           List<BufferedImage> images, List<Option> options, String answer, String explanation) {}

    public static byte[] readBytes(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) { return readBytes(input); }
    }

    public static byte[] readBytes(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) throw new IOException("题库不能超过 16 MB。");
        return bytes;
    }

    public static PracticePaper parse(byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("题库不能超过 16 MB。");
        try {
            String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            checkDepth(json);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("formatVersion") || !root.get("formatVersion").isJsonPrimitive()
                    || !root.getAsJsonPrimitive("formatVersion").isNumber()
                    || !root.get("formatVersion").getAsString().equals("1")) fail("仅支持 formatVersion=1。");
            String title = text(root, "title", true, 200);
            JsonArray entries = array(root, "questions", true, 500);
            if (entries.isEmpty()) fail("题库至少需要一道题。");
            var ids = new HashSet<String>();
            var questions = new ArrayList<Question>();
            var decoder = new Images();
            for (JsonElement element : entries) {
                JsonObject q = element.getAsJsonObject();
                String id = text(q, "id", true, 80);
                if (!id.matches("[a-zA-Z0-9_-]+") || !ids.add(id)) fail("题号 id 必须唯一，只能包含字母、数字、下划线和横线。");
                JsonArray rawOptions = array(q, "options", true, 4);
                if (rawOptions.size() != 4) fail("题目 " + id + " 必须有 A/B/C/D 四个选项。");
                var options = new ArrayList<Option>();
                for (JsonElement raw : rawOptions) {
                    JsonObject option = raw.getAsJsonObject();
                    String label = text(option, "text", false, 8000);
                    BufferedImage image = decoder.image(text(option, "image", false, MAX_BYTES));
                    if (label.isBlank() && image == null) fail("选项不能同时缺少文字和图片。");
                    options.add(new Option(label, image));
                }
                String answer = text(q, "answer", false, 4);
                if (!answer.isEmpty() && !answer.matches("[A-D]")) fail("答案必须是 A/B/C/D 之一，或留空。");
                questions.add(new Question(id, text(q, "material", false, 40000), decoder.list(q, "materialImages"),
                        text(q, "stem", true, 16000), decoder.list(q, "images"), List.copyOf(options), answer,
                        text(q, "explanation", false, 40000)));
            }
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            return new PracticePaper(hash, title, List.copyOf(questions));
        } catch (JsonParseException | IllegalArgumentException | IllegalStateException | UnsupportedOperationException | NoSuchAlgorithmException e) {
            throw new IOException("题库格式错误：" + e.getMessage(), e);
        }
    }

    private static void checkDepth(String json) {
        boolean quoted = false, escaped = false;
        int depth = 0;
        for (char ch : json.toCharArray()) {
            if (quoted) {
                if (escaped) escaped = false;
                else if (ch == '\\') escaped = true;
                else if (ch == '"') quoted = false;
            } else if (ch == '"') quoted = true;
            else if (ch == '{' || ch == '[') { if (++depth > 24) fail("JSON 嵌套过深。"); }
            else if (ch == '}' || ch == ']') depth--;
        }
    }

    private static String text(JsonObject o, String key, boolean required, int max) {
        JsonElement value = o.get(key);
        if (value == null || value.isJsonNull()) {
            if (required) fail("缺少 " + key);
            return "";
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) fail(key + " 必须是字符串。");
        String result = value.getAsString().strip();
        if (result.length() > max || required && result.isBlank()) fail(key + " 为空或超过长度限制。");
        return result;
    }

    private static JsonArray array(JsonObject o, String key, boolean required, int max) {
        if (!o.has(key) && !required) return new JsonArray();
        JsonElement value = o.get(key);
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() > max) fail(key + " 数量或格式错误。");
        return value.getAsJsonArray();
    }

    private static void fail(String message) { throw new IllegalArgumentException(message); }

    private static final class Images {
        private final Map<String, BufferedImage> cache = new HashMap<>();
        private long pixels;
        List<BufferedImage> list(JsonObject q, String key) throws IOException {
            var result = new ArrayList<BufferedImage>();
            for (JsonElement e : array(q, key, false, 8)) {
                if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) fail("图片必须是 Base64 字符串。");
                BufferedImage image = image(e.getAsString());
                if (image == null) fail("图片不能为空。");
                result.add(image);
            }
            return List.copyOf(result);
        }
        BufferedImage image(String data) throws IOException {
            if (data.isEmpty()) return null;
            if (cache.containsKey(data)) return cache.get(data);
            byte[] bytes = Base64.getDecoder().decode(data);
            if (bytes.length > 4 * 1024 * 1024) fail("单张图片不能超过 4 MB。");
            try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) fail("无法识别图片，请提供 PNG/JPEG Base64。");
                var reader = readers.next();
                try {
                    String format = reader.getFormatName();
                    if (!format.equalsIgnoreCase("png") && !format.equalsIgnoreCase("jpeg")) fail("只支持 PNG/JPEG 图片。");
                    reader.setInput(input, true, true);
                    long size = (long) reader.getWidth(0) * reader.getHeight(0);
                    if (size <= 0 || size > 4_000_000 || pixels + size > 16_000_000) fail("图片像素过大（单图 400 万、题库总计 1600 万）。");
                    pixels += size;
                    BufferedImage image = reader.read(0);
                    if (image == null) fail("图片解码失败。");
                    cache.put(data, image);
                    return image;
                } finally { reader.dispose(); }
            }
        }
    }
}
