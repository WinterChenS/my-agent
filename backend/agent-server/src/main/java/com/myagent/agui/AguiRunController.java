package com.myagent.agui;

import com.myagent.approval.service.ApprovalService;
import com.myagent.artifact.service.ArtifactCaptureService;
import com.myagent.audit.service.TokenUsageRecorder;
import com.myagent.auth.JwtPrincipal;
import com.myagent.chat.entity.ChatSession;
import com.myagent.chat.service.ChatSessionService;
import com.myagent.common.BizException;
import io.agentscope.core.agui.AguiInterruptConstants;
import io.agentscope.core.agui.encoder.AguiEventEncoder;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.processor.AguiRequestProcessor;
import io.agentscope.core.agui.runtime.AguiRequestBodyParser;
import io.agentscope.core.agui.runtime.AguiRuntimeContextRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

/**
 * AG-UI 聊天入口：{@code POST /agui/run}（SSE）。
 *
 * <p>链路：官方 {@link AguiRequestBodyParser} 解析 → 按 threadId 定位/创建平台会话 →
 * 官方 {@link AguiRequestProcessor} 处理（含 HITL resume）→ 官方编码器输出 SSE → 落库。
 *
 * <p>注意：**必须走 processor 管线**。直接调 {@code adapter.run(input)} 时 AG-UI 的
 * {@code resume} 不会被翻译成 AgentScope 需要的确认信息，恢复执行会失败（Task 7.1 实测）。
 */
@RestController
public class AguiRunController {

    private static final Logger log = LoggerFactory.getLogger(AguiRunController.class);
    private static final String FORWARDED_AGENT_KEY = "agentId";

    private final AguiRequestBodyParser requestParser;
    private final AguiEventEncoder eventEncoder;
    private final AguiRequestProcessor requestProcessor;
    private final AgentFactory agentFactory;
    private final ChatSessionService chatSessionService;
    private final ApprovalService approvalService;
    private final TokenUsageRecorder tokenUsageRecorder;
    private final AguiSseSanitizer sseSanitizer;
    private final ArtifactCaptureService artifactCaptureService;
    private final SessionSkillOverrides sessionSkillOverrides;
    private final RunningRunRegistry runningRuns;
    private final SessionTitleService sessionTitleService;

    public AguiRunController(
            AguiRequestBodyParser requestParser,
            AguiEventEncoder eventEncoder,
            AguiRequestProcessor requestProcessor,
            AgentFactory agentFactory,
            ChatSessionService chatSessionService,
            ApprovalService approvalService,
            TokenUsageRecorder tokenUsageRecorder,
            AguiSseSanitizer sseSanitizer,
            ArtifactCaptureService artifactCaptureService,
            SessionSkillOverrides sessionSkillOverrides,
            RunningRunRegistry runningRuns,
            SessionTitleService sessionTitleService) {
        this.requestParser = requestParser;
        this.eventEncoder = eventEncoder;
        this.requestProcessor = requestProcessor;
        this.agentFactory = agentFactory;
        this.chatSessionService = chatSessionService;
        this.approvalService = approvalService;
        this.tokenUsageRecorder = tokenUsageRecorder;
        this.sseSanitizer = sseSanitizer;
        this.artifactCaptureService = artifactCaptureService;
        this.sessionSkillOverrides = sessionSkillOverrides;
        this.runningRuns = runningRuns;
        this.sessionTitleService = sessionTitleService;
    }

