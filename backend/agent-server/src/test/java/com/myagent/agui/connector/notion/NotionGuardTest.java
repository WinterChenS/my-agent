package com.myagent.agui.connector.notion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.myagent.connectorconfig.ConnectorRecord;
import com.myagent.connectorconfig.ConnectorService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Notion 的错误人话化 + 写操作的两道闸。
 *
 * <p>为什么这两块必须单测：Notion 的错误信息对用户很不友好（"object_not_found"），
 * 而写操作是**不可撤销**的副作用。
 */
class NotionGuardTest {

    // ===================== 错误映射 =====================

    @Test
    void 令牌无效要说清去哪重新生成() {
        assertThat(NotionClient.toHumanError(401,
                "{\"object\":\"error\",\"code\":\"unauthorized\",\"message\":\"API token is invalid.\"}")
                .getMessage())
                .contains("令牌无效").contains("重新生成");
    }

    @Test
    void 没有权限要提示添加连接() {
        assertThat(NotionClient.toHumanError(403,
                "{\"code\":\"restricted_resource\",\"message\":\"no access\"}").getMessage())
                .contains("添加连接");
    }

    @Test
    void 找不到对象要提示id或授权() {
        assertThat(NotionClient.toHumanError(404,
                "{\"code\":\"object_not_found\",\"message\":\"not found\"}").getMessage())
                .contains("找不到这个页面").contains("id");
    }

    @Test
    void 限流有单独提示() {
        assertThat(NotionClient.toHumanError(429, "{\"code\":\"rate_limited\"}").getMessage())
                .contains("限流");
    }

    @Test
    void 校验错误原样带出message并附requestId() {
        String message = NotionClient.toHumanError(400,
                "{\"code\":\"validation_error\",\"message\":\"title is required\",\"request_id\":\"abc-123\"}")
                .getMessage();
        assertThat(message).contains("title is required").contains("abc-123");
    }

    // ===================== 写操作闸门 =====================

    private static ConnectorRecord record(Map<String, String> config) {
        return new ConnectorRecord(1L, "NOTION", "我的 Notion", "nt", 9L, "enc", true, "OK", null, null,
                "OFF", null, 0L, OffsetDateTime.now(), OffsetDateTime.now());
    }

    private static ToolCallParam param(Map<String, Object> input) {
        return ToolCallParam.builder()
                .input(input)
                .runtimeContext(RuntimeContext.builder().userId("9").sessionId("s1").build())
                .build();
    }

    private static String callCreatePage(Map<String, String> config, Map<String, Object> input) {
        ConnectorService service = mock(ConnectorService.class);
        when(service.resolveForUser(anyLong(), anyLong())).thenReturn(List.of(
                new ConnectorService.ResolvedConnector(record(config), config)));
        List<AgentTool> tools = NotionTools.create(37L, service);
        AgentTool createPage = tools.stream()
                .filter(tool -> tool.getName().equals("notion_create_page"))
                .findFirst().orElseThrow();
        ToolResultBlock result = createPage.callAsync(param(input)).block();
        assertThat(result).isNotNull();
        return String.valueOf(result.getOutput());
    }

    private static Map<String, String> config() {
        return new java.util.LinkedHashMap<>(Map.of(
                "token", "ntn_test",
                "defaultPageId", "2f1b8c9d4e5f4a1b8c9d4e5f4a1b8c9d",
                "allowWrite", "false"));
    }

    @Test
    void 关闭允许写入时写工具直接拒绝且不发请求() {
        String output = callCreatePage(config(), Map.of("title", "测试页面"));

        assertThat(output).contains("关闭了「允许写入」");
        // 没有真的打网络（令牌是假的，一旦发请求会得到"令牌无效"）
        assertThat(output).doesNotContain("令牌无效");
    }

    @Test
    void 父页面不在白名单里要拒绝() {
        Map<String, String> config = config();
        config.put("allowWrite", "true");
        config.put("allowedPageIds", "11111111222233334444555566667777");

        String output = callCreatePage(config, Map.of("title", "测试页面"));

        assertThat(output).contains("不在连接器的白名单里");
    }

    @Test
    void 没有父页面也没配默认页面时给出可操作提示() {
        Map<String, String> config = config();
        config.put("allowWrite", "true");
        config.remove("defaultPageId");

        String output = callCreatePage(config, Map.of("title", "测试页面"));

        assertThat(output).contains("没有指定父页面").contains("默认写入页面");
    }

    @Test
    void 归档参数要用新版API的in_trash_不能发archived() {
        // 实测踩过：Notion 2026-03-11 起拒绝 body.archived，只接受 in_trash
        Map<String, String> config = config();
        config.put("allowWrite", "true");
        ConnectorService service = mock(ConnectorService.class);
        when(service.resolveForUser(anyLong(), anyLong())).thenReturn(List.of(
                new ConnectorService.ResolvedConnector(record(config), config)));
        AgentTool update = NotionTools.create(37L, service).stream()
                .filter(tool -> tool.getName().equals("notion_update_page"))
                .findFirst().orElseThrow();

        // 令牌是假的：能走到"发请求"这一步并报令牌错，说明 body 校验那一关（archived）已经过了
        String output = String.valueOf(update.callAsync(param(Map.of(
                "pageId", "4099906f-5f0e-8119-83ca-f6bd310b305e",
                "archived", true))).block().getOutput());
        assertThat(output).doesNotContain("archived");
        assertThat(output).contains("Notion");
    }

    @Test
    void 写工具都标成非只读_读工具标只读() {
        ConnectorService service = mock(ConnectorService.class);
        Map<String, Boolean> readOnly = new java.util.LinkedHashMap<>();
        NotionTools.create(37L, service).forEach(tool -> readOnly.put(tool.getName(), tool.isReadOnly()));

        assertThat(readOnly)
                .containsEntry("notion_search", true)
                .containsEntry("notion_get_page", true)
                .containsEntry("notion_list_data_sources", true)
                .containsEntry("notion_query_data_source", true)
                .containsEntry("notion_create_page", false)
                .containsEntry("notion_append_markdown", false)
                .containsEntry("notion_update_page", false);
    }
}
