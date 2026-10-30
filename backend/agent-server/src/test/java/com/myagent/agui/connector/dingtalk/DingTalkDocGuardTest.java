package com.myagent.agui.connector.dingtalk;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 钉钉文档：错误人话化 + 写操作闸门 + 身份解析。
 *
 * <p>钉钉的错误码对用户毫无意义（`InvalidAuthentication`），而 operatorId 拿不到时
 * 必须给出"怎么才能拿到"的可操作提示，否则用户会一直卡在"没权限"。
 */
class DingTalkDocGuardTest {

    // ===================== 错误映射 =====================

    @Test
    void 凭据无效指向管理员检查appKey() {
        assertThat(DingTalkDocClient.toHumanError(400,
                "{\"code\":\"InvalidAuthentication\",\"message\":\"不合法的access_token\"}").getMessage())
                .contains("appKey").contains("管理员");
    }

    @Test
    void 没权限要说清三件事() {
        String message = DingTalkDocClient.toHumanError(403,
                "{\"code\":\"Forbidden.AccessDenied\",\"message\":\"没有权限\"}").getMessage();
        assertThat(message).contains("勾选").contains("可见范围").contains("权限");
    }

    @Test
    void 限流与找不到对象各有提示() {
        assertThat(DingTalkDocClient.toHumanError(429, "{\"code\":\"Throttling\"}").getMessage())
                .contains("限流");
        assertThat(DingTalkDocClient.toHumanError(404, "{\"code\":\"NotFound\"}").getMessage())
                .contains("找不到");
    }

    @Test
    void 缺文档权限时把权限点和一键开通链接都给出来() {
        String body = "{\"code\":\"Forbidden.AccessDenied.AccessTokenPermissionDenied\","
                + "\"requestid\":\"01A18AC8-17DC\","
                + "\"message\":\"应用尚未开通所需的权限：[Document.WorkspaceDocument.Read]，"
                + "点击链接申请并开通即可：https://open-dev.dingtalk.com/appscope/apply?content=ding84%23Document.WorkspaceDocument.Read\"}";

        String message = DingTalkDocClient.toHumanError(403, body).getMessage();

        assertThat(message).contains("Document.WorkspaceDocument.Read");
        assertThat(message).contains("https://open-dev.dingtalk.com/appscope/apply?content=ding84");
        assertThat(message).doesNotContain("点击链接申请并开通即可");
    }

    @Test
    void 缺通讯录权限时把申请链接直接给用户() {
        String reason = "应用尚未开通所需的权限：[qyapi_get_member]，点击链接申请并开通即可："
                + "https://open-dev.dingtalk.com/appscope/apply?content=ding84org4u4zruvivxe%23qyapi_get_member, "
                + "{requiredScopes=[qyapi_get_member]}";

        String hint = DingTalkDocClient.unionIdHint(reason);

        assertThat(hint).contains("unionId").contains("通讯录");
        assertThat(hint).contains("https://open-dev.dingtalk.com/appscope/apply?content=ding84org4u4zruvivxe");
        // 链接不能把后面的 ", {requiredScopes" 也吞进去
        assertThat(hint).doesNotContain("requiredScopes");
    }

    @Test
    void 降级用的纯文本会去markdown标记() {
        String plain = DingTalkDocClient.stripMarkdown("# 标题\n- 条目\n**粗体**和`代码`");
        assertThat(plain).doesNotContain("#", "**", "`");
        assertThat(plain).contains("标题").contains("条目").contains("粗体");
    }

    // ===================== 身份解析 =====================

