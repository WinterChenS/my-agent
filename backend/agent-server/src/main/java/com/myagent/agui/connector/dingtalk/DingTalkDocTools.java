package com.myagent.agui.connector.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
import com.myagent.agui.connector.ConnectorToolResolver;
import com.myagent.connectorconfig.ConnectorService;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 钉钉文档工具（P0）：列知识库 / 搜文档 / 最近编辑 / 读正文 / 按链接读 / 新建 / 追加 / 整篇覆盖。
 *
 * <p>和 Notion 工具同一套骨架：
 * 工具无状态 → 每次调用按当前用户解析连接器 → 写操作过 {@code allowWrite} 与白名单的闸 →
 * 写操作 {@code isReadOnly=false} 走审批卡 → 审计落库。
 *
 * <p>「追加」的实现说明：钉钉的块级追加需要准确的 blockId/element 结构，猜错会写坏文档；
 * P0 先用**读当前正文 → 拼接 → 覆盖**的方式实现（语义等价，风险可控），
 * 等用真实文档验证过块结构再换成原生块插入。
 */
public final class DingTalkDocTools {

    /** 换行常量（避免源码里的转义被工具链吃掉）。 */
    private static final String NL = System.lineSeparator();

    private DingTalkDocTools() {
    }

    public static List<AgentTool> create(
            long agentId,
            ConnectorService connectorService,
            DingTalkAppCredentials credentials,
            DingTalkOperatorResolver operatorResolver) {
        return List.of(
                new ListWorkspacesTool(agentId, connectorService, credentials, operatorResolver),
                new SearchDocsTool(agentId, connectorService, credentials, operatorResolver),
                new RecentDocsTool(agentId, connectorService, credentials, operatorResolver),
                new GetDocTool(agentId, connectorService, credentials, operatorResolver),
                new GetDocByUrlTool(agentId, connectorService, credentials, operatorResolver),
                new CreateWorkspaceTool(agentId, connectorService, credentials, operatorResolver),
                new CreateDocTool(agentId, connectorService, credentials, operatorResolver),
                new AppendContentTool(agentId, connectorService, credentials, operatorResolver),
                new DeleteDocTool(agentId, connectorService, credentials, operatorResolver),
                new OverwriteContentTool(agentId, connectorService, credentials, operatorResolver));
    }

    // ===================== 基类 =====================

    /** 工具上下文：连接器 + 配置 + 已解析 operatorId 的客户端。 */
    record Ctx(ConnectorService.ResolvedConnector connector, DingTalkDocSettings settings,
            DingTalkDocClient client) {}

    abstract static class AbstractDingTalkTool implements AgentTool {

        final long agentId;
        final ConnectorService connectorService;
        final DingTalkAppCredentials credentials;
        final DingTalkOperatorResolver operatorResolver;

        AbstractDingTalkTool(long agentId, ConnectorService connectorService,
                DingTalkAppCredentials credentials, DingTalkOperatorResolver operatorResolver) {
            this.agentId = agentId;
            this.connectorService = connectorService;
            this.credentials = credentials;
            this.operatorResolver = operatorResolver;
        }

        protected abstract String run(Ctx ctx, ToolCallParam param);

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.fromCallable(() -> execute(param))
                    .subscribeOn(Schedulers.boundedElastic())
                    .onErrorResume(ex -> Mono.just(text("钉钉文档操作失败：" + human(ex))));
        }

