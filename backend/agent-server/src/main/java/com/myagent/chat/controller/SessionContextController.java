package com.myagent.chat.controller;

import com.myagent.agui.context.SessionContextService;
import com.myagent.auth.JwtPrincipal;
import com.myagent.chat.entity.ChatSession;
import com.myagent.chat.service.ChatSessionService;
import com.myagent.common.BizException;
import com.myagent.common.Result;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话上下文控制（tier1-upgrades ②）：看用量、一键压缩、一键清空。
 *
 * <p>「清空上下文」不等于删会话：聊天记录与产物都保留，只是让模型从零开始。
 */
@RestController
public class SessionContextController {

    private final ChatSessionService chatSessionService;
    private final SessionContextService contextService;

    public SessionContextController(
            ChatSessionService chatSessionService, SessionContextService contextService) {
        this.chatSessionService = chatSessionService;
        this.contextService = contextService;
    }

    /** 返回体：删除的状态行数（清空时用）。 */
    public record ClearResponse(int removed) {}

    /** 当前上下文用量与阈值。 */
    @GetMapping("/api/sessions/{id}/context")
    public Result<SessionContextService.Usage> usage(
            @AuthenticationPrincipal JwtPrincipal principal, @PathVariable Long id) {
        Long userId = requireUserId(principal);
        ChatSession session = chatSessionService.requireOwned(userId, id);
        return Result.ok(contextService.usage(session.getThreadId(), session.getAgentId()));
    }

    /** 立刻压缩上下文（官方压缩能力），返回压缩后的用量。 */
    @PostMapping("/api/sessions/{id}/context/compact")
    public Result<SessionContextService.Usage> compact(
            @AuthenticationPrincipal JwtPrincipal principal, @PathVariable Long id) {
        Long userId = requireUserId(principal);
        ChatSession session = chatSessionService.requireOwned(userId, id);
        return Result.ok(contextService.compact(session.getThreadId(), session.getAgentId()));
    }

    /** 清空上下文（删框架状态；聊天记录保留）。 */
    @PostMapping("/api/sessions/{id}/context/clear")
    public Result<ClearResponse> clear(
            @AuthenticationPrincipal JwtPrincipal principal, @PathVariable Long id) {
        Long userId = requireUserId(principal);
        ChatSession session = chatSessionService.requireOwned(userId, id);
        return Result.ok(new ClearResponse(contextService.clear(session.getThreadId())));
    }

    private Long requireUserId(JwtPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw BizException.unauthorized("未登录");
        }
        return principal.userId();
    }
}
