package com.myagent.agui.browser;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 浏览器操作工具：让 Agent 在**服务端真实 Chromium** 里打开/点击/输入/读页面。
 *
 * <p>用户在产物预览区能实时看到画面（CDP screencast 推帧），也能自己接管操作。
 *
 * <p>为什么这些工具标 {@code isReadOnly=true}：{@code REQUIRE_APPROVAL} 模式会为"写工具"每一步弹审批卡，
 * 而浏览器操作天然是连续多步（打开→滚动→点击→输入），每步弹卡等于没法用。
 * 真正的危险动作（付款、提交、发消息）应该在产品层单独做确认（见 docs 的风险章节）。
 */
@Component
public class BrowserTools {

    private static final Logger log = LoggerFactory.getLogger(BrowserTools.class);

    private final BrowserSessionRegistry registry;
    private final com.myagent.agui.browser.ext.BrowserToolService tools;

    public BrowserTools(BrowserSessionRegistry registry,
                        com.myagent.agui.browser.ext.BrowserToolService tools) {
        this.registry = registry;
        this.tools = tools;
    }

    public List<String> register(Toolkit toolkit) {
        if (toolkit == null) {
            return List.of();
        }
        if (!tools.enabled() && !registry.properties().enabled()) {
            log.info("浏览器工具未启用（myagent.browser.mode=disabled）");
            return List.of();
        }
        List<AgentTool> tools = tools();
        tools.forEach(toolkit::registerAgentTool);
        log.info("已注册 {} 个浏览器工具：{}", tools.size(), tools.stream().map(AgentTool::getName).toList());
        return tools.stream().map(AgentTool::getName).toList();
    }

    public List<AgentTool> tools() {
        return List.of(new OpenTool(), new ReadTool(), new ClickTool(), new TypeTool(),
                new ScrollTool(), new ScreenshotTool(), new CloseTool(), new StatusTool());
    }

    // ===================== 基类 =====================

    private abstract static class Base implements AgentTool {

        /** 本机扩展模式的门面；远程模式下只是被忽略。 */
        protected final com.myagent.agui.browser.ext.BrowserToolService svc;

        Base(com.myagent.agui.browser.ext.BrowserToolService svc) {
            this.svc = svc;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.fromCallable(() -> block(run(param)))
                    .subscribeOn(Schedulers.boundedElastic())
                    .onErrorResume(ex -> Mono.just(block("浏览器操作失败：" + human(ex))));
        }

        protected abstract String run(ToolCallParam param);

        /** 见类注释：浏览器操作不逐条弹审批卡（危险动作需产品层单独确认）。 */
        @Override
        public boolean isReadOnly() {
            return true;
        }

        protected static ToolResultBlock block(String text) {
            return ToolResultBlock.builder().output(TextBlock.builder().text(text).build()).build();
        }

        /** 本机扩展模式：统一交给 BrowserToolService（远程模式返回 null，走旧逻辑）。 */
        protected String local(ToolCallParam param, String action, java.util.Map<String, Object> args) {
            if (!svc.localMode()) {
                return null;
            }
            return svc.format(svc.run(userIdLong(param), conversationId(param), action, args));
        }

        protected static long userIdLong(ToolCallParam param) {
            String userId = userId(param);
            try {
                return userId == null ? 0L : Long.parseLong(userId);
            } catch (NumberFormatException e) {
                return 0L;
            }
        }

        protected static String conversationId(ToolCallParam param) {
            return param.getRuntimeContext() == null ? null : param.getRuntimeContext().getSessionId();
        }

        protected static String userId(ToolCallParam param) {
            return param.getRuntimeContext() == null ? null : param.getRuntimeContext().getUserId();
        }

        protected static String str(ToolCallParam param, String key) {
            Object value = param.getInput().get(key);
            return value == null ? null : String.valueOf(value).trim();
        }

