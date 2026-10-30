package com.myagent.agui.reach;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 把 Agent Reach 注册成官方 {@link Toolkit} 里的工具 —— **Agent 访问互联网的唯一途径**。
 *
 * <p>三个工具的分工：
 * <ul>
 *   <li>{@code internet_search} 网页搜索（走 web 渠道：Exa → 公开网页兜底）
 *   <li>{@code internet_read} 读某个链接的正文（Jina Reader → web 兜底），带 SSRF 护栏
 *   <li>{@code internet_channel} 指定渠道+动作（GitHub 仓库/issues、V2EX、B站、YouTube 字幕…），
 *       渠道必须在白名单内
 * </ul>
 *
 * <p>为什么合成三个而不是给每个渠道一个工具：渠道和动作会随上游变化（agent-reach 自己就是
 * 「首选项 + 备选」的有序列表），固定成一堆 Java 工具会很快过期；保留一个受白名单约束的
 * 通用入口，模型先用 {@code internet_channel} 里的说明挑动作，扩展成本最低。
 *
 * <p>全部是**只读**工具（{@code isReadOnly=true}）：agent-reach 本身也只读，不发帖不登录。
 */
@Component
public class AgentReachToolkit {

    private static final Logger log = LoggerFactory.getLogger(AgentReachToolkit.class);

    private final AgentReachClient client;
    private final ReachProperties properties;
    private final ReachUrlPolicy urlPolicy;

    public AgentReachToolkit(AgentReachClient client, ReachProperties properties) {
        this.client = client;
        this.properties = properties;
        this.urlPolicy = new ReachUrlPolicy(properties.allowPrivateNetwork(), properties.resolveDns());
    }

    /** 注册到 Toolkit，返回注册的工具名。 */
    public List<String> register(Toolkit toolkit) {
        if (!properties.enabled()) {
            log.info("Agent Reach 未启用（myagent.reach.enabled=false），跳过联网工具注册");
            return List.of();
        }
        if (toolkit == null) {
            return List.of();
        }
        List<AgentTool> tools = tools();
        tools.forEach(toolkit::registerAgentTool);
        log.info("已注册 {} 个联网工具（Agent Reach）：{}", tools.size(),
                tools.stream().map(AgentTool::getName).toList());
        return tools.stream().map(AgentTool::getName).toList();
    }

    public List<AgentTool> tools() {
        return List.of(new SearchTool(), new ReadTool(), new ChannelTool());
    }

    ReachUrlPolicy urlPolicy() {
        return urlPolicy;
    }

    // ===================== 公共基类 =====================

    private abstract static class BaseReachTool implements AgentTool {

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.fromCallable(() -> block(execute(param)))
                    .subscribeOn(Schedulers.boundedElastic())
                    .onErrorResume(ex -> Mono.just(block("联网操作失败：" + ex.getMessage())));
        }

        protected abstract String execute(ToolCallParam param);

        /** 只读：不弹审批卡，也不会被写操作权限拦住。 */
        @Override
        public boolean isReadOnly() {
            return true;
        }

        protected static ToolResultBlock block(String value) {
            return ToolResultBlock.builder().output(TextBlock.builder().text(value).build()).build();
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
    }

    // ===================== 工具实现 =====================

    /** 网页搜索。 */
    private class SearchTool extends BaseReachTool {

        @Override
        public String getName() {
            return "internet_search";
        }

        @Override
        public String getDescription() {
            return "联网搜索（这是你访问互联网的唯一途径）。返回若干条结果，每条含标题、链接、摘要。"
                    + "需要最新信息、不确定的事实、要给出来源链接时用它；拿到链接后可以用 internet_read 读正文。"
                    + "只用关键词，不要写整句问句。";
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("query", Map.of("type", "string", "description", "搜索关键词"));
            fields.put("limit", Map.of("type", "integer",
                    "description", "返回条数，默认 5，最多 " + properties.maxLimit()));
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", fields);
            schema.put("required", List.of("query"));
            return schema;
        }

        @Override
        protected String execute(ToolCallParam param) {
            String query = str(param, "query");
            if (!properties.channelAllowed("web")) {
                return "联网搜索未启用（服务端白名单里没有 web 渠道）。";
            }
            if (query == null || query.isBlank()) {
                return "query 不能为空";
            }
            List<String> args = new ArrayList<>(List.of("web", "search", query));
            int limit = clamp(intOr(param, "limit", 5));
            args.add("--limit");
            args.add(String.valueOf(limit));
            return client.render(client.run(userId(param), args));
        }

