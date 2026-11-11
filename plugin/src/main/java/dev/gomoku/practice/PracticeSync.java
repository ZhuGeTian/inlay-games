package dev.gomoku.practice;

import com.google.gson.*;
import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;

/** 个人看板后台同步；设备令牌仅存 IDE 密码库。 */
public final class PracticeSync implements Disposable {
    public static final String URL = "https://zhugetian.com/study/";
    private static final CredentialAttributes CREDENTIAL = new CredentialAttributes("Moyu Study Dashboard");
    private final PracticeActivity activity = new PracticeActivity(PathManager.getConfigDir().resolve("moyu-practice/activity"));
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> { var t = new Thread(r,"moyu-study-sync"); t.setDaemon(true); return t; });
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    private volatile String status = "尚未连接个人看板；作答记录仅保存在本机。";
    private boolean started;
    private final java.util.concurrent.atomic.AtomicBoolean queued = new java.util.concurrent.atomic.AtomicBoolean();
    public static PracticeSync getInstance() { return ApplicationManager.getApplication().getService(PracticeSync.class); }
    // 项目启动时恢复补传；单线程保证配对、撤销和同步不会交错。
    public synchronized void start() {
        if (started) return; started = true;
        worker.scheduleWithFixedDelay(this::flush, 10, 60, TimeUnit.SECONDS);
    }
    // 必须先记录成功再提交，磁盘故障时允许用户重试而不是静默漏记。
    void record(PracticePaper paper, int index, String selected) throws IOException {
        activity.append(PracticeActivity.snapshot(paper, index, selected)); start();
        if (queued.compareAndSet(false, true)) worker.execute(() -> { try { flush(); } finally { queued.set(false); } });
    }
    // 后台访问密码库及网络，避免阻塞编辑器或触发主线程密码库限制。
    public void connect(String code, java.util.function.Consumer<String> done) {
        start(); worker.execute(() -> {
            try {
                var request = new JsonObject(); request.addProperty("code", code); request.addProperty("name", "IDE · " + java.time.LocalDate.now());
                String token = post("pair", request, null).get("token").getAsString();
                if (!token.matches("[\\w-]{40,100}")) throw new IOException("服务器返回的连接信息无效");
                PasswordSafe.getInstance().setPassword(CREDENTIAL, token); flush();
            } catch (Exception e) { status = safeError(e); }
            String message = status; ApplicationManager.getApplication().invokeLater(() -> done.accept(message));
        });
    }
    public void syncNow(java.util.function.Consumer<String> done) {
        start(); worker.execute(() -> {
            flush(); String message = status;
            try { message += "\n本机待同步：" + activity.count() + " 次。"; } catch (IOException ignored) { message += "\n无法读取本地记录。"; }
            String result = message; ApplicationManager.getApplication().invokeLater(() -> done.accept(result));
        });
    }
    public void disconnect(java.util.function.Consumer<String> done) {
        worker.execute(() -> {
            try { PasswordSafe.getInstance().setPassword(CREDENTIAL, null); status = "已断开；后续作答仍保存在本机，重新配对后补传。"; }
            catch (Exception e) { status = "未能清除连接信息，请在网页撤销这台设备。"; }
            String result = status; ApplicationManager.getApplication().invokeLater(() -> done.accept(result));
        });
    }
    // 每轮有界补传，断网及异常响应都保留发件箱；被撤销设备停止联网。
    private void flush() {
        try {
            String token = PasswordSafe.getInstance().getPassword(CREDENTIAL);
            if (token == null || token.isBlank()) { status = "尚未连接个人看板；作答记录仅保存在本机。"; return; }
            for (int i = 0; i < 20; i++) {
                var batch = activity.batch(); if (batch.isEmpty()) break;
                var request = new JsonObject(); request.add("attempts", batch);
                var response = post("attempts", request, token);
                activity.acknowledge(batch, response.getAsJsonArray("acknowledged"));
            }
            status = "已连接个人看板，最近同步：" + java.time.LocalDateTime.now().withNano(0);
        } catch (Exception e) { status = safeError(e); }
    }
    private JsonObject post(String path, JsonObject value, String token) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(URL + "api/" + path)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(value.toString()));
        if (token != null) request.header("Authorization", "Bearer " + token);
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            if (response.statusCode() != 200) {
                if (response.statusCode() == 401 && token != null) PasswordSafe.getInstance().setPassword(CREDENTIAL, null);
                throw new IOException(response.statusCode() == 401 ? "配对码已失效，或设备已被撤销，请重新连接。" : "同步服务返回 " + response.statusCode() + "，记录已保留，稍后自动重试。");
            }
            byte[] bytes = body.readNBytes(65537);
            if (bytes.length > 65536) throw new IOException("同步响应过大，记录已保留。");
            return JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    private static String safeError(Exception e) {
        if (e instanceof IOException && e.getMessage() != null && (e.getMessage().startsWith("配对码") || e.getMessage().startsWith("同步服务"))) return e.getMessage();
        return "暂时无法同步，记录已保存在本机；请检查网络及 IDE 密码库设置。";
    }
    @Override public void dispose() { worker.shutdownNow(); }
}