        protected static int intOr(ToolCallParam param, String key, int fallback) {
            Object value = param.getInput().get(key);
            if (value instanceof Number number) {
                return number.intValue();
            }
            try {
                return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        protected static String human(Throwable ex) {
            String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            if (message.contains("浏览器功能未启用")) {
                return message;
            }
            if (message.contains("资源被占用")) {
                return message;
            }
            return message;
        }
    }

    // ===================== 工具 =====================

    private class OpenTool extends Base {

        OpenTool() {
            super(BrowserTools.this.tools);
        }

        @Override
        public String getName() {
            return "browser_open";
        }

        @Override
        public String getDescription() {
            return "打开一个网页（服务端真实浏览器），用户能在右侧预览区实时看到画面。"
                    + "返回页面标题、地址与正文摘要。只支持公网 http/https；内网地址会被拒绝。"
                    + "需要点击、输入、滚动时接着用 browser_click / browser_type / browser_scroll。";
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("url", Map.of("type", "string", "description", "要打开的完整链接（含 https://）"));
            fields.put("maxChars", Map.of("type", "integer",
                    "description", "可选：返回的正文最多多少字符，默认 1200"));
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", fields);
            schema.put("required", List.of("url"));
            return schema;
        }

        @Override
        protected String run(ToolCallParam param) {
            String url = str(param, "url");
            String rejected = registry.rejectUrl(url);
            if (rejected != null) {
                return rejected;
            }
            String localResult = local(param, "navigate", java.util.Map.of("url", url));
            if (localResult != null) {
                return localResult;
            }
            BrowserSession session = registry.acquire(userId(param));
            session.navigate(url);
            int maxChars = Math.max(200, Math.min(intOr(param, "maxChars", 1200), 8000));
            String text;
            try {
                text = session.pageText(maxChars);
            } catch (RuntimeException e) {
                text = "（正文暂未取到：" + e.getMessage() + "）";
            }
            return "已在浏览器中打开：" + url + "\n标题：" + session.currentTitle()
                    + "\n（右侧预览区可以看到实时画面，用户也可以接管操作）\n\n正文摘要：\n" + text;
        }
    }

    private class ReadTool extends Base {

        ReadTool() {
            super(BrowserTools.this.tools);
        }

        @Override
        public String getName() {
            return "browser_read";
        }

        @Override
        public String getDescription() {
            return "读取当前浏览器页面的正文（纯文本）。需要看页面内容时用它，比截图更适合让模型理解。";
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("maxChars", Map.of("type", "integer", "description", "可选：最多多少字符，默认 4000"));
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", fields);
            schema.put("required", List.of());
            return schema;
        }

        @Override
        protected String run(ToolCallParam param) {
            String localResult = local(param, "get_snapshot",
                    java.util.Map.of("maxChars", Math.max(200, Math.min(intOr(param, "maxChars", 4000), 20000))));
            if (localResult != null) {
                return localResult;
            }
            BrowserSession session = registry.find(userId(param));
            if (session == null) {
                return "还没有打开浏览器。请先调用 browser_open 打开一个页面。";
            }
            int maxChars = Math.max(200, Math.min(intOr(param, "maxChars", 4000), 20000));
            return "当前页面：" + session.currentUrl() + "\n标题：" + session.currentTitle()
                    + "\n\n" + session.pageText(maxChars);
        }
    }

    private class ClickTool extends Base {

        ClickTool() {
            super(BrowserTools.this.tools);
        }

        @Override
        public String getName() {
            return "browser_click";
        }

        @Override
        public String getDescription() {
            return "在浏览器里点击。优先用 selector（CSS 选择器，例如 `button[type=submit]` 或 `a[href*=login]`），"
                    + "也可以用 x/y 像素坐标。点击是真实鼠标事件，用户能在画面上看到。";
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("selector", Map.of("type", "string", "description", "可选：CSS 选择器（优先用这个）"));
            fields.put("x", Map.of("type", "integer", "description", "可选：点击位置 x（像素）"));
            fields.put("y", Map.of("type", "integer", "description", "可选：点击位置 y（像素）"));
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", fields);
            schema.put("required", List.of());
            return schema;
        }

        @Override
        protected String run(ToolCallParam param) {
            java.util.Map<String, Object> args = new java.util.LinkedHashMap<>();
            if (str(param, "selector") != null) {
                args.put("selector", str(param, "selector"));
            }
            if (param.getInput().get("x") != null) {
                args.put("x", intOr(param, "x", -1));
            }
            if (param.getInput().get("y") != null) {
                args.put("y", intOr(param, "y", -1));
            }
            String localResult = local(param, "click", args);
            if (localResult != null) {
                return localResult;
            }
            BrowserSession session = registry.find(userId(param));
            if (session == null) {
                return "还没有打开浏览器。请先调用 browser_open。";
            }
            if (session.isUserBlocking()) {
                return "用户正在自己操作页面，先别动。等一会儿（用户 60 秒没操作会自动交还），"
                        + "或让用户点右上角「交还」再继续。";
            }
            String selector = str(param, "selector");
            int x;
            int y;
            if (selector != null && !selector.isBlank()) {
                int[] center = session.elementCenter(selector);
                x = center[0];
                y = center[1];
            } else {
                x = intOr(param, "x", -1);
                y = intOr(param, "y", -1);
                if (x < 0 || y < 0) {
                    return "请给 selector，或同时给 x 和 y 坐标";
                }
            }
            session.click(x, y);
            sleep(300);
            return "已点击" + (selector != null && !selector.isBlank() ? "元素 " + selector : "坐标 (" + x + "," + y + ")")
                    + "。当前页面：" + session.currentUrl() + "（标题：" + session.currentTitle() + "）";
        }
    }

    private class TypeTool extends Base {

        TypeTool() {
            super(BrowserTools.this.tools);
        }

        @Override
        public String getName() {
            return "browser_type";
        }

        @Override
        public String getDescription() {
            return "在浏览器里输入文字。可先用 selector 点中输入框（例如 `input[name=q]`），再输入 text；"
                    + "submit=true 会补一个回车。中文安全（走 Input.insertText）。";
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("text", Map.of("type", "string", "description", "要输入的文字"));
            fields.put("selector", Map.of("type", "string", "description", "可选：输入框的 CSS 选择器"));
            fields.put("submit", Map.of("type", "boolean", "description", "可选：输入后是否回车提交，默认 false"));
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", fields);
            schema.put("required", List.of("text"));
            return schema;
        }

        @Override
        protected String run(ToolCallParam param) {
            java.util.Map<String, Object> args = new java.util.LinkedHashMap<>();
            args.put("text", str(param, "text"));
            if (str(param, "selector") != null) {
                args.put("selector", str(param, "selector"));
            }
            args.put("submit", Boolean.parseBoolean(String.valueOf(
                    param.getInput().getOrDefault("submit", "false"))));
            String localResult = local(param, "type_text", args);
            if (localResult != null) {
                return localResult;
            }
            BrowserSession session = registry.find(userId(param));
            if (session == null) {
                return "还没有打开浏览器。请先调用 browser_open。";
            }
            if (session.isUserBlocking()) {
                return "用户正在自己操作页面，先别动。等一会儿（用户 60 秒没操作会自动交还）。";
            }
            String text = str(param, "text");
            if (text == null || text.isEmpty()) {
                return "text 不能为空";
            }
            String selector = str(param, "selector");
            if (selector != null && !selector.isBlank()) {
                int[] center = session.elementCenter(selector);
                session.click(center[0], center[1]);
                sleep(120);
            }
            session.insertText(text);
            boolean submit = Boolean.parseBoolean(String.valueOf(param.getInput().getOrDefault("submit", "false")));
            if (submit) {
                sleep(120);
                session.pressKey("Enter");
            }
            sleep(300);
            return "已输入「" + text + "」" + (submit ? " 并回车" : "") + "。当前页面：" + session.currentUrl();
        }
    }

    private class ScrollTool extends Base {

        ScrollTool() {
            super(BrowserTools.this.tools);
        }

        @Override
        public String getName() {
            return "browser_scroll";
        }

        @Override
        public String getDescription() {
            return "在浏览器里上下滚动（正数向下）。用于看更多内容或让目标元素进入视野。";
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("deltaY", Map.of("type", "integer", "description", "滚动像素，默认 600（正数向下）"));
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", fields);
            schema.put("required", List.of());
            return schema;
        }

        @Override
        protected String run(ToolCallParam param) {
            String localResult = local(param, "scroll", java.util.Map.of("deltaY", intOr(param, "deltaY", 600)));
            if (localResult != null) {
                return localResult;
            }
            BrowserSession session = registry.find(userId(param));
            if (session == null) {
                return "还没有打开浏览器。请先调用 browser_open。";
            }
            if (session.isUserBlocking()) {
                return "用户正在自己操作页面，先别动。等一会儿再滚。";
            }
            int delta = intOr(param, "deltaY", 600);
            session.scroll(640, 400, delta);
            sleep(250);
            return "已滚动 " + delta + " 像素。当前页面：" + session.currentUrl();
        }
    }

    private class ScreenshotTool extends Base {

        ScreenshotTool() {
            super(BrowserTools.this.tools);
        }

        @Override
        public String getName() {
            return "browser_screenshot";
        }

        @Override
        public String getDescription() {
            return "给当前浏览器页面截一张 PNG，保存到你的工作区并返回文件路径。"
                    + "需要留档、或要把页面交给用户下载时用它。";
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", Map.of());
            schema.put("required", List.of());
            return schema;
        }

        @Override
        protected String run(ToolCallParam param) {
            if (tools.localMode()) {
                var result = svc.run(userIdLong(param), conversationId(param),
                        "capture_screenshot", java.util.Map.of());
                if (!result.ok()) {
                    return svc.format(result);
                }
                String dataUrl = svc.screenshotDataUrl(result);
                if (dataUrl == null) {
                    return "截图已生成，但扩展没有回传图像数据。";
                }
                return "已截图（页面 " + (result.origin() == null ? "未知" : result.origin())
                        + "）。图像数据 " + dataUrl.length() + " 字符；用户可以直接在浏览器里看到。";
            }
            String userId = userId(param);
            BrowserSession session = registry.find(userId);
            if (session == null) {
                return "还没有打开浏览器。请先调用 browser_open。";
            }
            String base64 = session.screenshotBase64();
            String root = System.getenv().getOrDefault("MYAGENT_WORKSPACE", "workspace");
            Path dir = Path.of(root).resolve(userId == null ? "anonymous" : userId).resolve("browser-shots");
            Path file = dir.resolve("shot-" + System.currentTimeMillis() + ".png");
            try {
                Files.createDirectories(dir);
                Files.write(file, Base64.getDecoder().decode(base64));
            } catch (IOException e) {
                return "截图保存失败：" + e.getMessage();
            }
            return "已截图并保存：" + file + "（" + session.currentUrl() + "）";
        }
    }

    private class CloseTool extends Base {

        CloseTool() {
            super(BrowserTools.this.tools);
        }

        @Override
        public String getName() {
            return "browser_close";
        }

        @Override
        public String getDescription() {
            return "关闭浏览器（释放资源）。任务做完就关，别让它一直占着内存。";
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", Map.of());
            schema.put("required", List.of());
            return schema;
        }

        @Override
        protected String run(ToolCallParam param) {
            if (tools.localMode()) {
                return svc.close(userIdLong(param), conversationId(param)).text();
            }
            registry.close(userId(param));
            return "浏览器已关闭。";
        }
    }

    /** 查询连接/授权状态：Agent 拿到 EXTENSION_REQUIRED 之后用它确认「用户装好没有」。 */
    private class StatusTool extends Base {

        StatusTool() {
            super(BrowserTools.this.tools);
        }

        @Override
        public String getName() {
            return "browser_status";
        }

        @Override
        public String getDescription() {
            return "查看浏览器连接状态：是否已安装扩展、是否在线、当前标签页授权与控制权状态。"
                    + "当 browser_open 返回 EXTENSION_REQUIRED / AWAITING_TAB / HUMAN_CONTROL 时用它确认进展。";
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", Map.of());
            schema.put("required", List.of());
            return schema;
        }

        @Override
        protected String run(ToolCallParam param) {
            if (tools.localMode()) {
                java.util.Map<String, Object> status = svc.status(userIdLong(param), conversationId(param));
                StringBuilder sb = new StringBuilder("浏览器模式：本机扩展\n");
                Object devices = status.get("devices");
                int count = devices instanceof java.util.List<?> list ? list.size() : 0;
                sb.append("已配对设备：").append(count).append(" 台\n");
                if (devices instanceof java.util.List<?> list) {
                    for (Object item : list) {
                        sb.append("  - ").append(item).append("\n");
                    }
                }
                sb.append("控制会话：").append(status.getOrDefault("state", "未创建"));
                if (status.get("origin") != null) {
                    sb.append("（").append(status.get("origin")).append("）");
                }
                if (status.get("lastError") != null) {
                    sb.append("，原因：").append(status.get("lastError"));
                }
                if (count == 0) {
                    sb.append("\n提示：还没有配对任何浏览器，请让用户在右侧「浏览器」面板安装扩展并连接。");
                }
                return sb.toString();
            }
            BrowserSession session = registry.find(userId(param));
            return session == null ? "服务端浏览器未打开（远程模式）。" : "服务端浏览器："
                    + session.currentUrl() + "（" + session.currentTitle() + "）";
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
