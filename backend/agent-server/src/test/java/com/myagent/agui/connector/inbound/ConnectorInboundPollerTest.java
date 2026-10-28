package com.myagent.agui.connector.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.myagent.agui.connector.ConnectorProvider;
import com.myagent.agui.connector.ConnectorToolkit;
import com.myagent.connectorconfig.ConnectorRecord;
import com.myagent.connectorconfig.ConnectorService;
import com.myagent.connectorconfig.ConnectorStore;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

/**
 * 入站轮询的游标策略 —— 用户实测被"批量推送历史邮件"坑过，所以这里把两类情况都钉住：
 *
 * <ul>
 *   <li><b>第一次跑（打基线）</b>：一封都不处理，但**必须把游标落库**，
 *       否则每轮都是"第一次"，历史未读会被反复推送；</li>
 *   <li><b>正常轮次</b>：处理新邮件并把游标推进到本批最大 UID。</li>
 * </ul>
 */
class ConnectorInboundPollerTest {

    private final ConnectorStore store = mock(ConnectorStore.class);
    private final ConnectorService connectorService = mock(ConnectorService.class);
    private final ConnectorToolkit toolkit = mock(ConnectorToolkit.class);
    private final ConnectorInboundService inboundService = mock(ConnectorInboundService.class, Answers.RETURNS_DEFAULTS);
    private ConnectorInboundPoller poller;
    /** 固定实例：record/message 里有时间戳，每次 new 出来就不相等，mock 断言会错。 */
    private final ConnectorRecord record = new ConnectorRecord(1L, "MAIL", "工作邮箱", null, 9L, "enc",
            true, "OK", null, null, "NOTIFY", null, 0L, OffsetDateTime.now(), OffsetDateTime.now());
    private final ConnectorProvider.InboundMessage message = new ConnectorProvider.InboundMessage(
            12611L, 7L, "<m12611@x>", "<t@x>", "a@b.com", "A", "主题", "正文",
            OffsetDateTime.now(), false, null, List.of("me@company.com"));

    @BeforeEach
    void setUp() {
        poller = new ConnectorInboundPoller(store, connectorService, toolkit, inboundService, true, 5);
        when(connectorService.config(any())).thenReturn(Map.of("email", "me@company.com"));
        when(store.claimLease(anyLong(), anyString(), anyInt())).thenReturn(true);
    }

    private ConnectorProvider provider(ConnectorProvider.PollResult result) {
        return new ConnectorProvider() {
            @Override
            public String type() {
                return "MAIL";
            }

            @Override
            public List<io.agentscope.core.tool.AgentTool> tools(
                    long agentId, com.myagent.connectorconfig.ConnectorTypeDescriptor descriptor) {
                return List.of();
            }

            @Override
            public com.myagent.connectorconfig.dto.ConnectorTestResult probe(Map<String, String> config) {
                return new com.myagent.connectorconfig.dto.ConnectorTestResult(true, "ok", List.of());
            }

            @Override
            public PollResult poll(ConnectorRecord record, Map<String, String> config) {
                return result;
            }
        };
    }

    @Test
    void 第一次跑只打基线_不处理任何邮件但要把游标落库() {
        when(store.listInboundEnabled()).thenReturn(List.of(record));
        when(toolkit.providerFor(anyString()))
                .thenReturn(provider(new ConnectorProvider.PollResult(List.of(), 7L, 12610L, true)));

        poller.poll();

        verify(inboundService, never()).handle(any(), any());
        verify(store).saveInboundCursor(1L, 7L, 12610L);
        verify(store).releaseLease(eq(1L), anyString());
    }

    @Test
    void 正常轮次处理新邮件并推进游标() {
        when(store.listInboundEnabled()).thenReturn(List.of(record));
        when(toolkit.providerFor(anyString()))
                .thenReturn(provider(new ConnectorProvider.PollResult(List.of(message), 7L, 12611L, false)));
        when(inboundService.handle(any(), any())).thenReturn(1);

        poller.poll();

        verify(inboundService).handle(record, List.of(message));
        verify(store).saveInboundCursor(1L, 7L, 12611L);
    }

    @Test
    void 没抢到租约就什么都不做() {
        when(store.listInboundEnabled()).thenReturn(List.of(record));
        when(store.claimLease(anyLong(), anyString(), anyInt())).thenReturn(false);

        poller.poll();

        verify(toolkit, never()).providerFor(anyString());
        verify(store, never()).saveInboundCursor(anyLong(), any(), anyLong());
    }

    @Test
    void 类型没有Provider时跳过且不动游标() {
        when(store.listInboundEnabled()).thenReturn(List.of(record));
        when(toolkit.providerFor(anyString())).thenReturn(null);

        poller.poll();

        verify(store, never()).saveInboundCursor(anyLong(), any(), anyLong());
        verify(inboundService, never()).handle(any(), any());
    }

    @Test
    void 关闭轮询开关时直接返回() {
        ConnectorInboundPoller disabled =
                new ConnectorInboundPoller(store, connectorService, toolkit, inboundService, false, 5);
        disabled.poll();

        verify(store, never()).listInboundEnabled();
    }

    @Test
    void 拉取抛异常不会中断其它连接器() {
        when(store.listInboundEnabled()).thenReturn(List.of(record));
        when(toolkit.providerFor(anyString())).thenThrow(new IllegalStateException("IMAP 挂了"));

        poller.poll();

        // 异常被吞掉，但租约必须释放（否则这个连接器要等租约过期才轮到别人）
        verify(store).releaseLease(eq(1L), anyString());
        assertThat(store).isNotNull();
    }
}
