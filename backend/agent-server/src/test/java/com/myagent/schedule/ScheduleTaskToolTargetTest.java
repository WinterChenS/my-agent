package com.myagent.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.myagent.channelconfig.entity.ChannelApp;
import com.myagent.channelconfig.entity.ChannelPeer;
import com.myagent.channelconfig.service.ChannelTargetResolver;
import com.myagent.chat.entity.ChatSession;
import com.myagent.chat.mapper.ChatSessionMapper;
import com.myagent.schedule.dto.ScheduledTaskRequest;
import com.myagent.schedule.entity.ScheduledTask;
import com.myagent.schedule.service.ScheduledTaskService;
import com.myagent.schedule.tool.ScheduleTaskTool;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * schedule_task 的 IM 目标约束：必须落到**确定的**渠道与接收人，解析不出就拒绝创建。
 *
 * <p>用户反馈的真 bug：以前没有 channel/recipient 参数，{@code target=IM} 只能回退到"会话绑定"，
 * 多机器人/多接收人时会把结果发给别人。这里的用例钉住"宁可拒绝，也不猜"。
 */
class ScheduleTaskToolTargetTest {

    private ScheduledTaskService taskService;
    private ChatSessionMapper sessionMapper;
    private com.myagent.agui.channel.ChannelSessionBridge sessionBridge;
    private InMemoryResolver resolver;
    private ScheduleTaskTool tool;

    /** 内存版解析器：2 号渠道（张三/项目群）、7 号渠道（小李）。 */
    private static final class InMemoryResolver extends ChannelTargetResolver {

        private final List<ChannelApp> apps = new ArrayList<>();
        private final Map<Long, List<ChannelPeer>> peers = new HashMap<>();

        InMemoryResolver() {
            super(null, null);
            addApp(2L, "钉钉机器人").addPeer(2L, "0935422236", "张三", "DIRECT")
                    .addPeer(2L, "cid-group", "项目群", "GROUP")
                    .addApp(7L, "测试机器人").addPeer(7L, "peer-x", "小李", "DIRECT");
        }

        private InMemoryResolver addApp(long id, String name) {
            ChannelApp app = new ChannelApp();
            app.setId(id);
            app.setName(name);
            app.setChannelType("DINGTALK");
            app.setEnabled(true);
            apps.add(app);
            return this;
        }

        private InMemoryResolver addPeer(long appId, String key, String name, String kind) {
            ChannelPeer peer = new ChannelPeer();
            peer.setChannelAppId(appId);
            peer.setPeerKey(key);
            peer.setPeerName(name);
            peer.setPeerKind(kind);
            peers.computeIfAbsent(appId, k -> new ArrayList<>()).add(peer);
            return this;
        }

        @Override
        protected List<ChannelApp> loadApps() {
            return apps;
        }

        @Override
        protected ChannelApp loadApp(Long channelAppId) {
            return apps.stream().filter(a -> a.getId().equals(channelAppId)).findFirst().orElse(null);
        }

        @Override
        protected List<ChannelPeer> loadPeers(Long channelAppId) {
            return peers.getOrDefault(channelAppId, List.of());
        }
    }

    @BeforeEach
    void setUp() {
        taskService = mock(ScheduledTaskService.class);
        sessionMapper = mock(ChatSessionMapper.class);
        sessionBridge = mock(com.myagent.agui.channel.ChannelSessionBridge.class);
        resolver = new InMemoryResolver();
        tool = new ScheduleTaskTool(taskService, sessionMapper, sessionBridge, resolver);

        ChatSession session = new ChatSession();
        session.setId(2159L);
        session.setThreadId("thread-1");
        session.setAgentId(37L);
        session.setUserId(1L);
        when(sessionMapper.selectOne(any())).thenReturn(session);

        ScheduledTask created = new ScheduledTask();
        created.setId(99L);
        created.setName("测试任务");
        created.setScheduleType("EVERY_MINUTES");
        created.setIntervalMinutes(30);
        created.setTargetType("IM");
        created.setStatus("ACTIVE");
        when(taskService.create(any(), any())).thenReturn(created);
        when(taskService.list(any())).thenReturn(List.of());
    }

    private ToolCallParam param(Map<String, Object> input) {
        RuntimeContext context = RuntimeContext.builder().sessionId("thread-1").userId("1").build();
        return ToolCallParam.builder()
                .toolUseBlock(new ToolUseBlock("call-1", "schedule_task", input))
                .input(input)
                .runtimeContext(context)
                .build();
    }

