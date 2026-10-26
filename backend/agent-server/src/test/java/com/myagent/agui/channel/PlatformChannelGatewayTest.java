package com.myagent.agui.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.myagent.channelconfig.entity.ChannelApp;
import com.myagent.channelconfig.service.ChannelAppService;
import com.myagent.chat.entity.ChatSession;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.MsgContext;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import io.agentscope.harness.agent.gateway.channel.Peer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * 渠道网关：只把「正文」发给 IM、入站媒体分流、错误降级。
 *
 * <p>用 Mock 的 HarnessAgent 驱动，不依赖真实模型与钉钉服务。
 */
class PlatformChannelGatewayTest {

    private ChannelAppService channelAppService;
    private ChannelSessionBridge sessionBridge;
    private com.myagent.agui.AgentFactory agentFactory;
    private com.myagent.artifact.service.ArtifactService artifactService;
    private com.myagent.artifact.service.ArtifactCaptureService artifactCaptureService;
    private DingTalkMediaClientFactory mediaClientFactory;
    private HarnessAgent agent;

    private PlatformChannelGateway gateway;
    private ChannelApp app;
    private ChatSession session;

    @BeforeEach
    void setUp() {
        channelAppService = mock(ChannelAppService.class);
        sessionBridge = mock(ChannelSessionBridge.class);
        agentFactory = mock(com.myagent.agui.AgentFactory.class);
        artifactService = mock(com.myagent.artifact.service.ArtifactService.class);
        artifactCaptureService = mock(com.myagent.artifact.service.ArtifactCaptureService.class);
        mediaClientFactory = mock(DingTalkMediaClientFactory.class);
        agent = mock(HarnessAgent.class);

        gateway = new PlatformChannelGateway(
                channelAppService, sessionBridge, agentFactory,
                artifactService, artifactCaptureService, mediaClientFactory, true);

        app = new ChannelApp();
        app.setId(1L);
        app.setChannelType("DINGTALK");
        app.setAgentId(7L);
        app.setOwnerUserId(1L);
        app.setName("测试机器人");

        session = new ChatSession();
        session.setId(100L);
        session.setThreadId("thread-1");
        session.setAgentId(7L);

        when(channelAppService.require(1L)).thenReturn(app);
        when(sessionBridge.ensureSession(any(), any(), any(), anyString())).thenReturn(session);
        when(agentFactory.build(7L)).thenReturn(agent);
        when(agentFactory.isMultimodal(7L)).thenReturn(true);
        when(artifactCaptureService.beginRun(anyString())).thenReturn(Map.of());
        when(artifactService.list(any(), any())).thenReturn(List.of());
        when(mediaClientFactory.get(any())).thenReturn(null);
    }

    // ===================== 纯函数 =====================

    @Test
    void 增量与累积两种下发方式都能正确合并() {
        StringBuilder out = new StringBuilder();
        PlatformChannelGateway.merge(out, "你");
        PlatformChannelGateway.merge(out, "好");
        assertThat(out.toString()).isEqualTo("你好");

        StringBuilder cumulative = new StringBuilder();
        PlatformChannelGateway.merge(cumulative, "你");
        PlatformChannelGateway.merge(cumulative, "你好");   // 累积式：整段替换
        PlatformChannelGateway.merge(cumulative, "你好");   // 重复整段：忽略
        assertThat(cumulative.toString()).isEqualTo("你好");
    }

    @Test
    void 占位符会从正文里清掉() {
        assertThat(PlatformChannelGateway.cleanMarkers("[image] 帮我看看这个")).isEqualTo("帮我看看这个");
        assertThat(PlatformChannelGateway.cleanMarkers("看下 [file: 需求.docx] 谢谢")).isEqualTo("看下 谢谢");
        assertThat(PlatformChannelGateway.cleanMarkers(null)).isEmpty();
    }

    @Test
    void 只累积正文_思考事件被忽略() {
        StringBuilder out = new StringBuilder();
        PlatformChannelGateway.accumulate(
                new Event(EventType.REASONING, text("我先想想"), false), out);
        PlatformChannelGateway.accumulate(
                new Event(EventType.AGENT_RESULT, text("最终答案"), true), out);
        assertThat(out.toString()).isEqualTo("最终答案");
    }

    @Test
    void mime按扩展名推断() {
        assertThat(PlatformChannelGateway.guessMime("a.PNG")).isEqualTo("image/png");
        assertThat(PlatformChannelGateway.guessMime("a.pdf")).isEqualTo("application/pdf");
        assertThat(PlatformChannelGateway.guessMime("a.unknown")).isEqualTo("application/octet-stream");
    }

    // ===================== 端到端（Mock） =====================

    @Test
    void 一轮文本对话_只把正文落库并作为回复返回() {
        when(agent.stream(anyList(), any(StreamOptions.class), any(RuntimeContext.class)))
                .thenReturn(Flux.just(
                        new Event(EventType.REASONING, text("内部思考，不该发出去"), false),
                        new Event(EventType.AGENT_RESULT, text("你好，我是助手"), true)));

        Msg reply = gateway.run(
                MsgContext.defaultContext(), List.of(text("你好")), address(), null, inbound("你好"))
                .block();

        assertThat(reply).isNotNull();
        assertThat(reply.getTextContent()).isEqualTo("你好，我是助手");
        verify(sessionBridge).appendUserMessage(eq(session), eq("你好"), any());
        verify(sessionBridge).appendAssistantMessage(eq(session), eq("你好，我是助手"), any());
    }

