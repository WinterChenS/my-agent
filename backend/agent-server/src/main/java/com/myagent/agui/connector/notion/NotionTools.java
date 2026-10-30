package com.myagent.agui.connector.notion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myagent.agui.connector.ConnectorToolResolver;
import com.myagent.connectorconfig.ConnectorService;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Notion 工具（P0：读 + 搜 + 查库 + 新建/追加/修改）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>工具本身**无状态**，每次调用按当前用户解析连接器（多人共用一个 Agent 也不会串号）；</li>
 *   <li>读工具 {@code isReadOnly=true}；写工具 {@code false} → REQUIRE_APPROVAL 的 Agent 会自动弹审批卡；</li>
 *   <li>写操作在工具里再过两道闸：连接器的 {@code allowWrite} 开关、页面/数据库白名单；</li>
 *   <li>输出一律**精简成人能读的样子**（Markdown / 表格），不回原始 JSON，省上下文。</li>
 * </ul>
 */
public final class NotionTools {

    private static final ObjectMapper JSON = new ObjectMapper();

    private NotionTools() {
    }

    public static List<AgentTool> create(long agentId, ConnectorService connectorService) {
        return List.of(
                new SearchTool(agentId, connectorService),
                new GetPageTool(agentId, connectorService),
                new ListDataSourcesTool(agentId, connectorService),
                new QueryDataSourceTool(agentId, connectorService),
                new CreatePageTool(agentId, connectorService),
                new AppendMarkdownTool(agentId, connectorService),
                new UpdatePageTool(agentId, connectorService));
    }

    // ===================== 基类 =====================

    /** 工具上下文：连接器 + 解析好的配置 + 客户端。 */
    record NotionCtx(ConnectorService.ResolvedConnector connector, NotionSettings settings, NotionClient client) {}

    abstract static class AbstractNotionTool implements AgentTool {

        final long agentId;
        final ConnectorService connectorService;

        AbstractNotionTool(long agentId, ConnectorService connectorService) {
            this.agentId = agentId;
            this.connectorService = connectorService;
        }

        protected abstract String run(NotionCtx ctx, ToolCallParam param);

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.fromCallable(() -> execute(param))
                    .subscribeOn(Schedulers.boundedElastic())
                    .onErrorResume(ex -> Mono.just(text("Notion 操作失败：" + human(ex))));
        }

        private ToolResultBlock execute(ToolCallParam param) {
            String userId = param.getRuntimeContext() == null ? null : param.getRuntimeContext().getUserId();
            String wanted = asString(param.getInput().get("connector"));
            ConnectorToolResolver.Resolution resolution =
                    ConnectorToolResolver.resolve(connectorService, agentId, "NOTION", userId, wanted);
            if (!resolution.ok()) {
                return text(resolution.error());
            }
            ConnectorService.ResolvedConnector connector = resolution.picked();
            NotionSettings settings = NotionSettings.of(connector.config());
            return text(run(new NotionCtx(connector, settings, new NotionClient(settings.token())), param));
        }

        /** 写操作前的两道闸：allowWrite 开关 + 白名单。返回 null 表示放行。 */
        String writeGuard(NotionSettings settings, String pageOrDatabaseId, boolean isDataSource) {
            if (!settings.allowWrite()) {
                return "这个 Notion 连接器关闭了「允许写入」，Agent 只能读不能改。"
                        + "请让用户到「能力 → 连接器」里把「允许写入」打开后再试。";
            }
            boolean allowed = isDataSource
                    ? settings.allowsDataSource(pageOrDatabaseId)
                    : settings.allowsPage(pageOrDatabaseId);
            if (!allowed) {
                return "这个 " + (isDataSource ? "数据库" : "页面") + " 不在连接器的白名单里（id="
                        + pageOrDatabaseId + "）。请让用户到「能力 → 连接器」里把它加进白名单，或确认 id 是否写错。";
            }
            return null;
        }

