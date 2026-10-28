package com.myagent.agui.connector.mail;

import com.myagent.connectorconfig.ConnectorRecord;
import com.myagent.connectorconfig.ConnectorService;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 邮件工具的公共基类：**按当前对话用户解析"该用哪个邮箱"** + 统一错误处理。
 *
 * <p>关键设计：工具实例是每个 Agent 一份的**无状态**对象（只在构建 Agent 时创建），
 * 凭据不烘进来。调用时按 {@code RuntimeContext.getUserId()} 找该用户自己、
 * 且绑定到本 Agent 的邮件连接器 —— 所以多人共用一个 Agent 也不会碰到别人的邮箱。
 *
 * <p>错误一律返回「可操作的中文提示」而不是抛异常：模型看到提示能自己纠正
 * （换文件夹、重新 list 拿 uid、去添加连接器…）。
 */
abstract class AbstractMailTool implements AgentTool {

    /** 多个邮箱时用这个参数选：别名或名称。 */
    static final String CONNECTOR_PARAM = "connector";

    protected final long agentId;
    protected final ConnectorService connectorService;
    protected final Path workspaceRoot;
    protected final int maxBodyChars;
    protected final long maxAttachmentBytes;
    protected final long maxAttachmentsTotalBytes;
    protected final int connectTimeoutMs;
    protected final int readTimeoutMs;

    protected AbstractMailTool(
            long agentId,
            ConnectorService connectorService,
            Path workspaceRoot,
            int connectTimeoutMs,
            int readTimeoutMs,
            long maxAttachmentBytes,
            long maxAttachmentsTotalBytes,
            int maxBodyChars) {
        this.agentId = agentId;
        this.connectorService = connectorService;
        this.workspaceRoot = workspaceRoot;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.maxAttachmentBytes = maxAttachmentBytes;
        this.maxAttachmentsTotalBytes = maxAttachmentsTotalBytes;
        this.maxBodyChars = maxBodyChars;
    }

    /** 工具主体：拿到自己的邮箱 + 已解密的配置后干活。 */
    protected abstract String run(MailContext context, ToolCallParam param);

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> execute(param))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(ex -> Mono.just(text("邮件操作失败：" + humanMessage(ex))));
    }

    private ToolResultBlock execute(ToolCallParam param) {
        String userId = param.getRuntimeContext() == null ? null : param.getRuntimeContext().getUserId();
        if (userId == null || userId.isBlank()) {
            return text("拿不到当前用户身份，无法确定用哪个邮箱。请让用户重新登录后再试。");
        }
        long uid;
        try {
            uid = Long.parseLong(userId.trim());
        } catch (NumberFormatException ex) {
            return text("当前用户身份异常（" + userId + "），无法使用邮箱连接器。");
        }
        List<ConnectorService.ResolvedConnector> mine = connectorService.resolveForUser(agentId, uid).stream()
                .filter(candidate -> "MAIL".equalsIgnoreCase(candidate.record().type()))
                .toList();
        if (mine.isEmpty()) {
            return text(noConnectorHint());
        }
        String wanted = asString(param.getInput().get(CONNECTOR_PARAM));
        ConnectorService.ResolvedConnector picked = pick(mine, wanted);
        if (picked == null) {
            return text("你绑定了多个邮箱，请用参数 `" + CONNECTOR_PARAM + "` 指定其中一个："
                    + describe(mine) + "。例如 \"" + CONNECTOR_PARAM + "\":\""
                    + (mine.get(0).record().alias() == null ? mine.get(0).record().name() : mine.get(0).record().alias())
                    + "\"");
        }
        MailSettings settings = MailSettings.of(picked.config());
        MailClient client = new MailClient(
                settings, connectTimeoutMs, readTimeoutMs,
                maxAttachmentBytes, maxAttachmentsTotalBytes, maxBodyChars);
        MailContext context = new MailContext(picked.record(), settings, client, connectorService);
        return text(run(context, param));
    }

    private static ConnectorService.ResolvedConnector pick(
            List<ConnectorService.ResolvedConnector> mine, String wanted) {
        if (wanted != null && !wanted.isBlank()) {
            String key = wanted.trim().toLowerCase(java.util.Locale.ROOT);
            Optional<ConnectorService.ResolvedConnector> byAlias = mine.stream()
                    .filter(connector -> key.equals(lower(connector.record().alias()))
                            || key.equals(lower(connector.record().name())))
                    .findFirst();
            return byAlias.orElse(null);
        }
        return mine.size() == 1 ? mine.get(0) : null;
    }

    private static String describe(List<ConnectorService.ResolvedConnector> mine) {
        return String.join("、", mine.stream()
                .map(connector -> connector.record().alias() == null
                        ? connector.record().name()
                        : connector.record().name() + "(" + connector.record().alias() + ")")
                .toList());
    }

    /** 用户没有可用邮箱时的提示：说清"怎么才能用上"，模型才能转告用户。 */
    static String noConnectorHint() {
        return "当前用户还没有可用的邮箱连接器（或没有绑定到本 Agent）。"
                + "请告诉用户：到「能力 → 连接器」添加自己的邮箱（需要邮箱地址、IMAP/SMTP 服务器与授权码），"
                + "并把它绑定给当前 Agent；绑定后**下一轮**对话即可使用。"
                + "在此之前无法替他读信或发信。";
    }

    /** 工具上下文：拿到手就能直接操作邮箱。 */
    record MailContext(
            ConnectorRecord record,
            MailSettings settings,
            MailClient client,
            ConnectorService connectorService) {

        /** 工作区里的附件目录（模型可以用 run_command 直接处理这些文件）。 */
        Path attachmentDir(Path workspaceRoot, long userId) {
            return workspaceRoot.resolve(String.valueOf(userId))
                    .resolve("connector")
                    .resolve(String.valueOf(record.id()))
                    .resolve("attachments");
        }
    }

    // ===================== 参数辅助 =====================

    protected static Map<String, Object> connectorParam(String hint) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(CONNECTOR_PARAM, Map.of(
                "type", "string",
                "description", "可选：用哪个邮箱连接器（填别名或名称）。只绑定了一个邮箱时可以省略。" + hint));
        return properties;
    }

    protected static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    protected static Integer asInt(Object value, Integer fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    protected static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    protected static boolean asBool(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(value).trim().toLowerCase(java.util.Locale.ROOT);
        if ("true".equals(text) || "1".equals(text) || "yes".equals(text)) {
            return true;
        }
        if ("false".equals(text) || "0".equals(text) || "no".equals(text)) {
            return false;
        }
        return fallback;
    }

    protected static List<String> asStringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return list.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
        }
        return java.util.Arrays.stream(String.valueOf(value).split("[,;\\s]+"))
                .map(String::trim)
                .filter(text -> !text.isEmpty())
                .toList();
    }

    /** 把模型给的工作区相对路径解析成真实路径（限制在工作区内，防止越权读文件）。 */
    protected Path resolveWorkspacePath(String relative, long userId) {
        Path base = workspaceRoot.resolve(String.valueOf(userId)).normalize();
        Path target = base.resolve(relative).normalize();
        if (!target.startsWith(base)) {
            throw new IllegalArgumentException("路径必须在你自己的工作区内：" + relative);
        }
        return target;
    }

    protected static boolean exists(Path path) {
        return path != null && Files.exists(path);
    }

    protected static String lower(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT);
    }

    protected static ToolResultBlock text(String content) {
        return ToolResultBlock.builder().output(TextBlock.builder().text(content).build()).build();
    }

    private static String humanMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
