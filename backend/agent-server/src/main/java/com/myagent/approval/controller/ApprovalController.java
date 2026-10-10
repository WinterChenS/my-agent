package com.myagent.approval.controller;

import com.myagent.approval.dto.ApprovalDecisionRequest;
import com.myagent.approval.dto.ApprovalDecisionResponse;
import com.myagent.approval.dto.ApprovalResponse;
import com.myagent.approval.service.ApprovalService;
import com.myagent.auth.JwtPrincipal;
import com.myagent.common.BizException;
import com.myagent.common.Result;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HITL 审批。 */
@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

    private final ApprovalService service;

    public ApprovalController(ApprovalService service) {
        this.service = service;
    }

    /** 某会话下的审批记录。 */
    @GetMapping
    public Result<List<ApprovalResponse>> list(
            @AuthenticationPrincipal JwtPrincipal principal, @RequestParam Long sessionId) {
        return Result.ok(service.listBySession(requireUserId(principal), sessionId));
    }

    /**
     * 批准 / 拒绝。
     *
     * <p>路径里的 {@code interruptId} 即前端从 RUN_FINISHED 事件拿到的 AG-UI interrupt id。
     * 返回的 {@code resumeStatus} 供前端发起带 resume 的新一轮 /agui/run。
     */
    @PostMapping("/{interruptId}/decide")
    public Result<ApprovalDecisionResponse> decide(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable String interruptId,
            @Valid @RequestBody ApprovalDecisionRequest request) {
        return Result.ok(service.decide(requireUserId(principal), interruptId, request));
    }

    private Long requireUserId(JwtPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw BizException.unauthorized("未登录");
        }
        return principal.userId();
    }
}
