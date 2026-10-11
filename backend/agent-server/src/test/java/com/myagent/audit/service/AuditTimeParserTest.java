package com.myagent.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.myagent.common.BizException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** Phase 12 / D27：审计时间参数容错（此前只传日期会 500）。 */
class AuditTimeParserTest {

    @Test
    void 只传日期时补齐当天起止() {
        OffsetDateTime start = AuditTimeParser.parseStart("2026-10-10");
        OffsetDateTime end = AuditTimeParser.parseEnd("2026-10-10");
        ZoneId zone = ZoneId.systemDefault();

        assertThat(start).isEqualTo(LocalDate.of(2026, 10, 10).atTime(LocalTime.MIN).atZone(zone).toOffsetDateTime());
        assertThat(end).isEqualTo(LocalDate.of(2026, 10, 10).atTime(LocalTime.MAX).atZone(zone).toOffsetDateTime());
        assertThat(start).isBefore(end);
    }

    @Test
    void 接受带偏移的完整ISO与本地日期时间() {
        assertThat(AuditTimeParser.parseStart("2026-10-10T08:00:00+08:00"))
                .isEqualTo(OffsetDateTime.parse("2026-10-10T08:00:00+08:00"));
        // 本地日期时间按服务端时区解释
        assertThat(AuditTimeParser.parseStart("2026-10-10T08:00:00"))
                .isEqualTo(OffsetDateTime.parse("2026-10-10T08:00:00+08:00"));
    }

    @Test
    void 空值与空白返回null() {
        assertThat(AuditTimeParser.parseStart(null)).isNull();
        assertThat(AuditTimeParser.parseStart("  ")).isNull();
        assertThat(AuditTimeParser.parseEnd(null)).isNull();
    }

    @Test
    void 非法输入返回业务错误而不是500() {
        assertThatThrownBy(() -> AuditTimeParser.parseStart("昨天"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("时间参数格式不正确");
        assertThatThrownBy(() -> AuditTimeParser.parseEnd("2026-13-99"))
                .isInstanceOf(BizException.class);
    }
}
