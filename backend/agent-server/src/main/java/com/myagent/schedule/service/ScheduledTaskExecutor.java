package com.myagent.schedule.service;

import com.myagent.agui.AgentFactory;
import com.myagent.channelconfig.entity.ChannelPeer;
import com.myagent.channelconfig.service.ChannelPushPort;
import com.myagent.chat.entity.ChatSession;
import com.myagent.chat.service.ChatSessionService;
import com.myagent.artifact.dto.ArtifactResponse;
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

    /** 单次最多随结果发送的附件个数（每条 IM 消息只能带一个媒体，太多会刷屏）。 */
    static final int MAX_ATTACHMENTS = 5;

    private final AgentFactory agentFactory;
    private final ScheduledTaskService taskService;
    private final ChatSessionService sessionService;
    private final com.myagent.agui.channel.ChannelSessionBridge sessionBridge;
    private final ObjectProvider<ChannelPushPort> pushPort;
    private final com.myagent.artifact.service.ArtifactCaptureService artifactCaptureService;
    private final com.myagent.artifact.service.ArtifactService artifactService;
    /** 正在跑的执行，支持从界面取消。 */
    private final ScheduledTaskExecutionRegistry executionRegistry;

    public ScheduledTaskExecutor(
            AgentFactory agentFactory,
            ScheduledTaskService taskService,
            ChatSessionService sessionService,
            com.myagent.agui.channel.ChannelSessionBridge sessionBridge,
            ObjectProvider<ChannelPushPort> pushPort,
            com.myagent.artifact.service.ArtifactCaptureService artifactCaptureService,
            com.myagent.artifact.service.ArtifactService artifactService,
            ScheduledTaskExecutionRegistry executionRegistry) {
        this.agentFactory = agentFactory;
        this.taskService = taskService;
        this.sessionService = sessionService;
        this.sessionBridge = sessionBridge;
        this.pushPort = pushPort;
        this.artifactCaptureService = artifactCaptureService;
        this.artifactService = artifactService;
        this.executionRegistry = executionRegistry;
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
        String runId = java.util.UUID.randomUUID().toString();
        try {
            ChatSession session = taskService.ensureSession(task);
            // 产物快照：定时任务常要"生成报告文档并发出"，所以跑完要把新文件一起发
            java.util.Map<String, Long> before = snapshot(task);
            answer = runAgent(task, session, run.getId());
            java.util.List<Long> produced = captureArtifacts(task, session, before, runId);
            deliver(task, session, answer, produced);
            log.info("定时任务执行成功 id={} name={} 耗时={}ms 输出长度={} 产物={}",
                    task.getId(), task.getName(), System.currentTimeMillis() - started,
                    answer == null ? 0 : answer.length(), produced.size());
        } catch (Exception ex) {
            if (executionRegistry.wasCancelled(run.getId())) {
                // 用户主动取消：记「已取消」，不算失败（不计入连续失败次数）
                error = null;
            } else {
                error = rootMessage(ex);
                log.warn("定时任务执行失败 id={} name={}: {}", task.getId(), task.getName(), error, ex);
            }
        }
        boolean cancelled = executionRegistry.wasCancelled(run.getId());
        executionRegistry.unregister(run.getId());
        if (cancelled) {
            log.info("定时任务执行已取消 id={} name={} 已生成 {} 字", task.getId(), task.getName(),
                    answer == null ? 0 : answer.length());
        }
        boolean paused = taskService.finishRun(
                task.getId(), run.getId(), cancelled ? null : (error == null), answer, error,
                System.currentTimeMillis() - started);
        if (paused) {
            notifyPaused(task, error);
        }
    }

    // ===================== agent =====================

    private String runAgent(ScheduledTask task, ChatSession session, Long runDbId) {
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
        // 登记后即可从界面取消：取消会 dispose 这个订阅并中断 Agent
        ScheduledTaskExecutionRegistry.Handle handle =
                executionRegistry.register(runDbId, task.getName(), agent, context);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        handle.subscription.set(agent.stream(List.of(userMsg), options, context)
                .doOnNext(event -> accumulate(event, answer))
                .subscribe(
                        event -> { },
                        failure::set,
                        handle::finish));
        try {
            // 没有固定超时：定时任务可能跑很久；取消/完成都会 countDown
            handle.done.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("定时任务执行被中断", ex);
        }
        if (failure.get() != null && !handle.cancelled.get()) {
            throw new IllegalStateException(rootMessage(failure.get()), failure.get());
        }
        String result = answer.toString().strip();
        if (result.isEmpty() && handle.cancelled.get()) {
            return "（已取消，未生成内容）";
        }
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

    private void deliver(ScheduledTask task, ChatSession session, String answer, java.util.List<Long> produced) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("scheduledTaskId", task.getId());
        meta.put("scheduledTaskName", task.getName());
        meta.put("scheduled", true);
        if (produced != null && !produced.isEmpty()) {
            meta.put("artifactIds", produced);
        }

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
        // 正文一条 + 本轮产物（报告文档/图片）逐个发出
        int sent = port.push(appId, peerKey, truncate(answer, MAX_PUSH_CHARS), produced);
        log.info("定时任务已推送到 IM taskId={} appId={} peer={} 条数={} 附件={}",
                task.getId(), appId, peerKey, sent, produced.size());
    }

    private java.util.Map<String, Long> snapshot(ScheduledTask task) {
        try {
            return artifactCaptureService.beginRun(String.valueOf(task.getCreatedByUserId()));
        } catch (Exception ex) {
            log.debug("定时任务产物快照失败（忽略）: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * 把本轮新生成/修改的文件登记为产物，返回本次的 artifact id 列表（用于推送给 IM）。
     *
     * <p>这就是「每天生成报告文档」，agent 用 write_file/execute 产出文件后，
     * 平台自动收进来并随结果一起发出去。
     */
    private java.util.List<Long> captureArtifacts(
            ScheduledTask task, ChatSession session, java.util.Map<String, Long> before, String runId) {
        try {
            if (before == null) {
                return List.of();
            }
            artifactCaptureService.endRun(task.getCreatedByUserId(), session.getId(),
                    session.getThreadId(), before, runId);
            return artifactService.list(task.getCreatedByUserId(), session.getId()).stream()
                    .filter(a -> runId.equals(a.runId()))
                    .filter(a -> com.myagent.artifact.service.ArtifactService.ROLE_ARTIFACT
                            .equals(a.role()))
                    .filter(a -> a.sizeBytes() != null && a.sizeBytes() > 0)
                    // 图片优先，其次按 id 顺序；最多带 MAX_ATTACHMENTS 个（每条消息只能带一个媒体）
                    .sorted(java.util.Comparator.comparing(
                            (ArtifactResponse a) -> com.myagent.agui.channel.MediaFiles.isImage(a.fileName())
                                    ? 0 : 1))
                    .limit(MAX_ATTACHMENTS)
                    .map(ArtifactResponse::id)
                    .toList();
        } catch (Exception ex) {
            log.warn("登记定时任务产物失败 taskId={}: {}", task.getId(), ex.toString());
            return List.of();
        }
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