    @Test
    void 平台用户能映射到钉钉staffId() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(String.class), anyLong()))
                .thenReturn(List.of("staff-001"));

        assertThat(new DingTalkOperatorResolver(jdbc).resolve(9L)).isEqualTo("staff-001");
    }

    @Test
    void 没聊过机器人时映射为空() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(String.class), anyLong()))
                .thenReturn(List.of());

        assertThat(new DingTalkOperatorResolver(jdbc).resolve(9L)).isNull();
    }

    // ===================== 工具闸门 =====================

    private static ConnectorRecord record(Map<String, String> config) {
        return new ConnectorRecord(1L, "DINGTALK_DOC", "钉钉文档", "dd", 9L, "enc", true, "OK", null, null,
                "OFF", null, 0L, OffsetDateTime.now(), OffsetDateTime.now());
    }

    private static ToolCallParam param(Map<String, Object> input) {
        return ToolCallParam.builder()
                .input(input)
                .runtimeContext(RuntimeContext.builder().userId("9").sessionId("s1").build())
                .build();
    }

    private static String callCreateDoc(Map<String, String> config, Map<String, Object> input,
            DingTalkAppCredentials credentials, DingTalkOperatorResolver resolver) {
        ConnectorService service = mock(ConnectorService.class);
        when(service.resolveForUser(anyLong(), anyLong())).thenReturn(List.of(
                new ConnectorService.ResolvedConnector(record(config), config)));
        List<AgentTool> tools = DingTalkDocTools.create(37L, service, credentials, resolver);
        AgentTool create = tools.stream().filter(tool -> tool.getName().equals("dt_create_doc"))
                .findFirst().orElseThrow();
        ToolResultBlock result = create.callAsync(param(input)).block();
        assertThat(result).isNotNull();
        return String.valueOf(result.getOutput());
    }

    private static Map<String, String> config() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("operatorId", "staff-001");
        config.put("defaultWorkspaceId", "ws-1111");
        return config;
    }

    private static DingTalkAppCredentials credentialsWithApp() {
        DingTalkAppCredentials credentials = mock(DingTalkAppCredentials.class);
        when(credentials.resolve()).thenReturn(Map.of("appKey", "key", "appSecret", "secret"));
        return credentials;
    }

    @Test
    void 关闭允许写入时写工具直接拒绝() {
        Map<String, String> config = config();
        config.put("allowWrite", "false");

        String output = callCreateDoc(config, Map.of("title", "测试"),
                credentialsWithApp(), mock(DingTalkOperatorResolver.class));

        assertThat(output).contains("关闭了「允许写入」");
    }

    @Test
    void 知识库不在白名单里要拒绝() {
        Map<String, String> config = config();
        config.put("allowedWorkspaceIds", "ws-9999");

        String output = callCreateDoc(config, Map.of("title", "测试"),
                credentialsWithApp(), mock(DingTalkOperatorResolver.class));

        assertThat(output).contains("不在连接器的白名单里");
    }

    @Test
    void 拿不到钉钉身份时给出可操作提示() {
        Map<String, String> config = new LinkedHashMap<>();
        // 不填 operatorId，且 resolver 返回 null
        String output = callCreateDoc(config, Map.of("title", "测试"),
                credentialsWithApp(), mock(DingTalkOperatorResolver.class));

        assertThat(output).contains("拿不到你的钉钉身份")
                .contains("操作人")
                .contains("机器人");
    }

    @Test
    void 没有应用凭据时提示去配钉钉应用() {
        DingTalkAppCredentials noCreds = mock(DingTalkAppCredentials.class);
        when(noCreds.resolve()).thenReturn(null);

        String output = callCreateDoc(config(), Map.of("title", "测试"),
                noCreds, mock(DingTalkOperatorResolver.class));

        assertThat(output).contains("钉钉应用凭据").contains("IM 渠道");
    }

    @Test
    void 读工具只读_写工具非只读() {
        Map<String, Boolean> readOnly = new LinkedHashMap<>();
        DingTalkDocTools.create(37L, mock(ConnectorService.class),
                        credentialsWithApp(), mock(DingTalkOperatorResolver.class))
                .forEach(tool -> readOnly.put(tool.getName(), tool.isReadOnly()));

        assertThat(readOnly)
                .containsEntry("dt_list_workspaces", true)
                .containsEntry("dt_search_docs", true)
                .containsEntry("dt_recent_docs", true)
                .containsEntry("dt_get_doc", true)
                .containsEntry("dt_get_doc_by_url", true)
                .containsEntry("dt_create_doc", false)
                .containsEntry("dt_append_content", false)
                .containsEntry("dt_overwrite_content", false);
    }
}