        private ToolResultBlock execute(ToolCallParam param) {
            String userId = param.getRuntimeContext() == null ? null : param.getRuntimeContext().getUserId();
            ConnectorToolResolver.Resolution resolution = ConnectorToolResolver.resolve(
                    connectorService, agentId, "DINGTALK_DOC", userId,
                    asString(param.getInput().get("connector")));
            if (!resolution.ok()) {
                return text(resolution.error());
            }
            ConnectorService.ResolvedConnector connector = resolution.picked();
            DingTalkDocSettings settings = DingTalkDocSettings.of(connector.config());

            String operatorId = settings.operatorId();
            if (operatorId == null || operatorId.isBlank()) {
                // 自动映射：用户跟钉钉机器人说过话就能对上（channel_peer → staffId）
                Long uid = parseLong(userId);
                operatorId = uid == null ? null : operatorResolver.resolve(uid);
            }
            if (operatorId == null || operatorId.isBlank()) {
                return text("拿不到你的钉钉身份（operatorId）：请让用户到「能力 → 连接器 → 该连接器」里"
                        + "填写「操作人（钉钉 userId）」。或者在钉钉里先给机器人发一条消息，再回来重试。");
            }

            Map<String, String> appCredentials = credentials.resolve();
            if (appCredentials == null) {
                return text("平台还没有可用的钉钉应用凭据：请管理员在「系统设置 → IM 渠道」里配置钉钉应用"
                        + "（appKey/appSecret），或在配置里提供 myagent.connector.dingtalk-doc.app-key/app-secret。");
            }
            DingTalkDocClient client = new DingTalkDocClient(
                    appCredentials.get("appKey"), appCredentials.get("appSecret"), operatorId);
            return text(run(new Ctx(connector, settings, client), param));
        }

        /** 写操作闸门：允许写入 + 文档/知识库白名单。返回 null 表示放行。 */
        String writeGuard(DingTalkDocSettings settings, String docOrWorkspaceId, boolean isWorkspace) {
            if (!settings.allowWrite()) {
                return "这个钉钉文档连接器关闭了「允许写入」，Agent 只能读不能改。"
                        + "请让用户到「能力 → 连接器」里把「允许写入」打开后再试。";
            }
            boolean allowed = isWorkspace
                    ? settings.allowsWorkspace(docOrWorkspaceId)
                    : settings.allowsDoc(docOrWorkspaceId);
            if (!allowed) {
                return "这个" + (isWorkspace ? "知识库" : "文档") + "不在连接器的白名单里（id="
                        + docOrWorkspaceId + "）。请让用户到「能力 → 连接器」里把它加进白名单。";
            }
            return null;
        }

        static Long parseLong(String value) {
            try {
                return value == null ? null : Long.parseLong(value.trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }

        static String requireId(Object raw, String field) {
            String id = DingTalkDocSettings.Ids.normalize(asString(raw));
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("参数 " + field + " 不是有效的钉钉文档/知识库 id 或链接："
                        + asString(raw));
            }
            return id;
        }

        static String asString(Object value) {
            return value == null ? null : String.valueOf(value);
        }

        static Integer asInt(Object value, Integer fallback) {
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

        static Map<String, Object> connectorParam() {
            return Map.of("connector", Map.of(
                    "type", "string",
                    "description", "可选：用哪个钉钉文档连接器（别名或名称）。只绑定了一个时可省略"));
        }

        static ToolResultBlock text(String content) {
            return ToolResultBlock.builder().output(TextBlock.builder().text(content).build()).build();
        }

        static String human(Throwable ex) {
            Throwable current = ex;
            while (current.getCause() != null && current.getCause() != current) {
                current = current.getCause();
            }
            String message = current.getMessage();
            return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
        }

        static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", properties);
            if (!required.isEmpty()) {
                schema.put("required", required);
            }
            return schema;
        }

        /** 从钉钉返回里找列表（不同接口字段名不一致：result.data / data / workspaces / docs / nodes）。 */
        static List<JsonNode> items(JsonNode response, String... keys) {
            List<JsonNode> result = new ArrayList<>();
            JsonNode node = response.path("result");
            if (node.isMissingNode()) {
                node = response;
            }
            for (String key : keys) {
                JsonNode candidate = node.path(key);
                if (candidate.isArray()) {
                    candidate.forEach(result::add);
                    return result;
                }
            }
            // 兜底：result 本身就是数组
            if (node.isArray()) {
                node.forEach(result::add);
            }
            return result;
        }

        /** 一行文档信息（钉钉的返回是 docs[].nodeBO + workspaceBO 这种嵌套结构）。 */
        record DocRow(String name, String docKey, String workspaceId, String url, String editTime) {}

        /**
         * 解析文档列表。
         *
         * <p>实测钉钉的返回长这样（别再猜字段名了）：
         * <pre>
         * {"docs":[{"workspaceBO":{"name":"...","workspaceId":"KrwmPZLYAnPdz79"},
         *           "nodeBO":{"docType":"DOC","name":"...","nodeId":"KrwmPNgOJ3v90z79",
         *                     "lastEditTime":1777372915000,"url":"https://alidocs.dingtalk.com/i/team/.../docs/..."}}],
         *  "nextToken":"...","hasMore":true}
         * </pre>
         */
        static List<DocRow> docRows(JsonNode response) {
            List<DocRow> rows = new ArrayList<>();
            for (JsonNode item : items(response, "docs", "documents", "data", "result")) {
                JsonNode node = item.path("nodeBO").isObject() ? item.path("nodeBO") : item;
                JsonNode workspace = item.path("workspaceBO");
                String name = nameOf(node, "name", "title");
                if (name.isBlank()) {
                    name = nameOf(item, "name", "title");
                }
                String docKey = nameOf(node, "nodeId", "docKey", "dentryUuid", "id");
                if (docKey.isBlank()) {
                    docKey = nameOf(item, "docKey", "dentryUuid", "nodeId", "id");
                }
                String workspaceId = nameOf(workspace, "workspaceId");
                if (workspaceId.isBlank()) {
                    workspaceId = nameOf(item, "workspaceId");
                }
                String url = nameOf(node, "url");
                if (url.isBlank()) {
                    url = nameOf(item, "url");
                }
                String editTime = node.path("lastEditTime").isNumber()
                        ? java.time.Instant.ofEpochMilli(node.path("lastEditTime").asLong()).toString()
                        : nameOf(node, "updateTime", "modifiedTime");
                if (!name.isBlank() || !docKey.isBlank()) {
                    rows.add(new DocRow(name, docKey, workspaceId, url, editTime));
                }
            }
            return rows;
        }

        static String nameOf(JsonNode item, String... keys) {
            for (String key : keys) {
                String value = item.path(key).asText("");
                if (!value.isBlank()) {
                    return value;
                }
            }
            return "";
        }
    }

