package com.myagent.agui.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.myagent.connectorconfig.ConnectorService;
import com.myagent.connectorconfig.ConnectorTypeRegistry;
import com.myagent.connectorconfig.dto.ConnectorTestResult;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * 连接器工具注册：**只有绑定了该类型连接器的 Agent 才有工具**（否则所有 Agent 都会看到 mail_*，
 * 但点进去永远是"你没有邮箱"，非常迷惑）。
 */
class ConnectorToolkitTest {

    private static AgentTool fakeTool(String name) {
        return new AgentTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return "fake";
            }

            @Override
            public Map<String, Object> getParameters() {
                return Map.of("type", "object", "properties", Map.of());
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.empty();
            }
        };
    }

    private static ConnectorProvider provider(String type, List<String> toolNames) {
        return new ConnectorProvider() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public List<AgentTool> tools(long agentId, com.myagent.connectorconfig.ConnectorTypeDescriptor descriptor) {
                return toolNames.stream().map(ConnectorToolkitTest::fakeTool).toList();
            }

            @Override
            public ConnectorTestResult probe(Map<String, String> config) {
                return new ConnectorTestResult(true, "ok", List.of());
            }
        };
    }

    @Test
    void 没有绑定连接器时不注册任何工具() {
        ConnectorService service = mock(ConnectorService.class);
        when(service.typesBoundToAgent(7L)).thenReturn(List.of());
        ConnectorToolkit toolkit = new ConnectorToolkit(service, List.of(provider("MAIL", List.of("mail_list"))));

        Toolkit target = new Toolkit();
        List<String> registered = toolkit.register(target, 7L, new ConnectorTypeRegistry());

        assertThat(registered).isEmpty();
        assertThat(target.getToolNames()).isEmpty();
    }

    @Test
    void 绑定了邮件连接器就注册邮件工具() {
        ConnectorService service = mock(ConnectorService.class);
        when(service.typesBoundToAgent(7L)).thenReturn(List.of("MAIL"));
        ConnectorToolkit toolkit = new ConnectorToolkit(service,
                List.of(provider("MAIL", List.of("mail_list", "mail_send"))));

        Toolkit target = new Toolkit();
        List<String> registered = toolkit.register(target, 7L, new ConnectorTypeRegistry());

        assertThat(registered).containsExactly("mail_list", "mail_send");
        assertThat(target.getToolNames()).contains("mail_list", "mail_send");
    }

    @Test
    void 类型没有Provider实现时跳过_不影响其它工具() {
        ConnectorService service = mock(ConnectorService.class);
        when(service.typesBoundToAgent(7L)).thenReturn(List.of("CALENDAR", "MAIL"));
        ConnectorToolkit toolkit = new ConnectorToolkit(service, List.of(provider("MAIL", List.of("mail_list"))));

        Toolkit target = new Toolkit();
        List<String> registered = toolkit.register(target, 7L, new ConnectorTypeRegistry());

        assertThat(registered).containsExactly("mail_list");
    }

    @Test
    void 探测走对应类型的Provider() {
        ConnectorService service = mock(ConnectorService.class);
        ConnectorToolkit toolkit = new ConnectorToolkit(service, List.of(provider("MAIL", List.of())));

        ConnectorTestResult result = toolkit.probe("MAIL", Map.of("email", "a@b.com"));

        assertThat(result.ok()).isTrue();
        assertThat(toolkit.providerFor("mail")).isNotNull();
        assertThat(toolkit.providerFor("SLACK")).isNull();
    }
}