    @Test
    void 入站图片_非多模态模型时不塞图片块而是提示() {
        when(agentFactory.isMultimodal(7L)).thenReturn(false);
        DingTalkMediaClient client = mock(DingTalkMediaClient.class);
        when(mediaClientFactory.get(any())).thenReturn(client);
        when(client.download("code-1")).thenReturn(new byte[] {1, 2, 3});
        when(agent.stream(anyList(), any(StreamOptions.class), any(RuntimeContext.class)))
                .thenReturn(Flux.just(new Event(EventType.AGENT_RESULT, text("收到图片"), true)));

        InboundMessage withImage = InboundMessage.builder(
                        "1", Peer.direct("staff-1"),
                        List.of(Msg.builder()
                                .role(MsgRole.USER)
                                .textContent("[image]")
                                .metadata(Map.of(
                                        "channelMediaKind", "image",
                                        "channelMediaId", "code-1",
                                        "channelMediaFileName", "a.png",
                                        "channelMessageId", "m1"))
                                .build()))
                .senderId("staff-1")
                .build();

        Msg reply = gateway.run(
                MsgContext.defaultContext(), List.of(text("")), address(), null, withImage).block();

        assertThat(reply.getTextContent()).isEqualTo("收到图片");
        // 图片落到会话产物 + 显式物化到工作区（store 本身不物化），正文里带上"不支持看图"的说明
        verify(artifactService).store(
                eq(1L), eq(100L), eq(com.myagent.artifact.service.ArtifactService.ROLE_SOURCE),
                eq("a.png"), anyString(), any(), eq(false));
        verify(artifactService).materialize(eq("1"), any(), eq("a.png"));
        verify(sessionBridge).appendUserMessage(
                eq(session), org.mockito.ArgumentMatchers.contains("不支持看图"), any());
    }

    @Test
    void 入站Excel_直接给原文件路径让模型用python读_不再生成txt() {
        DingTalkMediaClient client = mock(DingTalkMediaClient.class);
        when(mediaClientFactory.get(any())).thenReturn(client);
        when(client.download("code-x")).thenReturn(new byte[] {1, 2, 3});
        when(agent.stream(anyList(), any(StreamOptions.class), any(RuntimeContext.class)))
                .thenReturn(Flux.just(new Event(EventType.AGENT_RESULT, text("好"), true)));

        InboundMessage withFile = InboundMessage.builder(
                        "1", Peer.direct("staff-1"),
                        List.of(Msg.builder()
                                .role(MsgRole.USER)
                                .textContent("[file: 白名单.xlsx]")
                                .metadata(Map.of(
                                        "channelMediaKind", "file",
                                        "channelMediaId", "code-x",
                                        "channelMediaFileName", "白名单.xlsx",
                                        "channelMessageId", "m2"))
                                .build()))
                .senderId("staff-1")
                .build();

        gateway.run(MsgContext.defaultContext(), List.of(text("")), address(), null, withFile).block();

        // 原文件必须物化到工作区（模型自己读）
        verify(artifactService).materialize(eq("1"), any(), eq("白名单.xlsx"));
        // 不能再生成 .txt 旁挂文件
        verify(artifactService, never()).materialize(eq("1"), any(), eq("白名单.xlsx.txt"));
        // 提示里给路径 + 指向 execute
        verify(sessionBridge).appendUserMessage(
                eq(session), org.mockito.ArgumentMatchers.contains("uploads/白名单.xlsx"), any());
        verify(sessionBridge).appendUserMessage(
                eq(session), org.mockito.ArgumentMatchers.contains("execute"), any());
    }

    @Test
    void Agent异常时回一句人话而不是静默() {
        when(agent.stream(anyList(), any(StreamOptions.class), any(RuntimeContext.class)))
                .thenThrow(new IllegalStateException("模型超时"));

        Msg reply = gateway.run(
                MsgContext.defaultContext(), List.of(text("在吗")), address(), null, inbound("在吗"))
                .block();

        assertThat(reply.getTextContent()).contains("这条没跑成功").contains("模型超时");
        verify(sessionBridge, never()).appendAssistantMessage(any(), anyString(), any());
    }

    @Test
    void 没有对应渠道应用时给出明确提示() {
        when(channelAppService.require(1L)).thenThrow(new com.myagent.common.BizException(404, "不存在", null));

        Msg reply = gateway.run(
                MsgContext.defaultContext(), List.of(text("hi")), address(), null, inbound("hi")).block();

        assertThat(reply.getTextContent()).contains("还没配置对应的渠道应用");
    }

    // ===================== 辅助 =====================

    private static Msg text(String content) {
        return Msg.builder().role(MsgRole.ASSISTANT).textContent(content).build();
    }

    private static OutboundAddress address() {
        return OutboundAddress.direct("1", "staff-1");
    }

    private static InboundMessage inbound(String text) {
        return InboundMessage.builder("1", Peer.direct("staff-1"), List.of(
                        Msg.builder().role(MsgRole.USER).textContent(text).build()))
                .senderId("staff-1")
                .build();
    }
}
