package com.myagent.agui.connector.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.myagent.agui.AgentFactory;
import com.myagent.agui.connector.ConnectorProvider;
import com.myagent.agui.connector.ConnectorToolkit;
import com.myagent.channelconfig.service.ChannelPushPort;
import com.myagent.chat.service.ChatSessionService;
import com.myagent.connectorconfig.ConnectorRecord;
import com.myagent.connectorconfig.ConnectorService;
import com.myagent.connectorconfig.ConnectorStore;
import com.myagent.connectorconfig.dto.ConnectorTestResult;
import io.agentscope.core.tool.AgentTool;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 入站处理的**流水线**：去重 → 推送 → 记账 → 推进游标。
 *
 * <p>护栏规则由 {@link ConnectorInboundGuardTest} 覆盖；这里验证"该做的事真的做了"：
 * 同一封邮件不会推两次、NOTIFY 会推给正确的 IM 接收人、游标一定要往前走
 * （不推进就会每轮重复拉同一批，越积越多）。
 */
class ConnectorInboundFlowTest {

    private final ConnectorStore store = mock(ConnectorStore.class);
    private final ConnectorService connectorService = mock(ConnectorService.class);
    private final ConnectorToolkit toolkit = mock(ConnectorToolkit.class);
    private final AgentFactory agentFactory = mock(AgentFactory.class);
    private final ChatSessionService sessionService = mock(ChatSessionService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ChannelPushPort pushPort = mock(ChannelPushPort.class);
    private ConnectorInboundService service;

    @BeforeEach
    void setUp() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory(
                Map.of("pushPort", pushPort));
        service = new ConnectorInboundService(
                store, connectorService, toolkit, agentFactory, sessionService,
                beans.getBeanProvider(ChannelPushPort.class), jdbc, 10, 3);

        when(connectorService.config(any()))
                .thenReturn(new java.util.LinkedHashMap<>(Map.of("email", "me@company.com")));
        when(toolkit.providerFor(anyString())).thenReturn(provider());
        when(store.agentIdsOf(anyLong())).thenReturn(List.of(3L));
        when(store.inboundId(anyLong(), anyString())).thenReturn(Optional.of(77L));
        when(store.recordInbound(anyLong(), anyString(), any(), any(), any(), any())).thenReturn(true);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(
                List.of(Map.of("app_id", 5L, "peer_key", "staff-1")));
    }

    private static ConnectorProvider provider() {
        return new ConnectorProvider() {
            @Override
            public String type() {
                return "MAIL";
            }

            @Override
            public List<AgentTool> tools(long agentId, com.myagent.connectorconfig.ConnectorTypeDescriptor descriptor) {
                return List.of();
            }

            @Override
            public ConnectorTestResult probe(Map<String, String> config) {
                return new ConnectorTestResult(true, "ok", List.of());
            }
        };
    }

    private ConnectorRecord record(String mode, long lastUid) {
        return new ConnectorRecord(1L, "MAIL", "工作邮箱", "work", 9L, "enc", true, "OK", null, null,
                mode, 11L, lastUid, OffsetDateTime.now(), OffsetDateTime.now());
    }

    private static ConnectorProvider.InboundMessage message(long uid) {
        return new ConnectorProvider.InboundMessage(uid, 11L, "<m" + uid + "@x>", "<t@x>", "boss@partner.com",
                "老板", "报价单", "请查收附件", OffsetDateTime.now(), false, null,
                List.of("me@company.com"));
    }

    @Test
    void NOTIFY模式_推送到该用户的IM并记成功() {
        int handled = service.handle(record("NOTIFY", 0L), List.of(message(21L)));

        assertThat(handled).isEqualTo(1);
        verify(pushPort).push(eq(5L), eq("staff-1"), org.mockito.ArgumentMatchers.contains("报价单"),
                eq(List.of()));
        verify(store).finishInbound(eq(77L), eq("HANDLED"), any(), eq(null), eq(null));
    }

    @Test
    void 同一封邮件重复拉到不会处理第二次() {
        when(store.recordInbound(anyLong(), anyString(), any(), any(), any(), any())).thenReturn(false);

        int handled = service.handle(record("NOTIFY", 21L), List.of(message(21L)));

        assertThat(handled).isZero();
        verify(pushPort, never()).push(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void OFF模式只记录不推送() {
        int handled = service.handle(record("OFF", 0L), List.of(message(22L)));

        assertThat(handled).isZero();
        verify(pushPort, never()).push(anyLong(), anyString(), anyString(), any());
        verify(store).finishInbound(eq(77L), eq("SKIPPED"), any(), eq(null), eq(null));
    }

    @Test
    void 用户没有IM接收人时只记录不报错() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        int handled = service.handle(record("NOTIFY", 0L), List.of(message(23L)));

        assertThat(handled).isEqualTo(1);
        verify(pushPort, never()).push(anyLong(), anyString(), anyString(), any());
        verify(store).finishInbound(eq(77L), eq("HANDLED"), any(), eq(null), eq(null));
    }

    @Test
    void 推送失败不影响游标推进() {
        when(pushPort.push(anyLong(), anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("渠道未就绪"));

        int handled = service.handle(record("NOTIFY", 0L), List.of(message(24L)));

        assertThat(handled).isEqualTo(1);
        verify(store, never()).finishInbound(eq(77L), eq("FAILED"), any(), any(), any());
    }

    @Test
    void 没有Provider实现时整批跳过() {
        when(toolkit.providerFor(anyString())).thenReturn(null);

        int handled = service.handle(record("NOTIFY", 0L), List.of(message(25L)));

        assertThat(handled).isZero();
        verify(store, never()).finishInbound(anyLong(), anyString(), any(), any(), any());
    }
}