    private String textOf(ToolResultBlock block) {
        return block.getOutput().stream()
                .map(content -> content instanceof io.agentscope.core.message.TextBlock t ? t.getText() : "")
                .reduce("", String::concat);
    }

    private Map<String, Object> baseInput() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("name", "测试任务");
        input.put("prompt", "汇总昨天的数据");
        input.put("everyMinutes", 30);
        input.put("target", "IM");
        return input;
    }

    @Test
    void 指定渠道与接收人_按解析结果落库() {
        Map<String, Object> input = baseInput();
        input.put("channel", "钉钉机器人");
        input.put("recipient", "张三");

        tool.callAsync(param(input)).block();

        ArgumentCaptor<ScheduledTaskRequest> captor = ArgumentCaptor.forClass(ScheduledTaskRequest.class);
        verify(taskService).create(captor.capture(), any());
        ScheduledTaskRequest request = captor.getValue();
        assertThat(request.targetType()).isEqualTo("IM");
        assertThat(request.channelAppId()).isEqualTo(2L);
        assertThat(request.peerKey()).isEqualTo("0935422236");
    }

    @Test
    void 只说了要发IM但没指定渠道_拒绝创建并给候选() {
        String text = textOf(tool.callAsync(param(baseInput())).block());

        verify(taskService, never()).create(any(), any());
        assertThat(text).contains("没有创建任务").contains("多个渠道");
        assertThat(text).contains("钉钉机器人").contains("测试机器人");
    }

    @Test
    void 接收人不存在_拒绝创建并列出该渠道的接收人() {
        Map<String, Object> input = baseInput();
        input.put("channel", "钉钉机器人");
        input.put("recipient", "王五");

        String text = textOf(tool.callAsync(param(input)).block());

        verify(taskService, never()).create(any(), any());
        assertThat(text).contains("找不到接收人").contains("张三").contains("项目群");
    }

    @Test
    void 渠道不存在_拒绝创建并列出全部渠道() {
        Map<String, Object> input = baseInput();
        input.put("channel", "飞书机器人");
        input.put("recipient", "张三");

        String text = textOf(tool.callAsync(param(input)).block());

        verify(taskService, never()).create(any(), any());
        assertThat(text).contains("找不到渠道");
    }

    @Test
    void 群聊接收人可以指定() {
        Map<String, Object> input = baseInput();
        input.put("channel", "钉钉机器人");
        input.put("recipient", "项目群");

        tool.callAsync(param(input)).block();

        ArgumentCaptor<ScheduledTaskRequest> captor = ArgumentCaptor.forClass(ScheduledTaskRequest.class);
        verify(taskService).create(captor.capture(), any());
        assertThat(captor.getValue().peerKey()).isEqualTo("cid-group");
    }

    @Test
    void 显式指定优先于会话绑定_不会发给会话默认那个人() {
        // 会话绑定的是 7 号渠道的小李
        ChannelPeer sessionPeer = new ChannelPeer();
        sessionPeer.setChannelAppId(7L);
        sessionPeer.setPeerKey("peer-x");
        when(sessionBridge.peerOfSession(2159L)).thenReturn(sessionPeer);

        Map<String, Object> input = baseInput();
        input.put("channel", "钉钉机器人");
        input.put("recipient", "张三");
        tool.callAsync(param(input)).block();

        ArgumentCaptor<ScheduledTaskRequest> captor = ArgumentCaptor.forClass(ScheduledTaskRequest.class);
        verify(taskService).create(captor.capture(), any());
        assertThat(captor.getValue().channelAppId()).isEqualTo(2L);
        assertThat(captor.getValue().peerKey()).isEqualTo("0935422236");
    }

    @Test
    void 目标为会话时不需要IM参数() {
        Map<String, Object> input = baseInput();
        input.put("target", "SESSION");

        tool.callAsync(param(input)).block();

        ArgumentCaptor<ScheduledTaskRequest> captor = ArgumentCaptor.forClass(ScheduledTaskRequest.class);
        verify(taskService).create(captor.capture(), any());
        assertThat(captor.getValue().targetType()).isEqualTo("SESSION");
        assertThat(captor.getValue().channelAppId()).isNull();
        assertThat(captor.getValue().peerKey()).isNull();
    }

    @Test
    void 回显里写清真实推送目标() {
        Map<String, Object> input = baseInput();
        input.put("channel", "钉钉机器人");
        input.put("recipient", "张三");

        String text = textOf(tool.callAsync(param(input)).block());

        assertThat(text).contains("钉钉机器人").contains("张三");
    }
}
