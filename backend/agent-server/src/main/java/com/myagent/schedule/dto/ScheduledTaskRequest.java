package com.myagent.schedule.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;

/**
 * 定时任务新建/更新请求。
 *
 * @param scheduleType CRON / EVERY_MINUTES / ONCE
 * @param cronExpr 5 或 6 段 cron（CRON 必填）
 * @param intervalMinutes 间隔分钟（EVERY_MINUTES 必填）
 * @param onceAt 一次性执行时间（ONCE 必填）
 * @param targetType SESSION（写回会话）/ IM（推到钉钉等）/ NONE（只记录）
 * @param chatSessionId 运行与写回的会话（决定 threadId → 记忆连续）
 * @param channelAppId 显式 IM 渠道（不填则用会话对应的 peer）
 * @param peerKey 显式 IM 接收方
 * @param misfirePolicy COALESCE（补跑一次）/ SKIP（跳过）
 */
public record ScheduledTaskRequest(
        @NotBlank(message = "name 不能为空") String name,
        @NotNull(message = "agentId 不能为空") Long agentId,
        @NotBlank(message = "scheduleType 不能为空") String scheduleType,
        String cronExpr,
        Integer intervalMinutes,
        OffsetDateTime onceAt,
        String timezone,
        @NotBlank(message = "prompt 不能为空") String prompt,
        String targetType,
        Long chatSessionId,
        Long channelAppId,
        String peerKey,
        String misfirePolicy) {}
