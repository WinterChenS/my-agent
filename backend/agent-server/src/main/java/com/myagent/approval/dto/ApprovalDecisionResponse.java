package com.myagent.approval.dto;

import java.util.Map;

/**
 * 审批决定结果。
 *
 * <p>{@code resumeStatus} 为 AG-UI 的 resume 状态（{@code resolved} / {@code cancelled}），
 * 前端据此发起带 {@code resume} 的新一轮 {@code /agui/run}，从而“唤醒”被拦截的执行。
 *
 * @param interruptId AG-UI 的 interrupt id
 * @param status 平台侧状态（APPROVED / DENIED）
 * @param resumeStatus 供前端 resume 使用的状态
 */
public record ApprovalDecisionResponse(
        String interruptId, String status, String resumeStatus, Map<String, Object> resumePayload) {}
