package com.myagent.chat.controller;

import com.myagent.auth.JwtPrincipal;
import com.myagent.chat.dto.MessageResponse;
import com.myagent.chat.dto.SessionRequest;
import com.myagent.chat.dto.SessionResponse;
import com.myagent.chat.service.ChatSessionService;
import com.myagent.common.BizException;
import com.myagent.common.Result;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 会话与历史消息。 */
@RestController
@RequestMapping("/api/sessions")
public class ChatSessionController {

    private final ChatSessionService service;

    public ChatSessionController(ChatSessionService service) {
        this.service = service;
    }

    @GetMapping
    public Result<List<SessionResponse>> list(@AuthenticationPrincipal JwtPrincipal principal) {
        return Result.ok(service.list(requireUserId(principal)));
    }

    @PostMapping
    public Result<SessionResponse> create(
            @AuthenticationPrincipal JwtPrincipal principal,
            @Valid @RequestBody SessionRequest request) {
        return Result.ok(service.create(requireUserId(principal), request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(
            @AuthenticationPrincipal JwtPrincipal principal, @PathVariable Long id) {
        service.delete(requireUserId(principal), id);
        return Result.ok();
    }

    @GetMapping("/{id}/messages")
    public Result<List<MessageResponse>> messages(
            @AuthenticationPrincipal JwtPrincipal principal, @PathVariable Long id) {
        return Result.ok(service.messages(requireUserId(principal), id));
    }

    private Long requireUserId(JwtPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw BizException.unauthorized("未登录");
        }
        return principal.userId();
    }
}
