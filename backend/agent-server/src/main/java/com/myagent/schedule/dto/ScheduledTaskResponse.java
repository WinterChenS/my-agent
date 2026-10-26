package com.myagent.schedule.dto;

import java.time.OffsetDateTime;

/** 定时任务回显（含人类可读的计划描述与下次执行时间）。 */
public record ScheduledTaskResponse(
        Long id,
        String name,
        Boolean enabled,
        Long agentId,
        String agentName,
        String scheduleType,
        String cronExpr,
        Integer intervalMinutes,
        OffsetDateTime onceAt,
        String timezone,
        String scheduleText,
        String prompt,
        String targetType,
        Long chatSessionId,
        Long channelAppId,
        String peerKey,
        String status,
        String misfirePolicy,
        OffsetDateTime nextRunAt,
        OffsetDateTime lastRunAt,
        String lastError,
        Integer runCount,
        Integer consecutiveFailures,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
