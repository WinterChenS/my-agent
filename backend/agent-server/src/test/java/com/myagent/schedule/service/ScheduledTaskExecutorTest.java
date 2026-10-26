package com.myagent.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.myagent.channelconfig.service.ChannelPushPort;
import com.myagent.chat.entity.ChatSession;
import com.myagent.chat.service.ChatSessionService;
import com.myagent.schedule.entity.ScheduledTask;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** 定时执行器：只取正文；推送目标解析；失败要留痕。 */
class ScheduledTaskExecutorTest {

    @Test
    void 只累积正文增量_思考与工具事件被丢掉() {
        StringBuilder out = new StringBuilder();
        ScheduledTaskExecutor.accumulate(reasoning("我想想怎么汇总"), out);
        ScheduledTaskExecutor.accumulate(toolResult("csv 内容"), out);
        ScheduledTaskExecutor.accumulate(result("昨天的销售额是 12 万"), out);
        ScheduledTaskExecutor.accumulate(result("，环比 +8%"), out);

        assertThat(out.toString()).isEqualTo("昨天的销售额是 12 万，环比 +8%");
    }

    @Test
    void 累积式下发也能正确合并() {
        StringBuilder out = new StringBuilder();
        ScheduledTaskExecutor.merge(out, "第一句");
        ScheduledTaskExecutor.merge(out, "第一句第二句");   // 累积式整段替换
        ScheduledTaskExecutor.merge(out, "第一句第二句");   // 重复整段忽略
        assertThat(out.toString()).isEqualTo("第一句第二句");
    }

    @Test
    void 推送目标为NONE时不写会话不推IM() {
        ScheduledTaskService taskService = Mockito.mock(ScheduledTaskService.class);
        ChatSessionService sessionService = Mockito.mock(ChatSessionService.class);
        com.myagent.agui.channel.ChannelSessionBridge bridge =
                Mockito.mock(com.myagent.agui.channel.ChannelSessionBridge.class);
        ChannelPushPort port = Mockito.mock(ChannelPushPort.class);
        var provider = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        provider.registerSingleton("pushPort", port);
        ScheduledTaskExecutor executor = new ScheduledTaskExecutor(
                Mockito.mock(com.myagent.agui.AgentFactory.class), taskService, sessionService, bridge,
                provider.getBeanProvider(ChannelPushPort.class));

        ScheduledTask task = new ScheduledTask();
        task.setId(1L);
        task.setTargetType("NONE");
        task.setName("静默任务");
        ChatSession session = new ChatSession();
        session.setId(9L);

        executorDeliver(executor, task, session, "结果正文");

        Mockito.verifyNoInteractions(sessionService);
        Mockito.verifyNoInteractions(port);
    }

    private static void executorDeliver(
            ScheduledTaskExecutor executor, ScheduledTask task, ChatSession session, String answer) {
        try {
            var method = ScheduledTaskExecutor.class
                    .getDeclaredMethod("deliver", ScheduledTask.class, ChatSession.class, String.class);
            method.setAccessible(true);
            method.invoke(executor, task, session, answer);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Event reasoning(String text) {
        return new Event(EventType.REASONING, text(text), false);
    }

    private static Event toolResult(String text) {
        return new Event(EventType.TOOL_RESULT, text(text), false);
    }

    private static Event result(String text) {
        return new Event(EventType.AGENT_RESULT, text(text), true);
    }

    private static Msg text(String content) {
        return Msg.builder().role(MsgRole.ASSISTANT)
                .content(List.of(TextBlock.builder().text(content).build())).build();
    }
}