    @PostMapping(value = "/agui/run", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter run(
            @RequestBody String rawBody,
            @AuthenticationPrincipal JwtPrincipal principal,
            HttpServletRequest httpRequest) {
        Long userId = principal == null ? null : principal.userId();
        if (userId == null) {
            throw BizException.unauthorized("未登录");
        }

        RunAgentInput input = requestParser.parse(rawBody);
        String threadId = input.getThreadId();
        if (!StringUtils.hasText(threadId)) {
            throw BizException.badRequest("threadId 不能为空");
        }

        Optional<ChatSession> existing = chatSessionService.findByThread(userId, threadId);
        String agentRef = asString(input.getForwardedProp(FORWARDED_AGENT_KEY));
        Long agentDbId = StringUtils.hasText(agentRef)
                ? agentFactory.resolveAgentDbId(agentRef)
                : existing.map(ChatSession::getAgentId).orElse(null);
        if (agentDbId == null) {
            throw BizException.badRequest(
                    "无法确定 Agent：请在 forwardedProps.agentId 指定 agent_id，或复用已有 threadId");
        }

        ChatSession session = existing.orElseGet(
                () -> chatSessionService.createForThread(userId, threadId, agentDbId));

        // resume 场景下不重复记录用户消息
        if (input.getResume() == null || input.getResume().isEmpty()) {
            persistUserMessage(session.getId(), input);
            // Phase 17：首条用户消息 → 异步生成会话标题（像 ChatGPT 那样）
            sessionTitleService.generateIfFirstMessage(session, firstUserText(input));
        }

        // 把用户身份通过 servlet 属性传给 runtime context 解析器（避免可伪造的请求头）
        httpRequest.setAttribute(PlatformRuntimeContextResolver.USER_ID_ATTRIBUTE, userId);

        // 会话级技能（D21）：把 forwardedProps.skillIds 交给 AgentResolver 并入官方 SkillFilter
        sessionSkillOverrides.put(threadId, parseSkillIds(input.getForwardedProp("skillIds")));

        // 产物自动登记（D22）：run 前快照该会话工作区，结束时对比新增/变更文件
        java.util.Map<String, Long> workspaceSnapshot =
                artifactCaptureService.beginRun(String.valueOf(userId));

        AguiRuntimeContextRequest<HttpServletRequest> contextRequest =
                AguiRuntimeContextRequest.<HttpServletRequest>builder()
                        .input(input)
                        .pathAgentId(StringUtils.hasText(agentRef) ? agentRef : String.valueOf(agentDbId))
                        .transport(AguiRuntimeContextRequest.Transport.MVC)
                        .method("POST")
                        .path("/agui/run")
                        .nativeRequest(httpRequest)
                        .build();

        SseEmitter emitter = new SseEmitter(0L);
        StringBuilder assistantText = new StringBuilder();
        List<String> customEventNames = new ArrayList<>();
        List<AguiEvent.Interrupt> interrupts = new ArrayList<>();
        // 该 run 的 token 归属信息（模型/供应商）只需解析一次
        TokenUsageRecorder.TokenContext tokenContext = tokenUsageRecorder.resolveContext(agentDbId);
        String runId = input.getRunId();

        // Phase 12 / D25：区分「跑完了」与「客户端中途断开」
        java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean(false);
        // Phase 14：登记本轮 run（保存已生成文本；断开后进入宽限期而不是直接丢弃）
        runningRuns.beginRun(threadId, runId);
        // AG-UI 协议校正：RUN_ERROR 是终态，之后不能再发任何事件（官方扩展会多发一个 RUN_FINISHED，
        // 官方客户端因此报 "Cannot send event type 'RUN_FINISHED'..."）。规则与单测见 AguiStreamGuard。
        AguiStreamGuard streamGuard = new AguiStreamGuard();
        Disposable subscription = requestProcessor
                .process(contextRequest)
                .events()
                .subscribe(
                        event -> {
                            send(emitter, event, streamGuard);
                            collect(event, assistantText, customEventNames, interrupts);
                            runningRuns.syncText(threadId, assistantText);
                            recordTokenUsage(event, userId, session.getId(), agentDbId, tokenContext, runId);
                        },
                        error -> {
                            log.warn("AG-UI run 执行失败 threadId={}: {}", threadId, error.toString());
                            sendRunError(emitter, threadId, input.getRunId(), error, streamGuard);
                            finish(emitter, session.getId(), userId, threadId, workspaceSnapshot, assistantText,
                                    customEventNames, interrupts, finished);
                        },
                        () -> finish(emitter, session.getId(), userId, threadId, workspaceSnapshot, assistantText,
                                customEventNames, interrupts, finished));

        // Phase 14：客户端断开（刷新页面/切页/关标签页）时**不要立刻** dispose + 中断——
        // 实测那样做会让这一轮永远不落库（finish() 不执行 → 答案丢失，用户看到"对话没有接续"）。
        // 现在改为进入宽限期：期间有人回来（前端轮询 /run）就继续跑；一直没人回来才中断，且中断后照样落库。
        emitter.onCompletion(() -> runningRuns.markDetached(threadId));
        emitter.onError(ex -> runningRuns.markDetached(threadId));
        emitter.onTimeout(() -> {
            runningRuns.markDetached(threadId);
            emitter.complete();
        });
        return emitter;
    }

    /**
     * 采集 token 用量：AG-UI 的 CUSTOM / token_usage 事件（每位模型调用一次）。
     *
     * <p>payload 形如 {@code {"delta":{"inputTokens":..,"outputTokens":..,"totalTokens":..},
     * "cumulative":{...},"modelCall":{"replyId":..}}}；取 {@code delta} 落库。
     */
    private void recordTokenUsage(
            AguiEvent event,
            Long userId,
            Long sessionId,
            Long agentDbId,
            TokenUsageRecorder.TokenContext context,
            String runId) {
        if (!(event instanceof AguiEvent.Custom custom)
                || !"token_usage".equals(custom.name())
                || !(custom.value() instanceof Map<?, ?> rawValue)) {
            return;
        }
        Object delta = rawValue.get("delta");
        if (!(delta instanceof Map<?, ?> rawDelta)) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> deltaMap = (Map<String, Object>) rawDelta;
        tokenUsageRecorder.record(userId, sessionId, agentDbId, context, runId, deltaMap);
    }

    private void send(SseEmitter emitter, AguiEvent event, AguiStreamGuard streamGuard) {
        if (!streamGuard.shouldSend(event)) {
            // 已进入 RUN_ERROR 终态：后续事件（尤其官方扩展跟着发的 RUN_FINISHED）一律不下发
            log.debug("已进入 RUN_ERROR 终态，丢弃后续事件: {}", event.getType());
            return;
        }
        try {
            // 去掉 null 字段：官方客户端 Zod 校验不接受 null（见 AguiSseSanitizer 注释）
            emitter.send(SseEmitter.event().data(sseSanitizer.stripNulls(eventEncoder.encodeToJson(event))));
        } catch (Exception ex) {
            // 客户端断开是正常情况：不向上抛，避免影响其他请求。
            log.debug("SSE 发送失败（客户端可能已断开）: {}", ex.getMessage());
        }
    }

    private void sendRunError(
            SseEmitter emitter,
            String threadId,
            String runId,
            Throwable error,
            AguiStreamGuard streamGuard) {
        if (!streamGuard.shouldSendFallbackError()) {
            // 官方扩展已经发过 RUN_ERROR 了，不再重复发第二个错误事件
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .data(sseSanitizer.stripNulls(eventEncoder.encodeToJson(new AguiEvent.RunError(
                            threadId, runId, String.valueOf(error.getMessage()), "RUN_FAILED")))));
        } catch (Exception ex) {
            log.debug("SSE 错误事件发送失败: {}", ex.getMessage());
        }
    }