    // ===================== 读 =====================

    /** 列知识库/文档空间。 */
    static final class ListWorkspacesTool extends AbstractDingTalkTool {

        ListWorkspacesTool(long agentId, ConnectorService connectorService,
                DingTalkAppCredentials credentials, DingTalkOperatorResolver operatorResolver) {
            super(agentId, connectorService, credentials, operatorResolver);
        }

        @Override
        public String getName() {
            return "dt_list_workspaces";
        }

        @Override
        public String getDescription() {
            return "列出你能访问的钉钉**知识库/文档空间**（含 id、名称、链接）。"
                    + "新建文档前先用它确定要写到哪个知识库。";
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> properties = new LinkedHashMap<>(connectorParam());
            properties.put("limit", Map.of("type", "integer", "description", "可选：最多返回几个（默认 10，最大 50）"));
            return schema(properties, List.of());
        }

        @Override
        protected String run(Ctx ctx, ToolCallParam param) {
            int limit = Math.min(Math.max(asInt(param.getInput().get("limit"), 10), 1), 50);
            List<JsonNode> wiki = items(safe(() -> ctx.client().mineWorkspaces(limit)), "workspaces", "result", "data");
            List<JsonNode> spaces = items(safe(() -> ctx.client().docWorkspaces(limit)), "workspaces", "result", "data");
            if (wiki.isEmpty() && spaces.isEmpty()) {
                return "没查到可访问的知识库/文档空间。可能是：① 钉钉应用没开文档权限；"
                        + "② 你的钉钉账号还没加入任何知识库。";
            }
            StringBuilder out = new StringBuilder("可访问的知识库/空间：\n");
            for (JsonNode item : wiki) {
                out.append("- [知识库] ").append(nameOf(item, "name", "title"))
                        .append(" | id=").append(nameOf(item, "workspaceId", "id"))
                        .append(nameOf(item, "url").isBlank() ? "" : " | " + nameOf(item, "url"))
                        .append('\n');
            }
            for (JsonNode item : spaces) {
                out.append("- [空间] ").append(nameOf(item, "name", "title"))
                        .append(" | id=").append(nameOf(item, "workspaceId", "id"))
                        .append('\n');
            }
            return out.toString();
        }

