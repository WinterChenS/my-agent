package com.myagent.agui;

import com.myagent.approval.service.ApprovalService;
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

    public AguiRunController(
            AguiRequestBodyParser requestParser,
            AguiEventEncoder eventEncoder,
            AguiRequestProcessor requestProcessor,
            AgentFactory agentFactory,
            ChatSessionService chatSessionService,
            ApprovalService approvalService) {
        this.requestParser = requestParser;
        this.eventEncoder = eventEncoder;
        this.requestProcessor = requestProcessor;
        this.agentFactory = agentFactory;
        this.chatSessionService = chatSessionService;
        this.approvalService = approvalService;
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
        }

        // 把用户身份通过 servlet 属性传给 runtime context 解析器（避免可伪造的请求头）
        httpRequest.setAttribute(PlatformRuntimeContextResolver.USER_ID_ATTRIBUTE, userId);

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

        Disposable subscription = requestProcessor
                .process(contextRequest)
                .events()
                .subscribe(
                        event -> {
                            send(emitter, event);
                            collect(event, assistantText, customEventNames, interrupts);
                        },
                        error -> {
                            log.warn("AG-UI run 执行失败 threadId={}: {}", threadId, error.toString());
                            sendRunError(emitter, threadId, input.getRunId(), error);
                            finish(emitter, session.getId(), assistantText, customEventNames, interrupts);
                        },
                        () -> finish(emitter, session.getId(), assistantText, customEventNames, interrupts));

        emitter.onCompletion(subscription::dispose);
        emitter.onTimeout(() -> {
            subscription.dispose();
            emitter.complete();
        });
        return emitter;
    }

    private void send(SseEmitter emitter, AguiEvent event) {
        try {
            emitter.send(SseEmitter.event().data(eventEncoder.encodeToJson(event)));
        } catch (Exception ex) {
            // 客户端断开是正常情况：不向上抛，避免影响其他请求。
            log.debug("SSE 发送失败（客户端可能已断开）: {}", ex.getMessage());
        }
    }

    private void sendRunError(SseEmitter emitter, String threadId, String runId, Throwable error) {
        try {
            emitter.send(SseEmitter.event()
                    .data(eventEncoder.encodeToJson(new AguiEvent.RunError(
                            threadId, runId, String.valueOf(error.getMessage()), "RUN_FAILED"))));
        } catch (Exception ex) {
            log.debug("SSE 错误事件发送失败: {}", ex.getMessage());
        }
    }

    private void finish(
            SseEmitter emitter,
            Long sessionId,
            StringBuilder assistantText,
            List<String> customEventNames,
            List<AguiEvent.Interrupt> interrupts) {
        try {
            chatSessionService.appendMessage(
                    sessionId, "ASSISTANT", assistantText.toString(), meta(customEventNames));
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
        try {
            emitter.complete();
        } catch (Exception ex) {
            log.debug("SSE 关闭失败: {}", ex.getMessage());
        }
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

    private static Map<String, Object> meta(List<String> customEventNames) {
        if (customEventNames.isEmpty()) {
            return null;
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("customEvents", customEventNames);
        return meta;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