    private void finish(
            SseEmitter emitter,
            Long sessionId,
            Long userId,
            String threadId,
            Map<String, Long> workspaceSnapshot,
            StringBuilder assistantText,
            List<String> customEventNames,
            List<AguiEvent.Interrupt> interrupts,
            java.util.concurrent.atomic.AtomicBoolean finished) {
        finished.set(true);
        // Phase 14：收尾时先标记完成（取消宽限期定时器），避免"已落库还被判为断开超时"而误中断
        runningRuns.markFinished(threadId);
        // 是否被用户手动停止（停止入口会置位）——落进消息 meta，刷新回放也能标注「已停止」
        boolean stopped = runningRuns.consumeStopped(threadId);
        try {
            chatSessionService.appendMessage(
                    sessionId, "ASSISTANT", assistantText.toString(), meta(customEventNames, stopped));
            chatSessionService.touch(sessionId);
        } catch (Exception ex) {
            log.error("落库 assistant 消息失败 sessionId={}", sessionId, ex);
        }
        // 被权限系统拦截的工具调用：落库为待审批（HITL 主链路）
        for (AguiEvent.Interrupt interrupt : interrupts) {
            try {
                approvalService.recordPending(
                        sessionId,
                        interrupt.id(),
                        asString(interrupt.metadata() == null
                                ? null
                                : interrupt.metadata().get(AguiInterruptConstants.METADATA_TOOL_NAME)),
                        extractArgs(interrupt));
            } catch (Exception ex) {
                log.error("落库待审批记录失败 interruptId={}", interrupt.id(), ex);
            }
        }
        // 登记本次 run 产出的文件（失败不影响 run）
        try {
            artifactCaptureService.endRun(userId, sessionId, threadId, workspaceSnapshot);
        } catch (Exception ex) {
            log.warn("产物登记失败 sessionId={}: {}", sessionId, ex.toString());
        }
        sessionSkillOverrides.clear(threadId);
        runningRuns.clear(threadId);
        try {
            emitter.complete();
        } catch (Exception ex) {
            log.debug("SSE 关闭失败: {}", ex.getMessage());
        }
    }