        private static JsonNode safe(java.util.function.Supplier<JsonNode> supplier) {
            try {
                return supplier.get();
            } catch (RuntimeException ex) {
                return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            }
        }
    }

    /** 搜索文档。 */
    static final class SearchDocsTool extends AbstractDingTalkTool {

        SearchDocsTool(long agentId, ConnectorService connectorService,
                DingTalkAppCredentials credentials, DingTalkOperatorResolver operatorResolver) {
            super(agentId, connectorService, credentials, operatorResolver);
        }

        @Override
        public String getName() {
            return "dt_search_docs";
        }

        @Override
        public String getDescription() {
            return "在钉钉文档里搜索（按标题/关键词）。可限定某个知识库，返回文档标题、id、链接、更新时间。"
                    + "拿到 docKey 后用 dt_get_doc 读正文。";
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> properties = new LinkedHashMap<>(connectorParam());
            properties.put("keyword", Map.of("type", "string", "description", "搜索关键词"));
            properties.put("workspaceId", Map.of("type", "string", "description", "可选：只在某个知识库内搜"));
            properties.put("limit", Map.of("type", "integer", "description", "可选：最多返回几条（默认 10，最大 50）"));
            return schema(properties, List.of("keyword"));
        }

        @Override
        protected String run(Ctx ctx, ToolCallParam param) {
            String keyword = asString(param.getInput().get("keyword"));
            if (keyword == null || keyword.isBlank()) {
                return "请给出搜索关键词（keyword）。";
            }
            String workspaceId = DingTalkDocSettings.Ids.normalize(asString(param.getInput().get("workspaceId")));
            JsonNode response = ctx.client().searchDocs(keyword, workspaceId,
                    Math.min(Math.max(asInt(param.getInput().get("limit"), 10), 1), 50));
            List<DocRow> docs = docRows(response);
            if (docs.isEmpty()) {
                return "没搜到匹配的文档。可以换个关键词，或者不带 workspaceId 全局搜。";
            }
            StringBuilder out = new StringBuilder("搜到 ").append(docs.size()).append(" 篇：").append(NL);
            for (DocRow doc : docs) {
                out.append("- ").append(doc.name())
                        .append(" | docKey=").append(doc.docKey())
                        .append(doc.workspaceId().isBlank() ? "" : " | workspaceId=" + doc.workspaceId())
                        .append(doc.url().isBlank() ? "" : " | " + doc.url())
                        .append(NL);
            }
            return out.toString();
        }
    }

    /** 最近编辑的文档。 */
    static final class RecentDocsTool extends AbstractDingTalkTool {

        RecentDocsTool(long agentId, ConnectorService connectorService,
                DingTalkAppCredentials credentials, DingTalkOperatorResolver operatorResolver) {
            super(agentId, connectorService, credentials, operatorResolver);
        }

        @Override
        public String getName() {
            return "dt_recent_docs";
        }

        @Override
        public String getDescription() {
            return "列出**你最近编辑过**的钉钉文档（用户说「我上周写的那个文档」时用它找）。";
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> properties = new LinkedHashMap<>(connectorParam());
            properties.put("limit", Map.of("type", "integer", "description", "可选：最多返回几条（默认 10）"));
            return schema(properties, List.of());
        }

        @Override
        protected String run(Ctx ctx, ToolCallParam param) {
            JsonNode response = ctx.client().recentEditDocs(
                    Math.min(Math.max(asInt(param.getInput().get("limit"), 10), 1), 50));
            List<DocRow> docs = docRows(response);
            if (docs.isEmpty()) {
                return "最近没有编辑记录（或你的钉钉账号没有文档权限）。";
            }
            StringBuilder out = new StringBuilder("最近编辑的文档：").append(NL);
            for (DocRow doc : docs) {
                out.append("- ").append(doc.name())
                        .append(" | docKey=").append(doc.docKey())
                        .append(doc.workspaceId().isBlank() ? "" : " | workspaceId=" + doc.workspaceId())
                        .append(doc.editTime().isBlank() ? "" : " | " + doc.editTime())
                        .append(NL);
            }
            return out.toString();
        }
    }

