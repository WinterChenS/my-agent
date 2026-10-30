package com.myagent.agui.connector.notion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Notion REST 客户端（不引 SDK：只有几个端点，直接用 JDK HttpClient）。
 *
 * <p>刻意做进去的三件事：
 * <ul>
 *   <li><b>限流</b>：Notion 平均 3 req/s。这里串行 + 最小间隔 350ms，遇到 429 按 {@code Retry-After} 退避重试；</li>
 *   <li><b>错误人话化</b>：把 {@code unauthorized / object_not_found / validation_error} 翻成用户能照着做的中文
 *       （尤其"页面没给集成授权"这条，否则用户会以为是 token 错了）；</li>
 *   <li><b>API 版本固定</b>：{@code Notion-Version: 2026-03-11}（2025-09-03 之后数据库查询走 data source）。</li>
 * </ul>
 */
public class NotionClient {

    /** 官方当前版本（文档示例 2026-03-11）；升级只改这一处。 */
    public static final String NOTION_VERSION = "2026-03-11";

    private static final String BASE = "https://api.notion.com";
    private static final long MIN_INTERVAL_MS = 350;
    private static final int MAX_RETRIES = 3;

    private final String token;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private volatile long lastCallAt = 0L;

    public NotionClient(String token) {
        this(token, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    public NotionClient(String token, HttpClient http) {
        this.token = token == null ? "" : token.trim();
        this.http = http;
    }

    // ===================== 端点 =====================

    /** 这个 token 属于谁（PAT 会返回创建者；用来做「测试连接」）。 */
    public JsonNode me() {
        return request("GET", "/v1/users/me", null);
    }

    /** 搜索页面 / 数据库（Notion 的搜索**只匹配标题**，不搜正文）。 */
    public JsonNode search(String query, String objectType, int pageSize, String startCursor) {
        ObjectNode body = json.createObjectNode();
        if (query != null && !query.isBlank()) {
            body.put("query", query.trim());
        }
        if (objectType != null && !objectType.isBlank()) {
            body.putObject("filter").put("value", objectType.trim()).put("property", "object");
        }
        body.put("page_size", Math.min(Math.max(pageSize, 1), 100));
        if (startCursor != null && !startCursor.isBlank()) {
            body.put("start_cursor", startCursor);
        }
        return request("POST", "/v1/search", body);
    }

    public JsonNode retrievePage(String pageId) {
        return request("GET", "/v1/pages/" + pageId, null);
    }

    /** 读块（一页最多 {@code pageSize} 个，分页用 start_cursor）。 */
    public JsonNode listChildren(String blockId, int pageSize, String startCursor) {
        String path = "/v1/blocks/" + blockId + "/children?page_size=" + Math.min(Math.max(pageSize, 1), 100)
                + (startCursor == null || startCursor.isBlank() ? "" : "&start_cursor=" + startCursor);
        return request("GET", path, null);
    }

    public JsonNode appendChildren(String blockId, ArrayNode children) {
        ObjectNode body = json.createObjectNode();
        body.set("children", children);
        return request("PATCH", "/v1/blocks/" + blockId + "/children", body);
    }

    /**
     * 新建页面。
     *
     * <p>坑（实测踩到）：Notion 的 create page **不接受顶层 {@code title} 字段**，
     * 标题必须放在 {@code properties} 里、且键名是"该父级里 title 属性的名字"——
     * 普通页面下固定叫 {@code title}；数据库（data source）下可能是「名称」「Name」等，
     * 所以这里先取一次 data source 的属性结构，找出真正的 title 属性名。
     */
    public JsonNode createPage(String parentId, boolean parentIsDataSource, String title, ArrayNode children) {
        ObjectNode body = json.createObjectNode();
        ObjectNode parent = body.putObject("parent");
        String titleProperty = "title";
        if (parentIsDataSource) {
            parent.put("type", "data_source_id");
            parent.put("data_source_id", parentId);
            titleProperty = titlePropertyName(parentId);
        } else {
            parent.put("type", "page_id");
            parent.put("page_id", parentId);
        }
        ObjectNode properties = body.putObject("properties");
        ArrayNode titleArray = properties.putObject(titleProperty).putArray("title");
        titleArray.addObject().put("type", "text").putObject("text")
                .put("content", title == null || title.isBlank() ? "未命名" : title);
        if (children != null && !children.isEmpty()) {
            body.set("children", children);
        }
        return request("POST", "/v1/pages", body);
    }

    /** 找 data source 里 type=title 的属性名（找不到就用 Name / 名称 / title 依次兜底）。 */
    String titlePropertyName(String dataSourceId) {
        try {
            JsonNode schema = retrieveDataSource(dataSourceId);
            JsonNode properties = schema.path("properties");
            if (properties.isObject()) {
                var fields = properties.fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    if ("title".equals(entry.getValue().path("type").asText(""))) {
                        return entry.getKey();
                    }
                }
            }
        } catch (RuntimeException ex) {
            // 取不到就用默认名，让 API 报错信息告诉我们真实名字
        }
        return "Name";
    }

    public JsonNode updatePage(String pageId, ObjectNode body) {
        return request("PATCH", "/v1/pages/" + pageId, body);
    }

    public JsonNode queryDataSource(String dataSourceId, ObjectNode body) {
        return request("POST", "/v1/data_sources/" + dataSourceId + "/query",
                body == null ? json.createObjectNode() : body);
    }

    public JsonNode retrieveDataSource(String dataSourceId) {
        return request("GET", "/v1/data_sources/" + dataSourceId, null);
    }

    // ===================== 传输 =====================

    private JsonNode request(String method, String path, JsonNode body) {
        String payload = body == null ? null : body.toString();
        MessagingException last = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            throttle();
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(BASE + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + token)
                    .header("Notion-Version", NOTION_VERSION)
                    .header("Content-Type", "application/json");
            if ("GET".equals(method)) {
                builder.GET();
            } else {
                builder.method(method, HttpRequest.BodyPublishers.ofString(
                        payload == null ? "{}" : payload, StandardCharsets.UTF_8));
            }
            try {
                HttpResponse<String> response =
                        http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                if (status == 429) {
                    // 限流：按 Retry-After 退避（缺省 1s）
                    long waitMs = response.headers().firstValue("Retry-After")
                            .map(value -> {
                                try {
                                    return Long.parseLong(value.trim()) * 1000L;
                                } catch (NumberFormatException ex) {
                                    return 1000L;
                                }
                            })
                            .orElse(1000L);
                    sleep(Math.min(waitMs, 5000));
                    continue;
                }
                if (status >= 400) {
                    throw toHumanError(status, response.body());
                }
                return json.readTree(response.body());
            } catch (MessagingException ex) {
                throw ex;
            } catch (java.io.IOException ex) {
                last = new MessagingException("连接 Notion 失败（网络问题？）：" + ex.getMessage());
                sleep(300L * (attempt + 1));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new MessagingException("请求被中断");
            }
        }
        throw last != null ? last : new MessagingException("连接 Notion 失败");
    }

