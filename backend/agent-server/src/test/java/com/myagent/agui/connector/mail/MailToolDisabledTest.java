package com.myagent.agui.connector.mail;

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
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 连接器**停用**后模型该说什么。
 *
 * <p>用户实测：停用后让 Agent 看邮件，它回「我没有这个功能」——很误导。
 * 现在工具照常注册，工具自己回答「这个邮箱连接器已停用，去聊天框下方的连接器按钮里勾上启用」。
 */
class MailToolDisabledTest {

    private static ConnectorRecord record(boolean enabled) {
        return new ConnectorRecord(1L, "MAIL", "QQ邮箱", "qq", 9L, "enc", enabled,
                "OK", null, null, "OFF", null, 0L, OffsetDateTime.now(), OffsetDateTime.now());
    }

    private static ToolCallParam param(Map<String, Object> input) {
        return ToolCallParam.builder()
                .input(input)
                .runtimeContext(RuntimeContext.builder().userId("9").sessionId("s1").build())
                .build();
    }

    private static final Map<String, String> CONFIG = Map.of(
            "email", "me@qq.com",
            "imapHost", "127.0.0.1",
            "imapPort", "1",
            "imapSsl", "false",
            "smtpHost", "127.0.0.1",
            "smtpPort", "1",
            "smtpSsl", "false",
            "username", "me@qq.com",
            "password", "x");

    private static String call(ConnectorRecord connector, Map<String, Object> input) {
        ConnectorService service = mock(ConnectorService.class);
        when(service.resolveForUser(anyLong(), anyLong())).thenReturn(List.of(
                new ConnectorService.ResolvedConnector(connector, CONFIG)));
        List<AgentTool> tools = MailTools.create(37L, service, Path.of("."), 1000, 1000, 1024, 1024, 1000);
        AgentTool list = tools.stream().filter(tool -> tool.getName().equals("mail_list")).findFirst().orElseThrow();
        ToolResultBlock result = list.callAsync(param(input)).block();
        assertThat(result).isNotNull();
        return String.valueOf(result.getOutput());
    }

    @Test
    void 停用的连接器_工具给出可操作提示而不是连不上() {
        String output = call(record(false), Map.of());

        assertThat(output).contains("停用").contains("连接器").contains("启用");
        assertThat(output).doesNotContain("IMAP");
    }

    @Test
    void 启用的连接器仍会真的去连邮箱() {
        // 配置里是假地址，所以必然连接失败；重点是**不是**"停用"那条提示
        String output = call(record(true), Map.of("connector", "qq"));

        // 假地址必然连不上，但错误是"连接失败"而不是"停用"
        assertThat(output).doesNotContain("停用");
        assertThat(output).contains("IMAP");
    }
}