    /** 读文档正文。 */
    static final class GetDocTool extends AbstractDingTalkTool {

        GetDocTool(long agentId, ConnectorService connectorService,
                DingTalkAppCredentials credentials, DingTalkOperatorResolver operatorResolver) {
            super(agentId, connectorService, credentials, operatorResolver);
        }

        @Override
        public String getName() {
            return "dt_get_doc";
        }

        @Override
        public String getDescription() {
            return "读取一篇钉钉文档的正文（转成 Markdown，最多读连接器配置的块数，超出会明确提示）。"
                    + "docKey 可以传 id，也可以直接粘文档链接。";
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> properties = new LinkedHashMap<>(connectorParam());
            properties.put("docKey", Map.of("type", "string", "description", "文档 docKey 或完整链接"));
            properties.put("maxBlocks", Map.of("type", "integer", "description", "可选：最多读多少块（默认用连接器配置）"));
            return schema(properties, List.of("docKey"));
        }

        @Override
        protected String run(Ctx ctx, ToolCallParam param) {
            String docKey = requireId(param.getInput().get("docKey"), "docKey");
            if (!ctx.settings().allowsDoc(docKey)) {
                return "这篇文档不在连接器的白名单里（docKey=" + docKey + "）。"
                        + "请让用户到「能力 → 连接器」里把它加进白名单。";
            }
            int limit = asInt(param.getInput().get("maxBlocks"), ctx.settings().maxBlocks());
            JsonNode response = ctx.client().blocks(docKey, 0, limit);
            String markdown = DingTalkDocMarkdown.toMarkdown(response);
            if (markdown.isBlank()) {
                return "这篇文档读出来是空的（可能是多维表/表格类文档，P0 只支持文档正文；"
                        + "也可能 block 结构不在当前解析范围内，可以告诉我，我再补）。";
            }
            return "文档 docKey=" + docKey + " 的正文（Markdown）：\n\n" + markdown;
        }
    }

    /** 按链接读文档（用户常直接粘链接）。 */
    static final class GetDocByUrlTool extends AbstractDingTalkTool {

        GetDocByUrlTool(long agentId, ConnectorService connectorService,
                DingTalkAppCredentials credentials, DingTalkOperatorResolver operatorResolver) {
            super(agentId, connectorService, credentials, operatorResolver);
        }

        @Override
        public String getName() {
            return "dt_get_doc_by_url";
        }

        @Override
        public String getDescription() {
            return "用户直接给你一个钉钉文档链接时用它：先解析链接拿节点信息，再读正文。";
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> properties = new LinkedHashMap<>(connectorParam());
            properties.put("url", Map.of("type", "string", "description", "钉钉文档/知识库链接"));
            return schema(properties, List.of("url"));
        }

        @Override
        protected String run(Ctx ctx, ToolCallParam param) {
            String url = asString(param.getInput().get("url"));
            if (url == null || url.isBlank()) {
                return "请提供文档链接（url）。";
            }
            JsonNode node = ctx.client().nodeByUrl(url);
            String docKey = nameOf(node.path("result"), "docKey", "dentryUuid", "nodeId");
            if (docKey.isBlank()) {
                docKey = DingTalkDocSettings.Ids.normalize(url);
            }
            if (docKey == null || docKey.isBlank()) {
                return "没能从这个链接解析出文档 id，请把链接完整贴一下（形如 https://alidocs.dingtalk.com/i/nodes/...）。";
            }
            if (!ctx.settings().allowsDoc(docKey)) {
                return "这篇文档不在连接器的白名单里（docKey=" + docKey + "）。";
            }
            JsonNode blocks = ctx.client().blocks(docKey, 0, ctx.settings().maxBlocks());
            String markdown = DingTalkDocMarkdown.toMarkdown(blocks);
            return markdown.isBlank()
                    ? "链接解析到 id=" + docKey + "，但正文读出来是空的（可能不是文档类型）。"
                    : "文档 " + url + " 的正文（Markdown）：\n\n" + markdown;
        }
    }

