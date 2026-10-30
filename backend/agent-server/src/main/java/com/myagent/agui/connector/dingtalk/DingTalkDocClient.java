package com.myagent.agui.connector.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 钉钉文档/知识库 REST 客户端。
 *
 * <p>两个和 Notion 完全不同的地方：
 * <ol>
 *   <li><b>凭据是"企业应用"的</b>：先用 appKey/appSecret 换 {@code accessToken}（2 小时有效），
 *       放在请求头 {@code x-acs-dingtalk-access-token}；这里做了进程内缓存，提前 60 秒过期；</li>
 *   <li><b>权限看 operatorId</b>：几乎每个接口都要传 {@code operatorId}（以哪个员工的身份操作），
 *       能看到什么完全由这个人自己的钉钉权限决定。</li>
 * </ol>
 *
 * <p>错误一律翻成人话（钉钉的错误信息偏技术，用户看不懂）。
 */
public class DingTalkDocClient {

    private static final String BASE = "https://api.dingtalk.com";
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 同一应用的所有请求共用一份 token 缓存。 */
    private static final Map<String, CachedToken> TOKENS = new java.util.concurrent.ConcurrentHashMap<>();
    /** staffId/unionId 的换算缓存（文档接口要 unionId）。 */
    private static final Map<String, CachedToken> UNION_IDS = new java.util.concurrent.ConcurrentHashMap<>();
    /** 换算失败的原因（用于把"还差哪个权限/申请链接"直接告诉用户）。 */
    private static final Map<String, String> UNION_ID_ERRORS = new java.util.concurrent.ConcurrentHashMap<>();

    private record CachedToken(String token, Instant expiresAt) {}

    private final String appKey;
    private final String appSecret;
    private final String operatorId;
    private final HttpClient http;
    private volatile long lastCallAt = 0L;

