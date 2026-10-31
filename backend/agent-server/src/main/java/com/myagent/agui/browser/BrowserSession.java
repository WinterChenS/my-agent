package com.myagent.agui.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一个用户的浏览器会话：**一个真实运行的 headless Chromium 进程** + 一个页面 target。
 *
 * <p>生命周期：{@link #open(String)} 起进程 → attach target → 开 screencast；
 * 空闲超时由 {@link BrowserSessionRegistry} 回收；{@link #close()} 杀进程。
 *
 * <p>帧广播：screencast 只在该页面**有变化时**推帧（这正是我们要的），这里把帧交给
 * {@link FrameListener}（SSE 订阅者），并按 maxFps 做节流，避免把带宽吃满。
 */
public class BrowserSession {

    private static final Logger log = LoggerFactory.getLogger(BrowserSession.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 帧订阅者：收到 JPEG 的 base64。 */
    public interface FrameListener {
        void onFrame(String base64Jpeg, int width, int height);
    }

    private final String userId;
    private final BrowserProperties properties;
    private final Path profileDir;
    private final List<FrameListener> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService executor;

    private Process process;
    private CdpClient cdp;
    private String targetId;
    private String sessionId;
    private int devToolsPort;
    private volatile String currentUrl = "about:blank";
    private volatile String currentTitle = "";
    private volatile String lastFrame;
    /** 当前真实视口尺寸（用户拖拽面板时会变） */
    private volatile int viewportWidth;
    private volatile int viewportHeight;
    private volatile int frameWidth;
    private volatile int frameHeight;
    private volatile long lastFrameAt;
    private volatile long lastFramePushedAt;
    private volatile boolean userControlled;
    /** 用户最后一次操作的时刻：用户接管超过一段时间没动作，就自动还给 Agent */
    private volatile long userControlledAt;
    private volatile long lastActiveAt = System.currentTimeMillis();
    /** 创建时间：给"刚启动就别回收"用（防止回收线程把正在初始化的会话杀掉） */
    private final long startedAt = System.currentTimeMillis();
    private final AtomicReference<String> statusText = new AtomicReference<>("idle");

    public BrowserSession(String userId, BrowserProperties properties, Path profileDir) {
        this.userId = userId;
        this.properties = properties;
        this.profileDir = profileDir;
        // 注意：线程名要用到 userId，所以不能写在字段初始化里（那时 userId 还没赋值）
        this.viewportWidth = properties.viewportWidth();
        this.viewportHeight = properties.viewportHeight();
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "browser-session-" + userId);
            thread.setDaemon(true);
            return thread;
        });
    }

    public String userId() {
        return userId;
    }

    public boolean isAlive() {
        return process != null && process.isAlive() && cdp != null && cdp.isOpen();
    }

    public String currentUrl() {
        return currentUrl;
    }

    public String currentTitle() {
        return currentTitle;
    }

    public String lastFrame() {
        return lastFrame;
    }

    public int frameWidth() {
        return frameWidth;
    }

    public int viewportWidth() {
        return viewportWidth;
    }

    public int viewportHeight() {
        return viewportHeight;
    }

    public int frameHeight() {
        return frameHeight;
    }

    public String statusText() {
        return statusText.get();
    }

    public long lastActiveAt() {
        return lastActiveAt;
    }

    public long startedAt() {
        return startedAt;
    }

    public boolean userControlled() {
        return userControlled;
    }

    public void setUserControlled(boolean value) {
        this.userControlled = value;
        this.userControlledAt = System.currentTimeMillis();
        this.lastActiveAt = this.userControlledAt;
    }

    /**
     * 用户是否正在"占着"页面（Agent 的工具应该让路）。
     *
     * <p>带自动过期：用户点了页面之后如果 60 秒没再操作，就自动还给 Agent，
     * 否则 Agent 会被永久挡住（用户点了下页面就去干别的了，这是常见情况）。
     */
    public boolean isUserBlocking() {
        return userControlled && (System.currentTimeMillis() - userControlledAt < 60_000);
    }

    public void touch() {
        this.lastActiveAt = System.currentTimeMillis();
    }

    public void addFrameListener(FrameListener listener) {
        listeners.add(listener);
        // 新订阅者先补一帧，避免打开面板时白屏等着
        String frame = lastFrame;
        if (frame != null) {
            listener.onFrame(frame, frameWidth, frameHeight);
        }
    }

    public void removeFrameListener(FrameListener listener) {
        listeners.remove(listener);
    }

    // ===================== 启停 =====================

    /** 拉起 Chromium 并 attach 页面。已启动则直接复用。 */
    public synchronized void ensureStarted() {
        if (isAlive()) {
            return;
        }
        try {
            startProcess();
            attachPage();
            startScreencast();
            installEventHooks();
            statusText.set("ready");
            log.info("浏览器已启动 userId={} port={} target={}", userId, devToolsPort, targetId);
        } catch (RuntimeException e) {
            statusText.set("failed: " + e.getMessage());
            log.warn("浏览器启动失败 userId={}: {}", userId, e.toString());
            close();
            throw e;
        }
    }

    private void startProcess() {
        try {
            Files.createDirectories(profileDir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建浏览器 profile 目录：" + profileDir, e);
        }
        int port = freePort();
        List<String> command = new ArrayList<>(List.of(
                properties.chromeBinary(),
                // chrome-headless-shell 本身已是无头版本，不要再传 --headless=new
                "--remote-debugging-port=" + port,
                "--remote-debugging-address=127.0.0.1",
                "--user-data-dir=" + profileDir.toAbsolutePath(),
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--disable-gpu",
                "--hide-scrollbars",
                "--mute-audio",
                "--no-first-run",
                "--no-default-browser-check",
                "--window-size=" + properties.viewportWidth() + "," + properties.viewportHeight(),
                "about:blank"));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "启动浏览器失败（二进制 " + properties.chromeBinary() + " 不存在或不可执行）", e);
        }
        devToolsPort = port;

        // 等 DevTools 端口就绪：读进程输出的 "DevTools listening on ws://..."
        Thread reader = new Thread(() -> {
            try (BufferedReader input = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                    if (line.contains("DevTools listening on")) {
                        statusText.set("devtools-ready");
                    }
                    log.debug("[chrome] {}", line);
                }
            } catch (IOException ignored) {
                // 进程结束时流会关闭
            }
        }, "chrome-log-" + userId);
        reader.setDaemon(true);
        reader.start();

        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException("浏览器进程已退出（退出码 " + process.exitValue() + "）");
            }
            String url = browserWebSocketUrl();
            if (url != null) {
                cdp = new CdpClient();
                cdp.connect(url);
                return;
            }
            sleep(120);
        }
        throw new IllegalStateException("浏览器 DevTools 端口未就绪（20s 超时）");
    }

    private void attachPage() {
        ObjectNode createParams = MAPPER.createObjectNode();
        createParams.put("url", "about:blank");
        JsonNode created = cdp.send("Target.createTarget", createParams, null);
        targetId = created.path("targetId").asText();
        if (targetId.isBlank()) {
            throw new IllegalStateException("CDP 创建页面失败：" + created);
        }
        ObjectNode attachParams = MAPPER.createObjectNode();
        attachParams.put("targetId", targetId);
        attachParams.put("flatten", true);
        JsonNode attached = cdp.send("Target.attachToTarget", attachParams, null);
        sessionId = attached.path("sessionId").asText();
        if (sessionId.isBlank()) {
            throw new IllegalStateException("CDP attach 页面失败：" + attached);
        }
        cdp.send("Page.enable", MAPPER.createObjectNode(), sessionId);
        cdp.send("Runtime.enable", MAPPER.createObjectNode(), sessionId);
        this.viewportWidth = properties.viewportWidth();
        this.viewportHeight = properties.viewportHeight();
        ObjectNode metrics = MAPPER.createObjectNode();
        metrics.put("width", properties.viewportWidth());
        metrics.put("height", properties.viewportHeight());
        metrics.put("deviceScaleFactor", 1);
        metrics.put("mobile", false);
        try {
            cdp.send("Emulation.setDeviceMetricsOverride", metrics, sessionId);
        } catch (RuntimeException e) {
            log.debug("设置视口失败（不影响使用）：{}", e.getMessage());
        }
    }

    private void startScreencast() {
        cdp.on("Page.screencastFrame", (method, params) -> {
            String data = params.path("data").asText("");
            if (data.isEmpty()) {
                return;
            }
            JsonNode metadata = params.path("metadata");
            int width = metadata.path("deviceWidth").asInt(properties.viewportWidth());
            int height = metadata.path("deviceHeight").asInt(properties.viewportHeight());
            lastFrame = data;
            frameWidth = width;
            frameHeight = height;
            lastFrameAt = System.currentTimeMillis();
            // 按 maxFps 节流：screencast 是"有变化就推"，20fps 的视频页会非常费带宽
            long minInterval = Math.max(1, 1000 / Math.max(1, properties.maxFps()));
            if (lastFrameAt - lastFramePushedAt >= minInterval) {
                lastFramePushedAt = lastFrameAt;
                for (FrameListener listener : listeners) {
                    try {
                        listener.onFrame(data, width, height);
                    } catch (RuntimeException ignored) {
                        // 单个订阅者出错不影响别人
                    }
                }
            }
            // 必须 ack，否则 Chrome 不再推后续帧。
            // 注意用 fire（不等响应）：这里在事件回调里，同步等会堵死接收线程（实测导致所有命令超时）
            ObjectNode ack = MAPPER.createObjectNode();
            ack.put("sessionId", params.path("sessionId").asInt());
            cdp.fire("Page.screencastFrameAck", ack, sessionId);
        });
        ObjectNode params = MAPPER.createObjectNode();
        params.put("format", "jpeg");
        params.put("quality", properties.jpegQuality());
        params.put("maxWidth", properties.viewportWidth());
        params.put("maxHeight", properties.viewportHeight());
        params.put("everyNthFrame", 1);
        cdp.send("Page.startScreencast", params, sessionId);
    }

    private void installEventHooks() {
        cdp.on("Page.frameNavigated", (method, node) -> {
            JsonNode frame = node.path("frame");
            if (frame.has("parentId")) {
                return; // 只关心主框架
            }
            String url = frame.path("url").asText("");
            if (!url.isBlank() && !"about:blank".equals(url)) {
                currentUrl = url;
            }
            lastActiveAt = System.currentTimeMillis();
        });
        // 标题变化没有专门事件，靠导航后主动取一次 + 每次操作后刷新
        refreshTitleAsync();
    }

    // ===================== 操作 =====================

    /** 导航到某个 URL（调用方负责做 URL 安全校验）。 */
    public void navigate(String url) {
        ensureStarted();
        touch();
        ObjectNode params = MAPPER.createObjectNode();
        params.put("url", url);
        try {
            cdp.send("Page.navigate", params, sessionId);
        } catch (RuntimeException e) {
            throw new IllegalStateException("打开页面失败：" + e.getMessage());
        }
        currentUrl = url;
        sleep(400); // 给首帧一点时间，避免工具返回时画面还是空白
        refreshTitle();
    }

    /** 真实鼠标点击（会触发页面自己的 hover/按下效果，用户在画面上看得到）。 */
    public void click(int x, int y) {
        ensureStarted();
        touch();
        dispatchMouse("mouseMoved", x, y, 0);
        dispatchMouse("mousePressed", x, y, 1);
        dispatchMouse("mouseReleased", x, y, 1);
    }

    /** 滚动（像素）。 */
    public void scroll(int x, int y, int deltaY) {
        ensureStarted();
        touch();
        ObjectNode params = MAPPER.createObjectNode();
        params.put("type", "mouseWheel");
        params.put("x", x);
        params.put("y", y);
        params.put("deltaX", 0);
        params.put("deltaY", deltaY);
        cdp.send("Input.dispatchMouseEvent", params, sessionId);
    }

    /** 输入文本（走 Input.insertText，中文/输入法安全）。 */
    public void insertText(String text) {
        ensureStarted();
        touch();
        ObjectNode params = MAPPER.createObjectNode();
        params.put("text", text);
        cdp.send("Input.insertText", params, sessionId);
    }

    /** 按键（Enter/Tab/Backspace 等快捷键）。 */
    public void pressKey(String key) {
        ensureStarted();
        touch();
        int code = switch (key) {
            case "Enter" -> 13;
            case "Tab" -> 9;
            case "Backspace" -> 8;
            case "Escape" -> 27;
            case "ArrowDown" -> 40;
            case "ArrowUp" -> 38;
            default -> 0;
        };
        ObjectNode down = MAPPER.createObjectNode();
        down.put("type", "keyDown");
        down.put("key", key);
        down.put("code", key);
        down.put("windowsVirtualKeyCode", code);
        cdp.send("Input.dispatchKeyEvent", down, sessionId);
        ObjectNode up = MAPPER.createObjectNode();
        up.put("type", "keyUp");
        up.put("key", key);
        up.put("code", key);
        up.put("windowsVirtualKeyCode", code);
        cdp.send("Input.dispatchKeyEvent", up, sessionId);
    }

    private void dispatchMouse(String type, int x, int y, int clickCount) {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("type", type);
        params.put("x", x);
        params.put("y", y);
        params.put("button", "left");
        params.put("clickCount", clickCount);
        cdp.send("Input.dispatchMouseEvent", params, sessionId);
    }

    /**
     * 改真实视口尺寸（用户拖拽面板宽度时调用）。
     *
     * <p>这样页面会像真浏览器窗口一样 **reflow**：画面 1:1 填满面板，不会出现上下黑边。
     */
    public void setViewport(int width, int height) {
        ensureStarted();
        touch();
        int w = Math.max(320, Math.min(width, 3840));
        int h = Math.max(240, Math.min(height, 2400));
        this.viewportWidth = w;
        this.viewportHeight = h;
        ObjectNode metrics = MAPPER.createObjectNode();
        metrics.put("width", w);
        metrics.put("height", h);
        metrics.put("deviceScaleFactor", 1);
        metrics.put("mobile", false);
        try {
            cdp.send("Emulation.setDeviceMetricsOverride", metrics, sessionId);
            // 主动扇一下 resize：让页面重新布局并触发一次 screencast 推帧
            // （screencast 只在"画面有变化"时推帧，不触发的话拖拽后画面会停在旧的一帧）
            ObjectNode eval = MAPPER.createObjectNode();
            eval.put("expression", "window.dispatchEvent(new Event('resize'))");
            eval.put("returnByValue", true);
            cdp.send("Runtime.evaluate", eval, sessionId);
        } catch (RuntimeException e) {
            log.debug("设置视口失败：{}", e.getMessage());
        }
    }

    /** 在页面里求值（只读，用于取正文/标题/元素坐标）。 */
    public JsonNode evaluate(String expression) {
        ensureStarted();
        touch();
        ObjectNode params = MAPPER.createObjectNode();
        params.put("expression", expression);
        params.put("returnByValue", true);
        params.put("awaitPromise", true);
        try {
            JsonNode result = cdp.send("Runtime.evaluate", params, sessionId);
            JsonNode exception = result.path("exceptionDetails");
            if (!exception.isMissingNode() && !exception.isNull()) {
                throw new IllegalStateException("页面脚本出错：" + exception.path("text").asText(""));
            }
            return result.path("result").path("value");
        } catch (RuntimeException e) {
            throw new IllegalStateException("读取页面失败：" + e.getMessage());
        }
    }

    /** CSS 选择器 → 元素中心坐标（用于"点用户看得见的那个按钮"）。 */
    public int[] elementCenter(String selector) {
        String expression = """
                (() => {
                  const el = document.querySelector(%s);
                  if (!el) return null;
                  el.scrollIntoView({block:'center'});
                  const r = el.getBoundingClientRect();
                  return [Math.round(r.left + r.width / 2), Math.round(r.top + r.height / 2)];
                })()
                """.formatted(MAPPER.getNodeFactory().textNode(selector));
        JsonNode value = evaluate(expression);
        if (value == null || value.isNull() || !value.isArray() || value.size() < 2) {
            throw new IllegalStateException("页面上没找到这个元素：" + selector);
        }
        return new int[] {value.get(0).asInt(), value.get(1).asInt()};
    }

    /** 截图（PNG base64），用于作为产物交付。 */
    public String screenshotBase64() {
        ensureStarted();
        touch();
        ObjectNode params = MAPPER.createObjectNode();
        params.put("format", "png");
        JsonNode result = cdp.send("Page.captureScreenshot", params, sessionId);
        String data = result.path("data").asText("");
        if (data.isEmpty()) {
            throw new IllegalStateException("截图失败（页面可能还没渲染）");
        }
        return data;
    }

    public String pageText(int maxChars) {
        JsonNode value = evaluate("document.body ? document.body.innerText : ''");
        String text = value == null || value.isNull() ? "" : value.asText("");
        text = text.replaceAll("\\n{3,}", "\n\n").trim();
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "…";
    }

    private void refreshTitle() {
        try {
            JsonNode value = evaluate("document.title || ''");
            currentTitle = value == null || value.isNull() ? "" : value.asText("");
            JsonNode url = evaluate("location.href || ''");
            if (url != null && !url.isNull() && !url.asText("").isBlank()) {
                currentUrl = url.asText();
            }
        } catch (RuntimeException ignored) {
            // 页面还在导航时取不到，忽略
        }
    }

    private void refreshTitleAsync() {
        executor.schedule(this::refreshTitle, 600, TimeUnit.MILLISECONDS);
    }

    // ===================== 关闭 =====================

    public synchronized void close() {
        listeners.clear();
        if (cdp != null) {
            cdp.close();
            cdp = null;
        }
        if (process != null) {
            try {
                process.destroy();
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            process = null;
        }
        executor.shutdownNow();
        statusText.set("closed");
        log.info("浏览器已关闭 userId={}", userId);
    }

    // ===================== 工具 =====================

    private String browserWebSocketUrl() {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + devToolsPort + "/json/version"))
                    .timeout(Duration.ofMillis(800)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            JsonNode node = MAPPER.readTree(response.body());
            String url = node.path("webSocketDebuggerUrl").asText("");
            return url.isBlank() ? null : url;
        } catch (Exception e) {
            return null;
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("拿不到空闲端口", e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