    // ===================== 写 =====================

    /** 新建知识库（文档要建在知识库里）。 */
    static final class CreateWorkspaceTool extends AbstractDingTalkTool {

        CreateWorkspaceTool(long agentId, ConnectorService connectorService,
                DingTalkAppCredentials credentials, DingTalkOperatorResolver operatorResolver) {
            super(agentId, connectorService, credentials, operatorResolver);
        }

        @Override
        public String getName() {
            return "dt_create_workspace";
        }

        @Override
        public String getDescription() {
            return "新建一个钉钉**知识库**（只有知识库才能用 API 新建文档；「我的文档」不行）。"
                    + "返回 workspaceId，之后用 dt_create_doc 往它里面写文档。";
        }

        @Override
        public boolean isReadOnly() {
            return false;
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> properties = new LinkedHashMap<>(connectorParam());
            properties.put("name", Map.of("type", "string", "description", "知识库名称"));
            properties.put("description", Map.of("type", "string", "description", "可选：描述"));
            return schema(properties, List.of("name"));
        }

        @Override
        protected String run(Ctx ctx, ToolCallParam param) {
            if (!ctx.settings().allowWrite()) {
                return "这个钉钉文档连接器关闭了「允许写入」，不能新建知识库。";
            }
            String name = asString(param.getInput().get("name"));
            JsonNode created = ctx.client().createWorkspace(name, asString(param.getInput().get("description")));
            JsonNode body = created.path("result").isMissingNode() ? created : created.path("result");
            String workspaceId = nameOf(body, "workspaceId", "id");
            connectorService.logAction(ctx.connector().record().id(),
                    ctx.connector().record().ownerUserId(), "CREATE_WORKSPACE", workspaceId, name, true, null);
            return "已创建知识库「" + name + "」：workspaceId=" + workspaceId
                    + (nameOf(body, "url").isBlank() ? "" : "，链接：" + nameOf(body, "url"))
                    + NL + "接下来可以用 dt_create_doc 在它里面新建文档。";
        }
    }

    /** 新建文档。 */
    static final class CreateDocTool extends AbstractDingTalkTool {

        CreateDocTool(long agentId, ConnectorService connectorService,
                DingTalkAppCredentials credentials, DingTalkOperatorResolver operatorResolver) {
            super(agentId, connectorService, credentials, operatorResolver);
        }

        @Override
        public String getName() {
            return "dt_create_doc";
        }

        @Override
        public String getDescription() {
            return "在钉钉知识库/空间里**新建一篇文档**（会真实写入，需要用户确认）。"
                    + "正文用 Markdown。未指定 workspaceId 时用连接器配置的默认知识库。";
        }

        @Override
        public boolean isReadOnly() {
            return false;
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> properties = new LinkedHashMap<>(connectorParam());
            properties.put("title", Map.of("type", "string", "description", "文档标题"));
            properties.put("markdown", Map.of("type", "string", "description", "可选：正文（Markdown）"));
            properties.put("workspaceId", Map.of("type", "string", "description", "可选：知识库/空间 id（或链接）"));
            properties.put("parentNodeId", Map.of("type", "string", "description", "可选：父节点 id（放在某个目录下）"));
            return schema(properties, List.of("title"));
        }

