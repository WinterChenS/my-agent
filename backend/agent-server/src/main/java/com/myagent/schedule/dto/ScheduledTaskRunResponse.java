package com.myagent.schedule.dto;

import java.time.OffsetDateTime;

/** 一次执行记录。 */
public record ScheduledTaskRunResponse(
        Long id,
        Long taskId,
        String nodeId,
        String triggerType,
        String status,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        Long durationMs,
        String outputPreview,
        String error) {}
