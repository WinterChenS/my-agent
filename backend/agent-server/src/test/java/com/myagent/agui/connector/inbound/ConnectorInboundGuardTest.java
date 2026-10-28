package com.myagent.agui.connector.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.myagent.agui.AgentFactory;
import com.myagent.agui.connector.ConnectorProvider;
import com.myagent.agui.connector.ConnectorToolkit;
import com.myagent.channelconfig.service.ChannelPushPort;
import com.myagent.chat.service.ChatSessionService;
import com.myagent.connectorconfig.ConnectorRecord;
import com.myagent.connectorconfig.ConnectorService;
import com.myagent.connectorconfig.ConnectorStore;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 入站护栏：写错一条就会给别人发垃圾邮件，所以每条规则都要有单测。
 *
 * <p>重点是「防自环 / 防互相轰炸」：邮件自动回复一旦成环，双方邮箱会被瞬间打爆。
 */
class ConnectorInboundGuardTest {

    private final ConnectorStore store = mock(ConnectorStore.class);
    private final ConnectorService connectorService = mock(ConnectorService.class);
    private final ConnectorToolkit toolkit = mock(ConnectorToolkit.class);
    private final AgentFactory agentFactory = mock(AgentFactory.class);
    private final ChatSessionService sessionService = mock(ChatSessionService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private ConnectorInboundService service;

    @BeforeEach
    void setUp() {
        service = new ConnectorInboundService(
                store, connectorService, toolkit, agentFactory, sessionService,
                new StaticListableBeanFactory().getBeanProvider(ChannelPushPort.class),
                jdbc, 10);
        when(connectorService.config(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new java.util.LinkedHashMap<>(Map.of("email", "me@company.com")));
        when(store.agentIdsOf(anyLong())).thenReturn(List.of(3L));
        when(store.countAutoRepliesForThread(anyLong(), anyString())).thenReturn(0);
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Integer.class), anyLong()))
                .thenReturn(0);
    }

    private ConnectorRecord record(String mode) {
        return new ConnectorRecord(1L, "MAIL", "工作邮箱", null, 9L, "enc", true, "OK", null, null,
                mode, null, 0L, OffsetDateTime.now(), OffsetDateTime.now());
    }

    private static ConnectorProvider.InboundMessage message(String from, boolean autoSubmitted, String precedence) {
        return new ConnectorProvider.InboundMessage(11L, 1L, "<msg@x>", "<thread@x>", from, "张三",
                "报价单", "正文内容", OffsetDateTime.now(), autoSubmitted, precedence,
                List.of("me@company.com"));
    }

    @Test
    void 自己发给自己的邮件不处理_否则会无限自环() {
        assertThat(service.skipReason(record("AUTOREPLY"), message("ME@company.com", false, null), "AUTOREPLY"))
                .contains("发件人是自己");
    }

    @Test
    void 对方是自动回复时不回() {
        assertThat(service.skipReason(record("AUTOREPLY"), message("a@b.com", true, null), "AUTOREPLY"))
                .contains("Auto-Submitted");
    }

    @Test
    void 批量或自动回复优先级的邮件不处理() {
        assertThat(service.skipReason(record("AUTOREPLY"), message("a@b.com", false, "bulk"), "AUTOREPLY"))
                .contains("Precedence");
        assertThat(service.skipReason(record("AUTOREPLY"), message("a@b.com", false, "auto_reply"), "AUTOREPLY"))
                .contains("Precedence");
    }

    @Test
    void 系统邮箱不处理() {
        for (String from : List.of("noreply@x.com", "no-reply@x.com", "mailer-daemon@x.com", "postmaster@x.com")) {
            assertThat(service.skipReason(record("AUTOREPLY"), message(from, false, null), "AUTOREPLY"))
                    .as(from)
                    .isNotNull();
        }
    }

    @Test
    void 同一线程已经自动回过就不再回() {
        when(store.countAutoRepliesForThread(1L, "<thread@x>")).thenReturn(1);

        assertThat(service.skipReason(record("AUTOREPLY"), message("a@b.com", false, null), "AUTOREPLY"))
                .contains("已经自动回复过");
    }

    @Test
    void 每小时上限到了就不回() {
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Integer.class), anyLong()))
                .thenReturn(10);

        assertThat(service.skipReason(record("AUTOREPLY"), message("a@b.com", false, null), "AUTOREPLY"))
                .contains("自动回复已达上限");
    }

    @Test
    void 没绑定Agent时不自动回复() {
        when(store.agentIdsOf(anyLong())).thenReturn(List.of());

        assertThat(service.skipReason(record("AUTOREPLY"), message("a@b.com", false, null), "AUTOREPLY"))
                .contains("没有绑定任何 Agent");
    }

    @Test
    void 连接器停用时不自动回复() {
        ConnectorRecord disabled = new ConnectorRecord(1L, "MAIL", "工作邮箱", null, 9L, "enc", false, "OK",
                null, null, "AUTOREPLY", null, 0L, OffsetDateTime.now(), OffsetDateTime.now());

        assertThat(service.skipReason(disabled, message("a@b.com", false, null), "AUTOREPLY"))
                .contains("已停用");
    }

    @Test
    void 通知模式不做自动回复类限制() {
        assertThat(service.skipReason(record("NOTIFY"), message("a@b.com", false, null), "NOTIFY")).isNull();
    }

    @Test
    void 没有发件人的邮件跳过() {
        ConnectorProvider.InboundMessage noFrom = new ConnectorProvider.InboundMessage(
                12L, 1L, "<m@x>", "<t@x>", "", "", "主题", "正文", OffsetDateTime.now(), false, null,
                List.of("me@company.com"));
        assertThat(service.skipReason(record("NOTIFY"), noFrom, "NOTIFY")).contains("没有发件人");
    }
}