    private void throttle() {
        long since = Instant.now().toEpochMilli() - lastCallAt;
        if (since < MIN_INTERVAL_MS) {
            sleep(MIN_INTERVAL_MS - since);
        }
        lastCallAt = Instant.now().toEpochMilli();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(Math.max(millis, 0));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** 把 Notion 的错误码翻成"用户下一步该做什么"。 */
    static MessagingException toHumanError(int status, String rawBody) {
        String code = "";
        String message = "";
        String requestId = "";
        try {
            JsonNode node = new ObjectMapper().readTree(rawBody == null ? "{}" : rawBody);
            code = node.path("code").asText("");
            message = node.path("message").asText("");
            requestId = node.path("request_id").asText("");
        } catch (Exception ignored) {
            message = rawBody == null ? "" : rawBody;
        }
        String suffix = requestId.isBlank() ? "" : "（request_id: " + requestId + "）";
        if (status == 401 || "unauthorized".equals(code)) {
            return new MessagingException("Notion 令牌无效或已被撤销：请到 Notion「Developer portal → 个人访问令牌」"
                    + "重新生成，并把新令牌填回连接器" + suffix);
        }
        if (status == 403 || "restricted_resource".equals(code)) {
            return new MessagingException("没有这个页面/数据库的权限：如果是内部集成，需要在页面右上角「…」→"
                    + "「添加连接」里选中它；个人访问令牌则请确认你有该页面的访问权" + suffix);
        }
        if (status == 404 || "object_not_found".equals(code)) {
            return new MessagingException("找不到这个页面/数据库：可能是 id 不对，或者它没授权给这个连接"
                    + "（内部集成需「添加连接」；个人访问令牌需本人有权限）" + suffix);
        }
        if (status == 400 || "validation_error".equals(code)) {
            return new MessagingException("Notion 认为请求不合法：" + (message.isBlank() ? "参数有误" : message) + suffix);
        }
        if (status == 409 && "conflict_error".equals(code)) {
            return new MessagingException("Notion 端发生冲突，请稍后重试" + suffix);
        }
        if (status == 429 || "rate_limited".equals(code)) {
            return new MessagingException("触发 Notion 限流（约 3 次/秒），请稍后重试" + suffix);
        }
        return new MessagingException("Notion 接口出错（HTTP " + status + "）："
                + (message.isBlank() ? code : message) + suffix);
    }

    /** 客户端侧的业务异常（message 直接可展示）。 */
    public static class MessagingException extends RuntimeException {
        public MessagingException(String message) {
            super(message);
        }
    }

    // ===================== 小工具（给工具层用） =====================

    /** 把 Notion 的富文本数组拼成纯文本。 */
    public static String plainText(JsonNode richText) {
        if (richText == null || !richText.isArray()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode item : richText) {
            builder.append(item.path("plain_text").asText(""));
        }
        return builder.toString();
    }

    /** 分页拉完所有块（最多 {@code maxBlocks} 个）。 */
    public List<JsonNode> allChildren(String blockId, int maxBlocks) {
        List<JsonNode> blocks = new ArrayList<>();
        String cursor = null;
        while (blocks.size() < maxBlocks) {
            JsonNode page = listChildren(blockId, Math.min(100, maxBlocks - blocks.size()), cursor);
            for (JsonNode block : page.path("results")) {
                blocks.add(block);
                if (blocks.size() >= maxBlocks) {
                    break;
                }
            }
            boolean hasMore = page.path("has_more").asBoolean(false);
            cursor = page.path("next_cursor").asText(null);
            if (!hasMore || cursor == null) {
                break;
            }
        }
        return blocks;
    }

    /** 数据库（data source）的属性结构 → 精简描述（给模型看，避免整坨 JSON）。 */
    public static Map<String, String> propertySummary(JsonNode properties) {
        Map<String, String> result = new LinkedHashMap<>();
        if (properties == null || !properties.isObject()) {
            return result;
        }
        properties.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            String type = value.path("type").asText(value.path("object").asText(""));
            result.put(entry.getKey(), type.isBlank() ? "unknown" : type);
        });
        return result;
    }
}
