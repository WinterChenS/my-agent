package com.myagent.schedule.service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;
import org.springframework.scheduling.support.CronExpression;

/**
 * 定时计划的解析与下次时间计算（不引入新依赖：用 Spring 自带 {@link CronExpression}）。
 *
 * <p>支持三种：{@code CRON}（6 段 Spring 表达式，也接受 5 段标准 cron）、
 * {@code EVERY_MINUTES}（每 N 分钟）、{@code ONCE}（指定时间一次性）。
 *
 * <p>时区：任务行带 timezone，cron 按该时区解释；内部统一换算成 UTC 的 {@link OffsetDateTime} 存库。
 */
public final class ScheduleMath {

    public static final String TYPE_CRON = "CRON";
    public static final String TYPE_EVERY = "EVERY_MINUTES";
    public static final String TYPE_ONCE = "ONCE";

    private ScheduleMath() {}

    /** 校验并规范化 cron：5 段（分 时 日 月 周）自动补秒，6 段原样。 */
    public static String normalizeCron(String expr) {
        if (expr == null || expr.isBlank()) {
            throw new IllegalArgumentException("cron 表达式不能为空");
        }
        String trimmed = expr.trim().replaceAll("\\s+", " ");
        String[] parts = trimmed.split(" ");
        if (parts.length == 5) {
            return "0 " + trimmed;
        }
        if (parts.length != 6) {
            throw new IllegalArgumentException(
                    "cron 表达式需要 5 或 6 段（分 时 日 月 周 [秒]），实际 " + parts.length + " 段：" + expr);
        }
        return trimmed;
    }

    /** 校验 cron 语法（不规范会抛 {@link IllegalArgumentException}，带人能看懂的话）。 */
    public static void validateCron(String expr) {
        String normalized = normalizeCron(expr);
        try {
            CronExpression.parse(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("cron 表达式不合法: " + expr + "（" + ex.getMessage() + "）");
        }
    }

    /** 解析时区；非法则回退 Asia/Shanghai。 */
    public static ZoneId zone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return ZoneId.of("Asia/Shanghai");
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (Exception ex) {
            return ZoneId.of("Asia/Shanghai");
        }
    }

    /**
     * 计算「严格晚于 base」的下一次执行时间。
     *
     * @return null 表示不再有下次（ONCE 已用完）
     */
    public static OffsetDateTime next(
            String scheduleType,
            String cronExpr,
            Integer intervalMinutes,
            OffsetDateTime onceAt,
            String timezone,
            OffsetDateTime base) {
        ZoneId zone = zone(timezone);
        OffsetDateTime from = base == null ? OffsetDateTime.now() : base;
        String type = scheduleType == null ? "" : scheduleType.toUpperCase(Locale.ROOT);
        switch (type) {
            case TYPE_CRON -> {
                CronExpression cron = CronExpression.parse(normalizeCron(cronExpr));
                ZonedDateTime next = cron.next(from.atZoneSameInstant(zone));
                return next == null ? null : next.toOffsetDateTime();
            }
            case TYPE_EVERY -> {
                int minutes = intervalMinutes == null || intervalMinutes <= 0 ? 60 : intervalMinutes;
                return from.plusMinutes(minutes);
            }
            case TYPE_ONCE -> {
                if (onceAt == null) {
                    return null;
                }
                // 一次性任务：只要还没跑过就是它本身；已经晚于它就不再有下次
                return onceAt.isAfter(from) ? onceAt : null;
            }
            default -> throw new IllegalArgumentException("不支持的调度类型: " + scheduleType);
        }
    }

    /**
     * 补跑判定：{@code next_run_at} 已经落后很多时，决定「立即补一次」还是「跳到下一个」。
     *
     * <p>为什么要它：节点全挂两小时后恢复，不做处理会在上班瞬间被上百条任务淹没。
     *
     * @param overdueMinutes 落后超过多少分钟算"错过"（由调用方按任务粒度给，默认 5 分钟）
     */
    /**
     * 是否"落后太多"（超过宽限）：与策略无关，只判时间。
     *
     * <p>为什么要单独一个方法：{@code shouldCoalesce} 只在 COALESCE 策略下有意义，
     * 而 SKIP 策略同样需要判断"这次是不是错过了"。
     */
    public static boolean isOverdue(OffsetDateTime scheduledAt, OffsetDateTime now, int graceMinutes) {
        if (scheduledAt == null || now == null) {
            return false;
        }
        return scheduledAt.isBefore(now.minusMinutes(Math.max(1, graceMinutes)));
    }

    public static boolean shouldCoalesce(
            String misfirePolicy, OffsetDateTime scheduledAt, OffsetDateTime now, int overdueMinutes) {
        if (scheduledAt == null) {
            return false;
        }
        if (!"COALESCE".equalsIgnoreCase(misfirePolicy)) {
            return false;
        }
        return scheduledAt.isBefore(now.minusMinutes(Math.max(1, overdueMinutes)));
    }

    /**
     * 预览「接下来 N 次执行时间」（管理页配置时用来验证表达式是否符合预期）。
     *
     * <p>这就是"配置要真的生效"的可验证一环：保存前把未来几次时间给用户看，
     * 写错了立刻能发现（而不是等它半夜跑错时间）。
     */
    public static java.util.List<OffsetDateTime> nextRuns(
            String scheduleType,
            String cronExpr,
            Integer intervalMinutes,
            OffsetDateTime onceAt,
            String timezone,
            OffsetDateTime base,
            int count) {
        java.util.List<OffsetDateTime> result = new java.util.ArrayList<>();
        OffsetDateTime cursor = base == null ? OffsetDateTime.now() : base;
        int limit = Math.min(Math.max(count, 1), 20);
        for (int i = 0; i < limit; i++) {
            OffsetDateTime next = next(scheduleType, cronExpr, intervalMinutes, onceAt, timezone, cursor);
            if (next == null) {
                break;
            }
            result.add(next);
            cursor = next;
            // 一次性只会有一次
            if (TYPE_ONCE.equalsIgnoreCase(scheduleType)) {
                break;
            }
        }
        return result;
    }

    /** 方便描述：人类可读的计划摘要（管理页/工具返回用）。 */
    public static String describe(
            String scheduleType, String cronExpr, Integer intervalMinutes, OffsetDateTime onceAt,
            String timezone) {
        String type = scheduleType == null ? "" : scheduleType.toUpperCase(Locale.ROOT);
        return switch (type) {
            case TYPE_CRON -> "cron: " + (cronExpr == null ? "" : cronExpr) + "（" + zone(timezone) + "）";
            case TYPE_EVERY -> "每 " + (intervalMinutes == null ? 60 : intervalMinutes) + " 分钟";
            case TYPE_ONCE -> "一次性: " + (onceAt == null ? "-" : onceAt);
            default -> type;
        };
    }
}