        static String requireId(Object raw, String field) {
            String id = NotionSettings.NotionIds.normalizeId(asString(raw));
            if (id == null) {
                throw new IllegalArgumentException("参数 " + field + " 不是有效的 Notion id 或链接：" + asString(raw));
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

        static boolean asBool(Object value, boolean fallback) {
            if (value == null) {
                return fallback;
            }
            if (value instanceof Boolean bool) {
                return bool;
            }
            String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
            return "true".equals(text) || "1".equals(text) || "yes".equals(text);
        }

        static Map<String, Object> connectorParam() {
            return Map.of("connector", Map.of(
                    "type", "string",
                    "description", "可选：用哪个 Notion 连接器（填别名或名称）。只绑定了一个时可省略"));
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
    }

    // ===================== 读 =====================

    /** 搜索页面/数据库（**标题**匹配，不搜正文）。 */
    static final class SearchTool extends AbstractNotionTool {

        SearchTool(long agentId, ConnectorService connectorService) {
            super(agentId, connectorService);
        }

        @Override
        public String getName() {
            return "notion_search";
        }

        @Override
        public String getDescription() {
            return "在用户的 Notion 里搜索**页面和数据库**（按标题/名称匹配；Notion 搜索不覆盖正文内容）。"
                    + "返回 id、类型、标题、最近编辑时间、链接。拿到 id 之后用 notion_get_page 读正文、"
                    + "或用 notion_query_data_source 查数据库。";
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> properties = new LinkedHashMap<>(connectorParam());
            properties.put("query", Map.of("type", "string", "description", "搜索关键词（标题里的词）；留空=列出最近的"));
            properties.put("objectType", Map.of("type", "string",
                    "description", "可选：page / data_source，只搜某一类"));
            properties.put("limit", Map.of("type", "integer", "description", "可选：最多返回几条（默认 10，最大 50）"));
            return schema(properties, List.of());
        }

        @Override
        protected String run(NotionCtx ctx, ToolCallParam param) {
            int limit = Math.min(Math.max(asInt(param.getInput().get("limit"), 10), 1), 50);
            JsonNode result = ctx.client().search(asString(param.getInput().get("query")),
                    asString(param.getInput().get("objectType")), limit, null);
            JsonNode results = result.path("results");
            if (results.isEmpty()) {
                return "没搜到匹配的页面或数据库。可以换个关键词（Notion 只按标题搜索，不搜正文）。";
            }
            StringBuilder out = new StringBuilder("搜到 ").append(results.size()).append(" 项：\n");
            int index = 1;
            for (JsonNode item : results) {
                String id = NotionSettings.NotionIds.normalizeId(item.path("id").asText(""));
                String type = item.path("object").asText("");
                String title = titleOf(item);
                out.append(index++).append(". [").append("page".equals(type) ? "页面" : "数据库").append("] ")
                        .append(title)
                        .append(" | id=").append(id)
                        .append(" | 编辑于 ").append(item.path("last_edited_time").asText(""))
                        .append('\n');
            }
            out.append("读正文用 notion_get_page(pageId=...)；查数据库用 notion_query_data_source(dataSourceId=...)");
            return out.toString();
        }

        private static String titleOf(JsonNode item) {
            JsonNode title = item.path("title");
            if (title.isArray() && !title.isEmpty()) {
                return NotionClient.plainText(title);
            }
            // data source 的标题在 name/title 属性里
            String name = item.path("name").asText("");
            if (!name.isBlank()) {
                return name;
            }
            JsonNode properties = item.path("properties");
            if (properties.isObject()) {
                for (var entry : iterable(properties.fields())) {
                    JsonNode value = entry.getValue();
                    String type = value.path("type").asText("");
                    if ("title".equals(type)) {
                        return NotionClient.plainText(value.path("title"));
                    }
                }
            }
            return "（无标题）";
        }

        private static Iterable<Map.Entry<String, JsonNode>> iterable(
                java.util.Iterator<Map.Entry<String, JsonNode>> iterator) {
            List<Map.Entry<String, JsonNode>> list = new ArrayList<>();
            iterator.forEachRemaining(list::add);
            return list;
        }
    }

    /** 读页面（元信息 + 正文 Markdown）。 */
    static final class GetPageTool extends AbstractNotionTool {

        GetPageTool(long agentId, ConnectorService connectorService) {
            super(agentId, connectorService);
        }

        @Override
        public String getName() {
            return "notion_get_page";
        }

        @Override
        public String getDescription() {
            return "读取一个 Notion 页面：标题、属性、以及正文（转成 Markdown，最多读连接器配置的块数上限，"
                    + "超出会明确提示被截断）。pageId 可以直接粘页面链接。";
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> properties = new LinkedHashMap<>(connectorParam());
            properties.put("pageId", Map.of("type", "string", "description", "页面 id 或完整链接"));
            properties.put("maxBlocks", Map.of("type", "integer",
                    "description", "可选：最多读多少个块（默认用连接器配置，通常 200）"));
            return schema(properties, List.of("pageId"));
        }

        @Override
        protected String run(NotionCtx ctx, ToolCallParam param) {
            String pageId = requireId(param.getInput().get("pageId"), "pageId");
            if (!ctx.settings().allowsPage(pageId)) {
                return "这个页面不在连接器的白名单里（id=" + pageId + "）。"
                        + "请让用户到「能力 → 连接器」里把它加进白名单，或确认链接是否贴错。";
            }
            JsonNode page = ctx.client().retrievePage(pageId);
            int limit = asInt(param.getInput().get("maxBlocks"), ctx.settings().maxBlocksPerPage());
            List<JsonNode> blocks = ctx.client().allChildren(pageId, limit);
            StringBuilder out = new StringBuilder();
            out.append("标题：").append(NotionClient.plainText(page.path("properties").path("title").path("title")));
            if (out.toString().endsWith("：")) {
                out.setLength(0);
                out.append("标题：（无标题）");
            }
            out.append('\n');
            out.append("最后编辑：").append(page.path("last_edited_time").asText("")).append('\n');
            out.append("链接：").append(page.path("url").asText("")).append("\n\n");
            Map<String, String> properties = NotionClient.propertySummary(page.path("properties"));
            properties.entrySet().stream()
                    .filter(entry -> !"title".equals(entry.getValue()))
                    .forEach(entry -> out.append("- 属性 ").append(entry.getKey())
                            .append("（").append(entry.getValue()).append("）\n"));
            out.append("\n--- 正文（Markdown）---\n");
            out.append(NotionMarkdown.toMarkdown(blocks));
            if (blocks.size() >= limit) {
                out.append("\n\n…（已读取到上限 ").append(limit)
                        .append(" 个块，后面还有内容；需要的话可以让用户直接在 Notion 里看，或调大连接器的上限）");
            }
            return out.toString();
        }
    }

    /** 列出可查询的数据库（data source）。 */
    static final class ListDataSourcesTool extends AbstractNotionTool {

        ListDataSourcesTool(long agentId, ConnectorService connectorService) {
            super(agentId, connectorService);
        }

        @Override
        public String getName() {
            return "notion_list_data_sources";
        }

        @Override
        public String getDescription() {
            return "列出用户能访问的 Notion 数据库（data source）及其属性结构，用来决定查哪个库、按什么字段过滤。";
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> properties = new LinkedHashMap<>(connectorParam());
            properties.put("query", Map.of("type", "string", "description", "可选：按名称过滤"));
            return schema(properties, List.of());
        }

        @Override
        protected String run(NotionCtx ctx, ToolCallParam param) {
            JsonNode result = ctx.client().search(asString(param.getInput().get("query")), "data_source", 25, null);
            JsonNode results = result.path("results");
            if (results.isEmpty()) {
                return "没有找到可访问的数据库。提醒：访问不到多半是没有授权——"
                        + "如果是内部集成，需要在数据库页面「…」→「添加连接」里选中它。";
            }
            StringBuilder out = new StringBuilder("可访问的数据库：\n");
            for (JsonNode item : results) {
                String id = NotionSettings.NotionIds.normalizeId(item.path("id").asText(""));
                String title = item.path("name").asText(item.path("title").asText("（无标题）"));
                out.append("- ").append(title).append(" | dataSourceId=").append(id).append('\n');
                Map<String, String> properties = NotionClient.propertySummary(item.path("properties"));
                if (!properties.isEmpty()) {
                    out.append("  属性：");
                    out.append(String.join("、", properties.entrySet().stream()
                            .map(entry -> entry.getKey() + "(" + entry.getValue() + ")").toList()));
                    out.append('\n');
                }
            }
            return out.toString();
        }
    }

    /** 查询数据库。 */
    static final class QueryDataSourceTool extends AbstractNotionTool {

        QueryDataSourceTool(long agentId, ConnectorService connectorService) {
            super(agentId, connectorService);
        }

        @Override
        public String getName() {
            return "notion_query_data_source";
        }

        @Override
        public String getDescription() {
            return "查询一个 Notion 数据库（data source）里的条目，返回**表格**（每行一条）。"
                    + "filterJson 用 Notion 的过滤语法原样传（例如 {\"property\":\"状态\",\"select\":{\"equals\":\"进行中\"}}）；"
                    + "sortsJson 同理。不确定字段名时先用 notion_list_data_sources 看属性结构。";
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> properties = new LinkedHashMap<>(connectorParam());
            properties.put("dataSourceId", Map.of("type", "string", "description", "数据库 data source id 或链接"));
            properties.put("filterJson", Map.of("type", "string", "description", "可选：Notion filter 对象（JSON 字符串）"));
            properties.put("sortsJson", Map.of("type", "string", "description", "可选：Notion sorts 数组（JSON 字符串）"));
            properties.put("pageSize", Map.of("type", "integer", "description", "可选：最多返回几条（默认 20，最大 100）"));
            return schema(properties, List.of("dataSourceId"));
        }

        @Override
        protected String run(NotionCtx ctx, ToolCallParam param) {
            String dataSourceId = requireId(param.getInput().get("dataSourceId"), "dataSourceId");
            if (!ctx.settings().allowsDataSource(dataSourceId)) {
                return "这个数据库不在连接器的白名单里（id=" + dataSourceId + "）。"
                        + "请让用户到「能力 → 连接器」里把它加进白名单。";
            }
            ObjectNode body = JSON.createObjectNode();
            body.put("page_size", Math.min(Math.max(asInt(param.getInput().get("pageSize"), 20), 1), 100));
            putJson(body, "filter", asString(param.getInput().get("filterJson")));
            putJson(body, "sorts", asString(param.getInput().get("sortsJson")));

            JsonNode result = ctx.client().queryDataSource(dataSourceId, body);
            JsonNode results = result.path("results");
            if (results.isEmpty()) {
                return "这个数据库没有符合条件的条目（或者一条都没有）。";
            }
            StringBuilder out = new StringBuilder("共 ").append(results.size()).append(" 条：\n");
            // 表头用第一条的属性名
            Map<String, String> headers = new LinkedHashMap<>();
            List<Map<String, String>> rows = new ArrayList<>();
            for (JsonNode row : results) {
                Map<String, String> values = new LinkedHashMap<>();
                row.path("properties").fields().forEachRemaining(entry -> {
                    headers.putIfAbsent(entry.getKey(), entry.getKey());
                    values.put(entry.getKey(), propertyValue(entry.getValue()));
                });
                rows.add(values);
            }
            out.append("| ").append(String.join(" | ", headers.keySet())).append(" |\n");
            out.append("|").append("---|".repeat(headers.size())).append("\n");
            for (Map<String, String> row : rows) {
                out.append("| ");
                out.append(String.join(" | ", headers.keySet().stream()
                        .map(key -> row.getOrDefault(key, "")).toList()));
                out.append(" |\n");
            }
            return out.toString();
        }

        private static void putJson(ObjectNode body, String field, String raw) {
            if (raw == null || raw.isBlank()) {
                return;
            }
            try {
                body.set(field, JSON.readTree(raw));
            } catch (Exception ex) {
                throw new IllegalArgumentException(field + " 不是合法 JSON：" + ex.getMessage());
            }
        }

        /** 属性值 → 便于阅读的文本。 */
        static String propertyValue(JsonNode property) {
            String type = property.path("type").asText("");
            JsonNode value = property.path(type);
            return switch (type) {
                case "title", "rich_text" -> NotionClient.plainText(value);
                case "select" -> value.path("name").asText("");
                case "status" -> value.path("name").asText("");
                case "multi_select" -> String.join(", ", toTextList(value, "name"));
                case "people" -> String.join(", ", toTextList(value, "name"));
                case "date" -> value.path("start").asText("");
                case "checkbox" -> value.asBoolean(false) ? "是" : "否";
                case "number" -> value.asText("");
                case "url", "email", "phone_number" -> value.asText("");
                case "formula" -> value.toString();
                case "relation" -> value.size() + " 个关联";
                case "files" -> value.size() + " 个文件";
                default -> value.isValueNode() ? value.asText("") : value.toString();
            };
        }

        private static List<String> toTextList(JsonNode array, String field) {
            List<String> list = new ArrayList<>();
            if (array != null && array.isArray()) {
                array.forEach(item -> list.add(item.path(field).asText(item.asText(""))));
            }
            return list;
        }
    }

    // ===================== 写 =====================

    /** 新建页面（正文用 Markdown）。 */
    static final class CreatePageTool extends AbstractNotionTool {

        CreatePageTool(long agentId, ConnectorService connectorService) {
            super(agentId, connectorService);
        }

        @Override
        public String getName() {
            return "notion_create_page";
        }

        @Override
        public String getDescription() {
            return "在指定页面/数据库下**新建一个 Notion 页面**（会真实写入用户的工作区，无法撤销，除非用户手动删）。"
                    + "正文用 Markdown（支持标题、列表、待办、引用、代码块、分割线）。"
                    + "parentId 不填时用连接器配置的默认页面；parentType=data_source 表示在数据库里建条目。";
        }

        @Override
        public boolean isReadOnly() {
            return false;
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> properties = new LinkedHashMap<>(connectorParam());
            properties.put("title", Map.of("type", "string", "description", "页面标题"));
            properties.put("markdown", Map.of("type", "string", "description", "可选：正文（Markdown）"));
            properties.put("parentId", Map.of("type", "string", "description", "可选：父页面/数据库 id 或链接"));
            properties.put("parentType", Map.of("type", "string", "description", "可选：page（默认）/ data_source"));
            return schema(properties, List.of("title"));
        }

        @Override
        protected String run(NotionCtx ctx, ToolCallParam param) {
            boolean isDataSource = "data_source".equalsIgnoreCase(
                    asString(param.getInput().get("parentType")));
            String rawParent = asString(param.getInput().get("parentId"));
            String parentId = rawParent == null || rawParent.isBlank()
                    ? ctx.settings().defaultPageId()
                    : requireId(rawParent, "parentId");
            if (parentId == null || parentId.isBlank()) {
                return "没有指定父页面：请在参数 parentId 里给一个页面/数据库，"
                        + "或让用户到「能力 → 连接器」里配置「默认写入页面」。";
            }
            String rejection = writeGuard(ctx.settings(), parentId, isDataSource);
            if (rejection != null) {
                return rejection;
            }
            ArrayNode children = NotionMarkdown.toBlocks(asString(param.getInput().get("markdown")));
            JsonNode created = ctx.client().createPage(parentId, isDataSource,
                    asString(param.getInput().get("title")), children);
            connectorService.logAction(ctx.connector().record().id(),
                    ctx.connector().record().ownerUserId(), "CREATE_PAGE",
                    asString(param.getInput().get("title")), "parent=" + parentId, true, null);
            return "已创建页面「" + asString(param.getInput().get("title")) + "」\n链接："
                    + created.path("url").asText("") + "\nid："
                    + NotionSettings.NotionIds.normalizeId(created.path("id").asText(""));
        }
    }

    /** 往页面末尾追加 Markdown 片段。 */
    static final class AppendMarkdownTool extends AbstractNotionTool {

        AppendMarkdownTool(long agentId, ConnectorService connectorService) {
            super(agentId, connectorService);
        }

        @Override
        public String getName() {
            return "notion_append_markdown";
        }

        @Override
        public String getDescription() {
            return "在已有页面**末尾追加内容**（Markdown）。适合“把这份总结加到上周那页后面”。"
                    + "注意：是追加，不会删改原有内容；要改已有内容用 notion_update_page。";
        }

        @Override
        public boolean isReadOnly() {
            return false;
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> properties = new LinkedHashMap<>(connectorParam());
            properties.put("pageId", Map.of("type", "string", "description", "页面 id 或链接"));
            properties.put("markdown", Map.of("type", "string", "description", "要追加的 Markdown 内容"));
            return schema(properties, List.of("pageId", "markdown"));
        }

        @Override
        protected String run(NotionCtx ctx, ToolCallParam param) {
            String pageId = requireId(param.getInput().get("pageId"), "pageId");
            String rejection = writeGuard(ctx.settings(), pageId, false);
            if (rejection != null) {
                return rejection;
            }
            String markdown = asString(param.getInput().get("markdown"));
            if (markdown == null || markdown.isBlank()) {
                return "没有要追加的内容（markdown 为空）。";
            }
            ArrayNode children = NotionMarkdown.toBlocks(markdown);
            ctx.client().appendChildren(pageId, children);
            connectorService.logAction(ctx.connector().record().id(),
                    ctx.connector().record().ownerUserId(), "APPEND", pageId,
                    children.size() + " 块", true, null);
            return "已往页面追加 " + children.size() + " 个块（pageId=" + pageId + "）。";
        }
    }

    /** 修改页面：标题 / 归档 / 简单属性。 */
    static final class UpdatePageTool extends AbstractNotionTool {

        UpdatePageTool(long agentId, ConnectorService connectorService) {
            super(agentId, connectorService);
        }

        @Override
        public String getName() {
            return "notion_update_page";
        }

        @Override
        public String getDescription() {
            return "修改页面：改标题（title）、归档/恢复（archived）、或设置简单属性"
                    + "（propertiesJson 里按 Notion 语法给，例如 {\"状态\":{\"select\":{\"name\":\"完成\"}}}）。"
                    + "要改**正文内容**请用 notion_append_markdown 追加，或告知用户去 Notion 里编辑。";
        }

        @Override
        public boolean isReadOnly() {
            return false;
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> properties = new LinkedHashMap<>(connectorParam());
            properties.put("pageId", Map.of("type", "string", "description", "页面 id 或链接"));
            properties.put("title", Map.of("type", "string", "description", "可选：新标题"));
            properties.put("archived", Map.of("type", "boolean",
                    "description", "可选：true=移入回收站（删除），false=恢复（内部按新版 API 用 in_trash 提交）"));
            properties.put("propertiesJson", Map.of("type", "string", "description", "可选：属性对象（JSON 字符串）"));
            return schema(properties, List.of("pageId"));
        }

        @Override
        protected String run(NotionCtx ctx, ToolCallParam param) {
            String pageId = requireId(param.getInput().get("pageId"), "pageId");
            String rejection = writeGuard(ctx.settings(), pageId, false);
            if (rejection != null) {
                return rejection;
            }
            ObjectNode body = JSON.createObjectNode();
            String title = asString(param.getInput().get("title"));
            if (title != null && !title.isBlank()) {
                ObjectNode properties = body.putObject("properties");
                ArrayNode titleArray = properties.putObject("title").putArray("title");
                titleArray.addObject().putObject("text").put("content", title);
            }
            // 坑（实测）：Notion API 2026-03-11 已不接受 archived 字段，改用 in_trash。
            // 对外仍保留 archived 参数名（模型习惯），这里翻译成 in_trash 发出去。
            Object archivedInput = param.getInput().get("archived") != null
                    ? param.getInput().get("archived")
                    : param.getInput().get("inTrash");
            if (archivedInput != null) {
                body.put("in_trash", asBool(archivedInput, false));
            }
            String propertiesJson = asString(param.getInput().get("propertiesJson"));
            if (propertiesJson != null && !propertiesJson.isBlank()) {
                try {
                    JsonNode extra = JSON.readTree(propertiesJson);
                    ObjectNode properties = body.has("properties")
                            ? (ObjectNode) body.get("properties")
                            : body.putObject("properties");
                    extra.fields().forEachRemaining(entry -> properties.set(entry.getKey(), entry.getValue()));
                } catch (Exception ex) {
                    throw new IllegalArgumentException("propertiesJson 不是合法 JSON：" + ex.getMessage());
                }
            }
            if (body.isEmpty()) {
                return "没有要改的内容：请给 title / archived / propertiesJson 之一。";
            }
            JsonNode updated = ctx.client().updatePage(pageId, body);
            connectorService.logAction(ctx.connector().record().id(),
                    ctx.connector().record().ownerUserId(), "UPDATE_PAGE", pageId, body.toString(), true, null);
            return "已更新页面：" + updated.path("url").asText("pageId=" + pageId);
        }
    }

    // ===================== schema 小工具 =====================

    static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }
}