        private int clamp(int limit) {
            return Math.max(1, Math.min(limit, properties.maxLimit()));
        }
    }

    /** 读链接正文。 */
    private class ReadTool extends BaseReachTool {

        @Override
        public String getName() {
            return "internet_read";
        }

        @Override
        public String getDescription() {
            return "读一个网页/文档链接的正文（这是你访问互联网的唯一途径，只读）。"
                    + "用户贴了链接、或者搜索结果的链接需要看细节时用它。"
                    + "只支持公网 http/https 链接；内网地址会被拒绝。";
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("url", Map.of("type", "string", "description", "要读的完整链接（含 https://）"));
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", fields);
            schema.put("required", List.of("url"));
            return schema;
        }

        @Override
        protected String execute(ToolCallParam param) {
            String url = str(param, "url");
            String rejected = urlPolicy.reject(url);
            if (rejected != null) {
                return rejected;
            }
            return client.render(client.run(userId(param), List.of("web", "read", url)));
        }
    }

    /** 指定渠道 + 动作（GitHub / V2EX / B站 / YouTube …）。 */
    private class ChannelTool extends BaseReachTool {

        @Override
        public String getName() {
            return "internet_channel";
        }

        @Override
        public String getDescription() {
            return "按平台渠道读取内容（这是你访问互联网的唯一途径，只读）。"
                    + "可用渠道：" + String.join("、", properties.channels()) + "。"
                    + "常用动作：github=search|read（仓库/issues/PR）、v2ex=hot|node|read|user、"
                    + "bilibili=search|read|hot|subtitle、youtube=search|read|subtitle。"
                    + "参数：channel + action 必填；搜索类动作用 query；按链接/ID 读用 target；可选 limit。"
                    + "登录类渠道（小红书/Twitter/Reddit 等）在服务器上不可用，不要尝试。";
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("channel", Map.of("type", "string",
                    "description", "渠道名，例如 github / v2ex / bilibili / youtube / web"));
            fields.put("action", Map.of("type", "string",
                    "description", "动作名，例如 search / read / hot / subtitle"));
            fields.put("query", Map.of("type", "string",
                    "description", "可选：搜索关键词（search 类动作必填）"));
            fields.put("target", Map.of("type", "string",
                    "description", "可选：链接或 ID（read/subtitle/profile 类动作用）"));
            fields.put("limit", Map.of("type", "integer",
                    "description", "可选：返回条数，最多 " + properties.maxLimit()));
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", fields);
            schema.put("required", List.of("channel", "action"));
            return schema;
        }

        @Override
        protected String execute(ToolCallParam param) {
            String channel = str(param, "channel");
            String action = str(param, "action");
            if (channel == null || channel.isBlank()) {
                return "channel 不能为空";
            }
            String normalized = channel.toLowerCase(java.util.Locale.ROOT);
            if (!properties.channelAllowed(normalized)) {
                return "渠道 " + channel + " 未启用。可用渠道：" + String.join("、", properties.channels())
                        + "（登录类渠道在服务器上不可用）";
            }
            if (action == null || action.isBlank()) {
                return "action 不能为空（search / read / hot / subtitle …）";
            }
            List<String> args = new ArrayList<>(List.of(normalized, action));
            String target = str(param, "target");
            String query = str(param, "query");
            String positional = target != null && !target.isBlank() ? target : query;
            if (positional == null || positional.isBlank()) {
                return "这个动作需要 query 或 target（例如 github search 需要 query，github read 需要 target）";
            }
            if (("read".equals(action) || "subtitle".equals(action)) && positional.startsWith("http")) {
                String rejected = urlPolicy.reject(positional);
                if (rejected != null) {
                    return rejected;
                }
            }
            args.add(positional);
            Object limitValue = param.getInput().get("limit");
            if (limitValue != null) {
                int limit = Math.max(1, Math.min(intOr(param, "limit", 5), properties.maxLimit()));
                args.add("--limit");
                args.add(String.valueOf(limit));
            }
            return client.render(client.run(userId(param), args));
        }
    }
}