    public DingTalkDocClient(String appKey, String appSecret, String operatorId) {
        this(appKey, appSecret, operatorId,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    public DingTalkDocClient(String appKey, String appSecret, String operatorId, HttpClient http) {
        this.appKey = appKey == null ? "" : appKey.trim();
        this.appSecret = appSecret == null ? "" : appSecret.trim();
        this.operatorId = operatorId == null ? "" : operatorId.trim();
        this.http = http;
    }

    public String operatorId() {
        return operatorId;
    }

    /**
     * 钉钉文档接口的 {@code operatorId} 实际要的是 **unionId**，而机器人里拿到的是 **staffId**
     * （形如 {@code 0935422236-1407729645}），直接传会报 {@code paramError-operatorId}（实测）。
     *
     * <p>这里用通讯录接口把 staffId 换成 unionId 并缓存；换不到就原样返回，
     * 让错误信息带着 requestid 暴露出来，方便排查（而不是静默失败）。
     */
    private String resolveUnionId(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        CachedToken cached = UNION_IDS.get(appKey + "|" + raw);
        if (cached != null) {
            return cached.token();
        }
        try {
            // 注意：新版通讯录接口 /v1.0/contact/users/{id} 收的是 unionId，不是 userId（实测 404），
            // 所以这里走老的 topapi：/topapi/v2/user/get，用 app access_token 换 unionid。
            String token = accessToken();
            ObjectNode body = JSON.createObjectNode();
            body.put("userid", raw);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://oapi.dingtalk.com/topapi/v2/user/get?access_token=" + encode(token)))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode parsed = JSON.readTree(response.body());
            String unionId = parsed.path("result").path("unionid").asText("");
            if (unionId.isBlank()) {
                String reason = parsed.path("errmsg").asText(parsed.toString());
                UNION_ID_ERRORS.put(appKey + "|" + raw, reason);
                org.slf4j.LoggerFactory.getLogger(DingTalkDocClient.class)
                        .warn("通讯录换算没有 unionid（userId={}）：{}", raw, parsed.toString());
                return raw;
            }
            UNION_IDS.put(appKey + "|" + raw, new CachedToken(unionId, Instant.now().plusSeconds(6 * 3600)));
            return unionId;
        } catch (RuntimeException ex) {
            org.slf4j.LoggerFactory.getLogger(DingTalkDocClient.class)
                    .warn("staffId → unionId 换算失败（userId={}）：{}", raw, ex.getMessage());
        } catch (Exception ex) {
            org.slf4j.LoggerFactory.getLogger(DingTalkDocClient.class)
                    .warn("staffId → unionId 换算异常（userId={}）：{}", raw, ex.toString());
        }
        return raw;
    }

    // ===================== 读 =====================

    /** 我参与的知识库（wiki）。 */
    public JsonNode mineWorkspaces(int maxResults) {
        return get("/v2.0/wiki/mineWorkspaces", params(
                "operatorId", operatorId,
                "maxResults", String.valueOf(clamp(maxResults, 1, 50))));
    }

    /** 文档空间列表（团队空间/知识库）。 */
    public JsonNode docWorkspaces(int maxResults) {
        return get("/v1.0/doc/workspaces", params(
                "operatorId", operatorId,
                "maxResults", String.valueOf(clamp(maxResults, 1, 50))));
    }

    /** 搜索文档（按关键词，可限定知识库）。 */
    public JsonNode searchDocs(String keyword, String workspaceId, int maxResults) {
        Map<String, String> query = params(
                "operatorId", operatorId,
                "keyword", keyword == null ? "" : keyword,
                "maxResults", String.valueOf(clamp(maxResults, 1, 50)));
        if (workspaceId != null && !workspaceId.isBlank()) {
            query.put("workspaceId", workspaceId);
        }
        return get("/v1.0/doc/docs", query);
    }

    /** 我最近编辑的文档。 */
    public JsonNode recentEditDocs(int maxResults) {
        return get("/v1.0/doc/workspaces/docs/recentEditDocs", params(
                "operatorId", operatorId,
                "maxResults", String.valueOf(clamp(maxResults, 1, 50))));
    }

    /** 读文档正文块（分页）。 */
    public JsonNode blocks(String docKey, int startIndex, int endIndex) {
        return get("/v1.0/doc/suites/documents/" + encode(docKey) + "/blocks", params(
                "operatorId", operatorId,
                "startIndex", String.valueOf(Math.max(startIndex, 0)),
                "endIndex", String.valueOf(Math.max(endIndex, 1))));
    }

    /** 通过链接解析知识库节点（用户直接粘链接时用）。 */
    public JsonNode nodeByUrl(String url) {
        return get("/v2.0/wiki/nodes/queryByUrl", params(
                "operatorId", operatorId,
                "url", url == null ? "" : url));
    }

    /** 知识库节点详情。 */
    public JsonNode node(String nodeId) {
        return get("/v2.0/wiki/nodes/" + encode(nodeId), params("operatorId", operatorId));
    }

    // ===================== 写 =====================

    /**
     * 在知识库/空间里新建文档。
     *
     * @return 创建结果（含 docKey / dentryUuid / nodeId / url）
     */
    public JsonNode createDoc(String workspaceId, String name, String parentNodeId) {
        ObjectNode body = JSON.createObjectNode();
        body.put("name", name == null || name.isBlank() ? "未命名文档" : name);
        body.put("operatorId", operatorId);
        body.put("docType", "doc");
        if (parentNodeId != null && !parentNodeId.isBlank()) {
            body.put("parentNodeId", parentNodeId);
        }
        return post("/v1.0/doc/workspaces/" + encode(workspaceId) + "/docs", body);
    }

    /**
     * 覆盖整篇内容。
     *
     * <p>钉钉的覆盖接口要 {@code content + dataType}；先按 {@code markdown} 提交，
     * 如果这个 dataType 不被接受（不同租户/版本可能只支持 text），自动降级成纯文本重试一次。
     */
    public JsonNode overwriteContent(String docKey, String markdown) {
        try {
            return overwriteContent(docKey, markdown, "markdown");
        } catch (DingTalkException ex) {
            if (!ex.getMessage().contains("dataType")) {
                throw ex;
            }
            return overwriteContent(docKey, stripMarkdown(markdown), "text");
        }
    }

    private JsonNode overwriteContent(String docKey, String content, String dataType) {
        ObjectNode body = JSON.createObjectNode();
        body.put("content", content == null ? "" : content);
        body.put("dataType", dataType);
        body.put("operatorId", operatorId);
        return post("/v1.0/doc/suites/documents/" + encode(docKey) + "/overwriteContent", body);
    }

    /** 删文档（放进回收站）。 */
    public JsonNode deleteDoc(String workspaceId, String docKey) {
        ObjectNode body = JSON.createObjectNode();
        body.put("operatorId", operatorId);
        body.put("docKey", docKey);
        return delete("/v1.0/doc/workspaces/" + encode(workspaceId) + "/docs", body);
    }

    // ===================== 传输 =====================

    private JsonNode get(String path, Map<String, String> query) {
        String url = BASE + path + (query.isEmpty() ? "" : "?" + toQuery(query));
        return send("GET", url, null);
    }

    private JsonNode post(String path, JsonNode body) {
        return send("POST", BASE + path, body);
    }

    private JsonNode delete(String path, JsonNode body) {
        return send("DELETE", BASE + path, body);
    }

    private JsonNode send(String method, String url, JsonNode body) {
        return send(method, url, body, true);
    }

    private JsonNode send(String method, String url, JsonNode body, boolean withOperator) {
        throttle();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("x-acs-dingtalk-access-token", accessToken())
                .header("Content-Type", "application/json");
        if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(
                    body == null ? "{}" : body.toString(), StandardCharsets.UTF_8));
        }
        try {
            HttpResponse<String> response =
                    http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String raw = response.body();
            if (response.statusCode() >= 400) {
                DingTalkException error = toHumanError(response.statusCode(), raw);
                if (withOperator && raw != null && raw.contains("operatorId")) {
                    String unionId = resolveUnionId(operatorId);
                    if (unionId != null && !unionId.equals(operatorId)) {
                        return send(method, withOperator(url, unionId), body, false);
                    }
                    String reason = UNION_ID_ERRORS.get(appKey + "|" + operatorId);
                    if (reason != null && !reason.isBlank()) {
                        throw new DingTalkException(unionIdHint(reason));
                    }
                }
                throw error;
            }
            return raw == null || raw.isBlank() ? JSON.createObjectNode() : JSON.readTree(raw);
        } catch (DingTalkException ex) {
            throw ex;
        } catch (java.io.IOException ex) {
            throw new DingTalkException("连接钉钉失败（网络问题？）：" + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new DingTalkException("请求被中断");
        }
    }

    /** 取（并缓存）企业应用 access token。 */
    private String accessToken() {
        CachedToken cached = TOKENS.get(appKey);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.token();
        }
        if (appKey.isBlank() || appSecret.isBlank()) {
            throw new DingTalkException("钉钉应用凭据没有配：请管理员在「能力 → 连接器」对应的钉钉应用里配置 appKey/appSecret"
                    + "（平台会复用 IM 渠道里那个钉钉应用的凭据）");
        }
        ObjectNode body = JSON.createObjectNode();
        body.put("appKey", appKey);
        body.put("appSecret", appSecret);
        JsonNode response;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE + "/v1.0/oauth2/accessToken"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> raw =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (raw.statusCode() >= 400) {
                throw toHumanError(raw.statusCode(), raw.body());
            }
            response = JSON.readTree(raw.body());
        } catch (DingTalkException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DingTalkException("获取钉钉 access token 失败：" + ex.getMessage());
        }
        String token = response.path("accessToken").asText("");
        long expireIn = response.path("expireIn").asLong(7200);
        if (token.isBlank()) {
            throw new DingTalkException("钉钉没有返回 access token（检查 appKey/appSecret 是否正确）");
        }
        TOKENS.put(appKey, new CachedToken(token, Instant.now().plusSeconds(Math.max(expireIn - 60, 60))));
        return token;
    }

    /**
     * 把"缺通讯录权限"翻成用户能直接操作的提示（钉钉会在错误里给申请链接，抽出来给他）。
     */
    static String unionIdHint(String reason) {
        String link = "";
        String marker = "https://open-dev.dingtalk.com/appscope/apply?content=";
        String text = reason == null ? "" : reason;
        int at = text.indexOf(marker);
        if (at >= 0) {
            int end = at;
            while (end < text.length() && ", ]".indexOf(text.charAt(end)) < 0
                    && !Character.isWhitespace(text.charAt(end))) {
                end++;
            }
            link = text.substring(at, end);
        }
        return "钉钉文档接口的 operatorId 要的是 **unionId**，而机器人里拿到的是 staffId，"
                + "换算需要「通讯录成员信息读权限」(qyapi_get_member)。"
                + (link.isBlank() ? "请到开放平台给应用勾选该权限后再试。" : "点这个链接一键申请/开通：" + link)
                + " 开通后重新点「测试连接」即可（不需要重填任何东西）。";
    }

    /** 把 URL 里 operatorId 的值换成 unionId（用于参数错误后的重试）。 */
    private static String withOperator(String url, String unionId) {
        return url.replaceAll("operatorId=[^&]*", "operatorId=" + encode(unionId));
    }

    private void throttle() {
        long since = Instant.now().toEpochMilli() - lastCallAt;
        if (since < 120) {
            try {
                Thread.sleep(120 - since);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        lastCallAt = Instant.now().toEpochMilli();
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static Map<String, String> params(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (pairs[i] != null) {
                map.put(pairs[i], pairs[i + 1] == null ? "" : pairs[i + 1]);
            }
        }
        return map;
    }

    private static String toQuery(Map<String, String> query) {
        StringBuilder builder = new StringBuilder();
        query.forEach((key, value) -> {
            if (builder.length() > 0) {
                builder.append('&');
            }
            builder.append(encode(key)).append('=').append(encode(value));
        });
        return builder.toString();
    }

    /** 钉钉错误 → 人话。 */
    static DingTalkException toHumanError(int status, String rawBody) {
        String code = "";
        String message = "";
        String requestId = "";
        try {
            JsonNode node = JSON.readTree(rawBody == null ? "{}" : rawBody);
            code = node.path("code").asText("");
            message = node.path("message").asText("");
            requestId = node.path("requestid").asText("");
        } catch (Exception ignored) {
            message = rawBody == null ? "" : rawBody;
        }
        String suffix = requestId.isBlank() ? "" : "（requestid: " + requestId + "）";
        String lowerCode = code.toLowerCase(java.util.Locale.ROOT);
        if (status == 401 || lowerCode.contains("invalidauthentication")) {
            return new DingTalkException("钉钉应用凭据无效或已过期：请管理员检查 appKey/appSecret" + suffix);
        }
        if (lowerCode.contains("forbidden") || status == 403
                || message.contains("权限") || message.contains("permission")) {
            return new DingTalkException("没有权限操作这个文档/知识库：" + explainPermission() + suffix);
        }
        if (status == 404 || lowerCode.contains("notfound")) {
            return new DingTalkException("找不到这个文档/知识库：可能是 id 不对，或这个钉钉账号本身没有访问权" + suffix);
        }
        if (status == 429 || lowerCode.contains("throttl") || lowerCode.contains("limit")) {
            return new DingTalkException("触发钉钉接口限流，请稍后重试" + suffix);
        }
        return new DingTalkException("钉钉接口出错（HTTP " + status + "）："
                + (message.isBlank() ? code : message) + suffix);
    }

    private static String explainPermission() {
        return "请确认 ① 钉钉应用已在开放平台勾选「文档/知识库」的读写权限；"
                + "② 该应用可见范围包含你；③ 这个文档/知识库本身对你有访问权"
                + "（钉钉文档的权限跟着人走，不是跟着应用走）";
    }

    /** 粗略去掉 Markdown 语法（仅用于 dataType=text 的降级）。 */
    static String stripMarkdown(String markdown) {
        if (markdown == null) {
            return "";
        }
        return markdown
                .replaceAll("(?m)^#{1,6}\\s*", "")
                .replaceAll("(?m)^\\s*[-*+]\\s+\\[[ xX]]\\s*", "")
                .replaceAll("(?m)^\\s*[-*+]\\s+", "")
                .replaceAll("(?m)^>\\s*", "")
                .replaceAll("```[a-zA-Z]*", "")
                .replaceAll("\\*\\*(.+?)\\*\\*", "$1")
                .replaceAll("`([^`]+)`", "$1");
    }

    /** 钉钉接口的业务异常（message 直接可展示）。 */
    public static class DingTalkException extends RuntimeException {
        public DingTalkException(String message) {
            super(message);
        }
    }
}