        @Override
        protected String run(Ctx ctx, ToolCallParam param) {
            String workspaceId = DingTalkDocSettings.Ids.normalize(asString(param.getInput().get("workspaceId")));
            if (workspaceId == null || workspaceId.isBlank()) {
                workspaceId = ctx.settings().defaultWorkspaceId();
            }
            if (workspaceId == null || workspaceId.isBlank()) {
                return "没有指定知识库：请在参数 workspaceId 里给一个知识库 id，"
                        + "或让用户到「能力 → 连接器」里配置「默认知识库/空间」。";
            }
            String rejection = writeGuard(ctx.settings(), workspaceId, true);
            if (rejection != null) {
                return rejection;
            }
            String title = asString(param.getInput().get("title"));
            JsonNode created = ctx.client().createDoc(workspaceId, title,
                    DingTalkDocSettings.Ids.normalize(asString(param.getInput().get("parentNodeId"))));
            JsonNode body = created.path("result").isMissingNode() ? created : created.path("result");
            String docKey = nameOf(body, "docKey", "dentryUuid", "nodeId");
            String markdown = asString(param.getInput().get("markdown"));
            String note = "";
            if (markdown != null && !markdown.isBlank() && !docKey.isBlank()) {
                ctx.client().overwriteContent(docKey, markdown);
                note = "，正文已写入";
            }
            connectorService.logAction(ctx.connector().record().id(),
                    ctx.connector().record().ownerUserId(), "CREATE_DOC", docKey, title, true, null);
            String nodeId = nodeIdOf(nameOf(body, "url"), docKey);
            return "已创建文档「" + title + "」：docKey=" + docKey
                    + (nodeId == null ? "" : "，nodeId=" + nodeId)
                    + (nameOf(body, "url").isBlank() ? "" : "，链接：" + nameOf(body, "url")) + note;
        }
    }

    /** 追加内容（读-拼-覆盖）。 */
    static final class AppendContentTool extends AbstractDingTalkTool {

        AppendContentTool(long agentId, ConnectorService connectorService,
                DingTalkAppCredentials credentials, DingTalkOperatorResolver operatorResolver) {
            super(agentId, connectorService, credentials, operatorResolver);
        }

        @Override
        public String getName() {
            return "dt_append_content";
        }

        @Override
        public String getDescription() {
            return "在已有钉钉文档**末尾追加内容**（Markdown）。"
                    + "实现方式是「读当前正文 → 拼接 → 覆盖」，所以不会动到看不见的结构，但会重写整篇。";
        }

        @Override
        public boolean isReadOnly() {
            return false;
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> properties = new LinkedHashMap<>(connectorParam());
            properties.put("docKey", Map.of("type", "string", "description", "文档 docKey 或链接"));
            properties.put("markdown", Map.of("type", "string", "description", "要追加的 Markdown 内容"));
            return schema(properties, List.of("docKey", "markdown"));
        }

        @Override
        protected String run(Ctx ctx, ToolCallParam param) {
            String docKey = requireId(param.getInput().get("docKey"), "docKey");
            String rejection = writeGuard(ctx.settings(), docKey, false);
            if (rejection != null) {
                return rejection;
            }
            String addition = asString(param.getInput().get("markdown"));
            if (addition == null || addition.isBlank()) {
                return "没有要追加的内容（markdown 为空）。";
            }
            String current = DingTalkDocMarkdown.toMarkdown(
                    ctx.client().blocks(docKey, 0, ctx.settings().maxBlocks()));
            String merged = current.isBlank() ? addition : current + "\n\n" + addition;
            ctx.client().overwriteContent(docKey, merged);
            connectorService.logAction(ctx.connector().record().id(),
                    ctx.connector().record().ownerUserId(), "APPEND", docKey,
                    addition.length() + " 字符", true, null);
            return "已往文档 docKey=" + docKey + " 追加内容（" + addition.length() + " 字符）。";
        }
    }

    /** 删除文档（进回收站）。 */
    static final class DeleteDocTool extends AbstractDingTalkTool {

        DeleteDocTool(long agentId, ConnectorService connectorService,
                DingTalkAppCredentials credentials, DingTalkOperatorResolver operatorResolver) {
            super(agentId, connectorService, credentials, operatorResolver);
        }

        @Override
        public String getName() {
            return "dt_delete_doc";
        }

        @Override
        public String getDescription() {
            return "把一篇钉钉文档**移入回收站**（可恢复）。需要 workspaceId + 文档的 nodeId"
                    + "（也可以直接给文档链接，会自动解析）；workspaceId 不填时用连接器的默认知识库。";
        }

        @Override
        public boolean isReadOnly() {
            return false;
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> properties = new LinkedHashMap<>(connectorParam());
            properties.put("urlOrNodeId", Map.of("type", "string", "description", "文档链接或 nodeId"));
            properties.put("workspaceId", Map.of("type", "string", "description", "可选：知识库 id（默认用连接器配置）"));
            return schema(properties, List.of("urlOrNodeId"));
        }

