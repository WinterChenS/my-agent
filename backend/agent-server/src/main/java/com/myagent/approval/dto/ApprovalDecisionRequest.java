package com.myagent.approval.dto;

import jakarta.validation.constraints.NotBlank;

/** 审批决定请求。 */
public record ApprovalDecisionRequest(
        @NotBlank(message = "decision 不能为空") String decision,
        String reason) {}
