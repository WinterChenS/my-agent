package com.myagent.agui.reach;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 调 Agent Reach CLI（{@code agent-reach <channel> <action> …}）并把它的一行 JSON 变成人能读的结果。
 *
 * <p>几个关键约束：
 * <ul>
 *   <li><b>它是唯一出口</b>：所有出网读取都从这里走，便于统一限流、审计、脱敏与 SSRF 拦截。
 *   <li><b>每个用户一个 HOME</b>：{@code ~/.agent-reach}（登录态/配置）落在 {@code homeRoot/<userId>}，
 *       agent-reach 官方就是用 HOME 做隔离边界的，所以多人共用一个 Agent 不会串登录态。
 *   <li><b>每次调用一个进程</b>：CLI 启动约 200~400ms；因此严格控制并发（每用户信号量），
 *       并给出明确超时（默认 45s），超时直接杀进程，避免拖死一轮对话。
 *   <li><b>输出必须截断</b>：一次搜索回来几万字符，直接喂给模型会烧掉上下文；
 *       这里按条数 + 总字符数双重截断。
 * </ul>
 */
@Component
public class AgentReachClient {

    private static final Logger log = LoggerFactory.getLogger(AgentReachClient.class);

    /** 单条正文默认保留的字符数（读正文够用；列表页请用工具参数放大）。 */
    static final int DEFAULT_ITEM_CHARS = 1500;

    private final ReachProperties properties;
    private final ObjectMapper objectMapper;
    private final Map<String, Semaphore> gates = new ConcurrentHashMap<>();

