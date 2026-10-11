package com.myagent.audit.service;

import com.myagent.common.BizException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * 审计时间参数解析（Phase 12 / D27）。
 *
 * <p>此前接口直接绑 {@code OffsetDateTime}，只传日期（如 {@code 2026-10-10}）会被 Spring 判为类型不匹配而 500。
 * 这里统一放宽：接受
 * <ul>
 *   <li>{@code yyyy-MM-dd}（日期）——按服务端时区补齐：start → 当天 00:00:00，end → 当天 23:59:59.999999999</li>
 *   <li>不带偏移的本地日期时间（{@code yyyy-MM-ddTHH:mm:ss}）——按服务端时区解释</li>
 *   <li>完整带偏移的 ISO-8601（{@code 2026-10-10T00:00:00+08:00}）</li>
 * </ul>
 * 其它一律返回**业务错误（400）**，不再 500。
 */
public final class AuditTimeParser {

    private AuditTimeParser() {
        // 工具类
    }

    /** 起始时间：日期形式补齐到当天 00:00:00。 */
    public static OffsetDateTime parseStart(String raw) {
        return parse(raw, true);
    }

    /** 结束时间：日期形式补齐到当天 23:59:59.999999999。 */
    public static OffsetDateTime parseEnd(String raw) {
        return parse(raw, false);
    }

    private static OffsetDateTime parse(String raw, boolean startOfDay) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        try {
            if (text.length() == 10) {
                LocalDate date = LocalDate.parse(text);
                LocalTime time = startOfDay ? LocalTime.MIN : LocalTime.MAX;
                return date.atTime(time).atZone(ZoneId.systemDefault()).toOffsetDateTime();
            }
            try {
                return OffsetDateTime.parse(text);
            } catch (DateTimeParseException notOffset) {
                return LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toOffsetDateTime();
            }
        } catch (DateTimeParseException ex) {
            throw BizException.badRequest(
                    "时间参数格式不正确（支持 yyyy-MM-dd 或 ISO-8601 日期时间）: " + raw);
        }
    }
}
