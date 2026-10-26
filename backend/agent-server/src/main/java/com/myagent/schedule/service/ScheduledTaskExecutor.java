package com.myagent.schedule.service;

import com.myagent.agui.AgentFactory;
import com.myagent.channelconfig.entity.ChannelPeer;
import com.myagent.channelconfig.service.ChannelPushPort;
import com.myagent.chat.entity.ChatSession;
import com.myagent.chat.service.ChatSessionService;
import com.myagent.schedule.entity.ScheduledTask;
import com.myagent.schedule.entity.ScheduledTaskRun;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 单次定时任务的执行：跑 agent → 取正文 → 按目标推送 → 写执行记录。
 *
 * <p>与 Web / IM 走**同一条官方入口**（{@code agent.stream}），所以技能、MCP、记忆、工具、权限全部继承。
 *
 * <p>只取 {@code AGENT_RESULT} 的 {@link TextBlock}：思考过程与工具调用不写进会话、不推到 IM。
 */
@Component
public class ScheduledTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskExecutor.class);

    /** 推送正文长度上限（IM 单条消息太长会被截断/拒收）。 */
    static final int MAX_PUSH_CHARS = 3500;

    private final AgentFactory agentFactory;
    private final ScheduledTaskService taskService;
    private final ChatSessionService sessionService;
    private final com.myagent.agui.channel.ChannelSessionBridge sessionBridge;
    private final ObjectProvider<ChannelPushPort> pushPort;

    public ScheduledTaskExecutor(
            AgentFactory agentFactory,
            ScheduledTaskService taskService,
            ChatSessionService sessionService,
            com.myagent.agui.channel.ChannelSessionBridge sessionBridge,
            ObjectProvider<ChannelPushPort> pushPort) {
        this.agentFactory = agentFactory;
        this.taskService = taskService;
        this.sessionService = sessionService;
        this.sessionBridge = sessionBridge;
        this.pushPort = pushPort;
    }

    /**
     * 执行一个任务（同步，由调度线程池调用）。
     *
     * @param triggerType CRON / MANUAL / COALESCE
     */
    public void execute(ScheduledTask task, String nodeId, String triggerType) {
        long started = System.currentTimeMillis();
        ScheduledTaskRun run = taskService.startRun(task.getId(), nodeId, triggerType);
        String answer = null;
        String error = null;
        try {
            ChatSession session = taskService.ensureSession(task);
            answer = runAgent(task, session);
            deliver(task, session, answer);
            log.info("定时任务执行成功 id={} name={} 耗时={}ms 输出长度={}",
                    task.getId(), task.getName(), System.currentTimeMillis() - started,
                    answer == null ? 0 : answer.length());
        } catch (Exception ex) {
            error = rootMessage(ex);
            log.warn("定时任务执行失败 id={} name={}: {}", task.getId(), task.getName(), error, ex);
        }
        boolean paused = taskService.finishRun(
                task.getId(), run.getId(), error == null, answer, error,
                System.currentTimeMillis() - started);
        if (paused) {
            notifyPaused(task, error);
        }
    }

    // ===================== agent =====================

    private String runAgent(ScheduledTask task, ChatSession session) {
        HarnessAgent agent = agentFactory.build(task.getAgentId());
        Msg userMsg = Msg.builder()
                .role(MsgRole.USER)
                .content(List.of(TextBlock.builder().text(task.getPrompt()).build()))
                .build();
        RuntimeContext context = RuntimeContext.builder()
                .sessionId(session.getThreadId())
                .userId(String.valueOf(task.getCreatedByUserId()))
                .build();
        StreamOptions options = StreamOptions.builder()
                .eventTypes(EventType.ALL)
                .incremental(true)
                .build();
        StringBuilder answer = new StringBuilder();
        agent.stream(List.of(userMsg), options, context)
                .doOnNext(event -> accumulate(event, answer))
                .blockLast();
        String result = answer.toString().strip();
        if (result.isEmpty()) {
            throw new IllegalStateException("agent 没有产出可见正文（可能工具调用失败）");
        }
        return result;
    }

    /** 只累积正文增量（思考/工具调用/工具结果一律丢弃）。 */
    static void accumulate(Event event, StringBuilder out) {
        if (event == null || event.getType() != EventType.AGENT_RESULT) {
            return;
        }
        Msg message = event.getMessage();
        if (message == null) {
            return;
        }
        for (ContentBlock block : message.getContent()) {
            if (block instanceof TextBlock textBlock) {
                merge(out, textBlock.getText());
            }
        }
    }

    static void merge(StringBuilder out, String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        String current = out.toString();
        if (current.isEmpty()) {
            out.append(chunk);
        } else if (chunk.equals(current)) {
            // 重复整段，忽略
        } else if (chunk.startsWith(current)) {
            out.setLength(0);
            out.append(chunk);
        } else {
            out.append(chunk);
        }
    }

    // ===================== 推送 =====================

    private void deliver(ScheduledTask task, ChatSession session, String answer) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("scheduledTaskId", task.getId());
        meta.put("scheduledTaskName", task.getName());
        meta.put("scheduled", true);

        String type = task.getTargetType() == null ? "SESSION" : task.getTargetType();
        if ("NONE".equalsIgnoreCase(type)) {
            return;
        }
        // 会话里始终留一份（Web 可见），IM 只额外推送
        sessionService.appendMessage(session.getId(), "ASSISTANT", answer, meta);
        sessionService.touch(session.getId());
        if (!"IM".equalsIgnoreCase(type)) {
            return;
        }
        ChannelPushPort port = pushPort.getIfAvailable();
        if (port == null) {
            throw new IllegalStateException("渠道运行时未就绪，无法推送到 IM");
        }
        Long appId = task.getChannelAppId();
        String peerKey = task.getPeerKey();
        if (appId == null || peerKey == null || peerKey.isBlank()) {
            ChannelPeer peer = sessionBridge.peerOfSession(session.getId());
            if (peer == null) {
                throw new IllegalStateException(
                        "该会话没有绑定 IM 用户（请从 IM 会话创建任务，或在任务里指定渠道与接收人）");
            }
            appId = peer.getChannelAppId();
            peerKey = peer.getPeerKey();
        }
        int sent = port.push(appId, peerKey, truncate(answer, MAX_PUSH_CHARS), List.of());
        log.info("定时任务已推送到 IM taskId={} appId={} peer={} 条数={}",
                task.getId(), appId, peerKey, sent);
    }

    /** 连续失败自动暂停后通知创建人（会话里留一条，能推 IM 就再推一条）。 */
    private void notifyPaused(ScheduledTask task, String error) {
        String text = "定时任务「" + task.getName() + "」连续失败 "
                + ScheduledTaskService.AUTO_PAUSE_AFTER_FAILURES + " 次，已自动暂停。\n最近错误："
                + (error == null ? "-" : truncate(error, 500))
                + "\n处理完可以点「恢复」，或直接让我把任务改好。";
        try {
            if (task.getChatSessionId() != null) {
                sessionService.appendMessage(task.getChatSessionId(), "ASSISTANT", text,
                        Map.of("scheduledTaskId", task.getId(), "scheduledNotice", "PAUSED"));
            }
            ChannelPushPort port = pushPort.getIfAvailable();
            if (port != null && "IM".equalsIgnoreCase(task.getTargetType())) {
                Long appId = task.getChannelAppId();
                String peerKey = task.getPeerKey();
                if ((appId == null || peerKey == null) && task.getChatSessionId() != null) {
                    ChannelPeer peer = sessionBridge.peerOfSession(task.getChatSessionId());
                    if (peer != null) {
                        appId = peer.getChannelAppId();
                        peerKey = peer.getPeerKey();
                    }
                }
                if (appId != null && peerKey != null) {
                    port.push(appId, peerKey, text, List.of());
                }
            }
        } catch (Exception ex) {
            log.warn("通知任务暂停失败 taskId={}: {}", task.getId(), ex.toString());
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "\n…（内容过长，已截断）";
    }

    private static String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null ? current.getClass().getSimpleName() : message;
    }
}