    public AgentReachClient(ReachProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** 一条结果（Agent Reach 的统一字段）。 */
    public record Item(String title, String url, String author, String publishedAt, String text) {}

    /** 一次调用的结果。 */
    public record Result(
            boolean ok,
            String channel,
            String action,
            String backend,
            List<Item> items,
            String errorCode,
            String errorMessage,
            String errorHint,
            long elapsedMs) {

        public static Result failure(String code, String message, String hint) {
            return new Result(false, null, null, null, List.of(), code, message, hint, 0);
        }
    }

    public ReachProperties properties() {
        return properties;
    }

    /**
     * 执行一次 CLI 调用。
     *
     * @param userId 当前对话用户（用于隔离 HOME 与并发闸门）
     * @param args   例如 {@code ["web","search","AgentScope","--limit","5"]}
     */
    public Result run(String userId, List<String> args) {
        if (!properties.enabled()) {
            return Result.failure("DISABLED", "联网能力未启用（服务端 myagent.reach.enabled=false）", "");
        }
        if (args == null || args.isEmpty()) {
            return Result.failure("BAD_INPUT", "缺少要执行的动作", "");
        }
        Semaphore gate = gates.computeIfAbsent(userId == null ? "-" : userId,
                key -> new Semaphore(Math.max(1, properties.concurrencyPerUser())));
        boolean acquired = false;
        try {
            acquired = gate.tryAcquire(properties.timeoutSeconds(), TimeUnit.SECONDS);
            if (!acquired) {
                return Result.failure("BUSY", "上一次联网请求还没结束，请稍等再试", "");
            }
            return execute(userId, args);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failure("BUSY", "联网请求被中断，请重试", "");
        } finally {
            if (acquired) {
                gate.release();
            }
        }
    }

    private Result execute(String userId, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add(properties.binary());
        command.addAll(args);

        Path home = userHome(userId);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(home.toFile());
        Map<String, String> env = builder.environment();
        env.put("HOME", home.toAbsolutePath().toString());
        env.put("LANG", "C.UTF-8");
        env.put("LC_ALL", "C.UTF-8");
        env.put("PYTHONIOENCODING", "utf-8");
        env.put("PYTHONUNBUFFERED", "1");
        env.put("NO_COLOR", "1");

        long started = System.currentTimeMillis();
        Process process = null;
        try {
            process = builder.start();
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            Thread outReader = drain(process.getInputStream(), stdout);
            Thread errReader = drain(process.getErrorStream(), stderr);
            boolean finished = process.waitFor(properties.timeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("agent-reach 超时：user={} args={}", userId, args);
                return Result.failure("TIMEOUT",
                        "联网请求超时（>" + properties.timeoutSeconds() + "s）", "换个更小的目标或稍后再试");
            }
            outReader.join(2000);
            errReader.join(2000);

            long elapsed = System.currentTimeMillis() - started;
            String json = stdout.toString().trim();
            if (json.isEmpty()) {
                String message = stderr.toString().trim();
                log.warn("agent-reach 无输出：user={} args={} exit={} stderr={}", userId, args,
                        process.exitValue(), abbreviate(message));
                return new Result(false, null, null, null, List.of(), "NO_OUTPUT",
                        message.isEmpty() ? "联网工具没有返回内容" : abbreviate(message),
                        "可以重试一次；持续失败请让管理员检查 agent-reach 是否正常", elapsed);
            }
            Result parsed = parse(json);
            log.info("agent-reach ok={} channel={} action={} backend={} items={} elapsedMs={} user={}",
                    parsed.ok(), parsed.channel(), parsed.action(), parsed.backend(),
                    parsed.items().size(), elapsed, userId);
            return new Result(parsed.ok(), parsed.channel(), parsed.action(), parsed.backend(),
                    parsed.items(), parsed.errorCode(), parsed.errorMessage(), parsed.errorHint(), elapsed);
        } catch (IOException e) {
            log.warn("agent-reach 启动失败：user={} binary={} err={}", userId, properties.binary(),
                    e.getMessage());
            return Result.failure("NOT_INSTALLED",
                    "联网工具不可用（" + e.getMessage() + "）", "请联系管理员检查 agent-reach 是否已安装");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failure("BUSY", "联网请求被中断", "");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /** 把 CLI 的一行 JSON 解析成结构。JSON 坏了也不抛，返回可读错误。 */
    static Result parse(String json) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(json);
            boolean ok = root.path("ok").asBoolean(false);
            String channel = text(root, "channel");
            String action = text(root, "action");
            String backend = text(root, "backend");
            List<Item> items = new ArrayList<>();
            for (JsonNode node : root.path("items")) {
                items.add(new Item(text(node, "title"), text(node, "url"), text(node, "author"),
                        text(node, "published_at"), text(node, "text")));
            }
            JsonNode error = root.path("error");
            String code = error.isMissingNode() ? null : text(error, "code");
            String message = error.isMissingNode() ? null : text(error, "message");
            String hint = error.isMissingNode() ? null : text(error, "hint");
            return new Result(ok, channel, action, backend, items, code, message, hint, 0);
        } catch (IOException e) {
            return Result.failure("BAD_OUTPUT", "联网工具返回了无法解析的内容", "请重试；持续失败请联系管理员");
        }
    }

    /**
     * 把结果渲染成给模型看的文本：**限量 + 截断**，并保留可点的原始链接。
     *
     * <p>失败时给「能照着做的下一步」，而不是把 CLI 的原始英文甩给模型。
     */
    public String render(Result result) {
        return render(result, DEFAULT_ITEM_CHARS);
    }

    /**
     * 渲染结果，可指定**单条正文**最多保留多少字符。
     *
     * <p>为什么需要可调：读「列表页/榜单页」（GitHub trending、新闻栏目页）时，正文在页面中段，
     * 默认 1500 字常常只截到导航栏 → 模型等于什么都没读到。读这类页面要放大到几千字；
     * 但总字符上限（{@code myagent.reach.max-chars}）仍然兜着，不会把上下文烧光。
     */
    public String render(Result result, int itemMaxChars) {
        if (!result.ok()) {
            return "联网失败：" + humanError(result);
        }
        if (result.items().isEmpty()) {
            return "没有找到相关内容（渠道 " + nullToDash(result.channel()) + "）。可以换关键词再试。";
        }
        StringBuilder out = new StringBuilder();
        out.append("来源：").append(nullToDash(result.channel()))
                .append(" / ").append(nullToDash(result.action()))
                .append("（引擎 ").append(nullToDash(result.backend()))
                .append("，用时 ").append(result.elapsedMs()).append("ms）\n");
        int count = 0;
        for (Item item : result.items()) {
            if (count >= properties.maxItems()) {
                break;
            }
            if (out.length() >= properties.maxChars()) {
                out.append("…（结果太多，已截断；需要更多请缩小范围）\n");
                break;
            }
            count++;
            out.append("\n[").append(count).append("] ").append(nullToDash(item.title())).append("\n");
            if (item.url() != null && !item.url().isBlank()) {
                out.append("    链接：").append(item.url()).append("\n");
            }
            if (item.author() != null && !item.author().isBlank()) {
                out.append("    作者：").append(item.author()).append("\n");
            }
            if (item.publishedAt() != null && !item.publishedAt().isBlank()) {
                out.append("    时间：").append(item.publishedAt()).append("\n");
            }
            String body = item.text() == null ? "" : item.text().trim();
            if (!body.isEmpty()) {
                out.append("    正文：").append(abbreviate(body, Math.max(200, itemMaxChars))).append("\n");
            }
        }
        log.debug("agent-reach 渲染 {} 条，共 {} 字符", count, out.length());
        return out.toString();
    }

    /** 错误码 → 中文可操作提示（错误码来自 agent-reach 的 error.code 契约）。 */
    static String humanError(Result result) {
        String code = result.errorCode() == null ? "" : result.errorCode().toUpperCase(Locale.ROOT);
        String detail = result.errorMessage() == null ? "" : result.errorMessage();
        return switch (code) {
            case "DISABLED" -> detail;
            case "NOT_INSTALLED" -> "联网工具没装好，请联系管理员（" + detail + "）";
            case "TIMEOUT" -> "这次联网超时了，可以换个更具体的目标或稍后再试";
            case "BUSY" -> "上一次联网还没结束，请过一会儿再试";
            case "RATE_LIMITED" -> "平台限流了，请稍后再试（不要立刻重试、也不要换后端猛打）";
            case "NEED_LOGIN" -> "这个平台需要登录才能看，当前环境不代登录；可以改用公开网页或换来源";
            case "NEED_SETUP" -> "该渠道还没准备好（缺依赖或密钥）：" + detail;
            case "PLATFORM_BLOCKED" -> "平台要求人工验证，自动访问被挡了；请换来源或让用户自己在浏览器里看";
            case "NOT_FOUND" -> "内容不存在，核对一下链接或 ID";
            case "UPSTREAM_BROKEN" -> "这个平台暂时访问不了（上游工具失效或改版）：" + detail;
            case "BAD_INPUT", "BAD_OUTPUT" -> "参数或返回不对：" + detail;
            default -> detail.isBlank() ? "未知错误" : detail;
        };
    }

    /** 每个用户的隔离目录（agent-reach 用 HOME 当隔离边界）。 */
    Path userHome(String userId) {
        String root = properties.homeRoot().isEmpty() ? defaultHomeRoot() : properties.homeRoot();
        Path base = Path.of(root).toAbsolutePath().normalize();
        Path home = base.resolve(userId == null || userId.isBlank() ? "anonymous" : userId).normalize();
        try {
            Files.createDirectories(home);
        } catch (IOException e) {
            log.warn("创建 Agent Reach 用户目录失败 {}: {}", home, e.getMessage());
        }
        return home;
    }

    private String defaultHomeRoot() {
        String workspace = System.getenv("MYAGENT_WORKSPACE");
        if (workspace == null || workspace.isBlank()) {
            workspace = "workspace";
        }
        return Path.of(workspace).resolve(".reach-home").toString();
    }

    private static Thread drain(java.io.InputStream stream, StringBuilder sink) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (sink.length() < 2_000_000) {
                        sink.append(line).append('\n');
                    }
                }
            } catch (IOException ignored) {
                // 进程被强杀时流会中断，忽略
            }
        }, "agent-reach-io");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    static String abbreviate(String value) {
        return abbreviate(value, 300);
    }

    static String abbreviate(String value, int max) {
        if (value == null) {
            return "";
        }
        String single = value.replaceAll("\\s+", " ").trim();
        return single.length() <= max ? single : single.substring(0, max) + "…";
    }

}