        @Override
        protected String run(Ctx ctx, ToolCallParam param) {
            String raw = asString(param.getInput().get("urlOrNodeId"));
            String workspaceId = DingTalkDocSettings.Ids.normalize(asString(param.getInput().get("workspaceId")));
            if (workspaceId == null || workspaceId.isBlank()) {
                workspaceId = ctx.settings().defaultWorkspaceId();
            }
            if (workspaceId == null || workspaceId.isBlank()) {
                return "没有指定知识库：请给 workspaceId，或让用户在连接器里配置「默认知识库」。";
            }
            String nodeId = nodeIdOf(raw, null);
            if (nodeId == null) {
                return "没能从「" + raw + "」解析出 nodeId，请给文档链接（.../i/nodes/xxx）或直接给 nodeId。";
            }
            String rejection = writeGuard(ctx.settings(), workspaceId, true);
            if (rejection != null) {
                return rejection;
            }
            ctx.client().deleteDoc(workspaceId, nodeId);
            connectorService.logAction(ctx.connector().record().id(),
                    ctx.connector().record().ownerUserId(), "DELETE_DOC", nodeId, "workspace=" + workspaceId,
                    true, null);
            return "已把文档移入回收站（nodeId=" + nodeId + "）。";
        }
    }

    /** 整篇覆盖（高风险）。 */
    static final class OverwriteContentTool extends AbstractDingTalkTool {

        OverwriteContentTool(long agentId, ConnectorService connectorService,
                DingTalkAppCredentials credentials, DingTalkOperatorResolver operatorResolver) {
            super(agentId, connectorService, credentials, operatorResolver);
        }

        @Override
        public String getName() {
            return "dt_overwrite_content";
        }

        @Override
        public String getDescription() {
            return "**用新内容整篇替换**钉钉文档正文（原内容会被覆盖，风险高，必须用户明确同意）。"
                    + "只是想补充内容请用 dt_append_content。";
        }

        @Override
        public boolean isReadOnly() {
            return false;
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> properties = new LinkedHashMap<>(connectorParam());
            properties.put("docKey", Map.of("type", "string", "description", "文档 docKey 或链接"));
            properties.put("markdown", Map.of("type", "string", "description", "新的完整正文（Markdown）"));
            return schema(properties, List.of("docKey", "markdown"));
        }

        @Override
        protected String run(Ctx ctx, ToolCallParam param) {
            String docKey = requireId(param.getInput().get("docKey"), "docKey");
            String rejection = writeGuard(ctx.settings(), docKey, false);
            if (rejection != null) {
                return rejection;
            }
            String markdown = asString(param.getInput().get("markdown"));
            if (markdown == null || markdown.isBlank()) {
                return "新的正文为空；如果确实要清空，请明确告诉用户这会删掉全部内容。";
            }
            ctx.client().overwriteContent(docKey, markdown);
            connectorService.logAction(ctx.connector().record().id(),
                    ctx.connector().record().ownerUserId(), "OVERWRITE", docKey,
                    markdown.length() + " 字符", true, null);
            return "已覆盖文档 docKey=" + docKey + " 的正文（" + markdown.length() + " 字符）。";
        }
    }

    /** 从文档链接里取 nodeId；链接里没有就用传入的兜底值（可能是 nodeId 本身）。 */
    static String nodeIdOf(String urlOrId, String fallback) {
        if (urlOrId != null && !urlOrId.isBlank()) {
            java.util.regex.Matcher matcher =
                    java.util.regex.Pattern.compile("/nodes/([A-Za-z0-9]{8,})").matcher(urlOrId);
            if (matcher.find()) {
                return matcher.group(1);
            }
            if (!urlOrId.contains("/")) {
                return urlOrId.trim();
            }
        }
        return fallback;
    }

    // ===================== 辅助 =====================

    static Map<String, String> headers() {
        return Map.of();
    }

    /** 供 provider 复用：把异常包成人话（测试里也用到）。 */
    static Function<Throwable, String> humanizer() {
        return AbstractDingTalkTool::human;
    }
}