    /** 解析 forwardedProps.skillIds（数组或单个数字都兼容）。 */
    static java.util.List<Long> parseSkillIds(Object raw) {
        java.util.List<Long> ids = new java.util.ArrayList<>();
        if (raw instanceof java.util.Collection<?> collection) {
            for (Object item : collection) {
                Long id = asLong(item);
                if (id != null) {
                    ids.add(id);
                }
            }
        } else {
            Long single = asLong(raw);
            if (single != null) {
                ids.add(single);
            }
        }
        return ids;
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank() && text.trim().chars().allMatch(Character::isDigit)) {
            return Long.valueOf(text.trim());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractArgs(AguiEvent.Interrupt interrupt) {
        Object raw = interrupt.metadata() == null
                ? null
                : interrupt.metadata().get(AguiInterruptConstants.METADATA_TOOL_INPUT);
        if (raw instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return raw == null ? null : Map.of("raw", raw);
    }

    /** 取本轮最后一条用户消息的文本（供标题生成使用）。 */
    private static String firstUserText(RunAgentInput input) {
        if (input.getMessages() == null || input.getMessages().isEmpty()) {
            return null;
        }
        for (int i = input.getMessages().size() - 1; i >= 0; i--) {
            AguiMessage message = input.getMessages().get(i);
            if ("user".equalsIgnoreCase(message.getRole())) {
                return message.getTextContent();
            }
        }
        return null;
    }

    private void persistUserMessage(Long sessionId, RunAgentInput input) {
        if (input.getMessages() == null || input.getMessages().isEmpty()) {
            return;
        }
        for (int i = input.getMessages().size() - 1; i >= 0; i--) {
            AguiMessage message = input.getMessages().get(i);
            if ("user".equalsIgnoreCase(message.getRole())) {
                chatSessionService.appendMessage(sessionId, "USER", message.getTextContent(), null);
                return;
            }
        }
    }

    private static void collect(
            AguiEvent event,
            StringBuilder assistantText,
            List<String> customNames,
            List<AguiEvent.Interrupt> interrupts) {
        if (event instanceof AguiEvent.TextMessageContent content && content.delta() != null) {
            assistantText.append(content.delta());
        } else if (event instanceof AguiEvent.Custom custom && custom.name() != null) {
            customNames.add(custom.name());
        } else if (event instanceof AguiEvent.RunFinished finished
                && finished.outcome() instanceof AguiEvent.RunFinishedInterruptOutcome interruptOutcome
                && interruptOutcome.interrupts() != null) {
            interrupts.addAll(interruptOutcome.interrupts());
        }
    }

    private static Map<String, Object> meta(List<String> customEventNames, boolean stopped) {
        if (customEventNames.isEmpty() && !stopped) {
            return null;
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        if (!customEventNames.isEmpty()) {
            meta.put("customEvents", customEventNames);
        }
        if (stopped) {
            // Phase 12 / D25：气泡据此标注「已停止」，刷新回放一致
            meta.put("stopped", true);
        }
        return meta;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
