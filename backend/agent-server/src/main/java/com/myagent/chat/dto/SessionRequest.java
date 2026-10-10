package com.myagent.chat.dto;

import jakarta.validation.constraints.NotNull;

/** 新建会话请求。 */
public record SessionRequest(
        @NotNull(message = "agentId 不能为空") Long agentId,
        String title,
        String threadId) {}
