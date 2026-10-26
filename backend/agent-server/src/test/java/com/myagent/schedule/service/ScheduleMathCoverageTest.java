package com.myagent.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * 计划计算：三种类型（cron / 间隔 / 一次性）全覆盖，含边界。
 *
 * <p>为什么要把边界写死：cron 的"下一天/下个月/跨时区"、"一次性已过期"、
 * "间隔为 0"这些都是只会在线上踩到的角落，单测必须钉住。
 */
class ScheduleMathCoverageTest {

    private static final OffsetDateTime BASE =
            OffsetDateTime.of(2026, 3, 10, 8, 0, 0, 0, ZoneOffset.ofHours(8));

    // ===================== CRON =====================

    @Test
    void cron_每5分钟_从整点算下一次() {
        // 8:00 的下一次 5 分钟对齐是 8:05
        assertThat(ScheduleMath.next(ScheduleMath.TYPE_CRON, "0 */5 * * * *", null, null,
                "Asia/Shanghai", BASE))
                .isEqualTo(BASE.plusMinutes(5));
        // 8:03 的下一次仍是 8:05
        assertThat(ScheduleMath.next(ScheduleMath.TYPE_CRON, "0 */5 * * * *", null, null,
                "Asia/Shanghai", BASE.plusMinutes(3)))
                .isEqualTo(BASE.plusMinutes(5));
    }

    @Test
    void cron_每天9点_当天未到与已过的分界() {
        assertThat(ScheduleMath.next(ScheduleMath.TYPE_CRON, "0 9 * * *", null, null,
                "Asia/Shanghai", BASE.minusMinutes(1)))
                .isEqualTo(BASE.plusHours(1));
        // 正好 9:00 时，下一次必须是"明天 9 点"（严格晚于）
        OffsetDateTime nine = BASE.plusHours(1);
        assertThat(ScheduleMath.next(ScheduleMath.TYPE_CRON, "0 9 * * *", null, null,
                "Asia/Shanghai", nine))
                .isEqualTo(BASE.plusDays(1).plusHours(1));
    }

    @Test
    void cron_每周一8点半_跨周计算() {
        // 2026-03-10 是周二；下一次周一 8:30 是 2026-03-16
        OffsetDateTime next = ScheduleMath.next(ScheduleMath.TYPE_CRON, "30 8 * * 1", null, null,
                "Asia/Shanghai", BASE);
        assertThat(next.getDayOfWeek().getValue()).isEqualTo(1);
        assertThat(next.getHour()).isEqualTo(8);
        assertThat(next.getMinute()).isEqualTo(30);
        assertThat(next).isAfter(BASE);
    }

    @Test
    void cron_每月1号_跨月计算() {
        OffsetDateTime endOfMonth = OffsetDateTime.of(2026, 1, 31, 12, 0, 0, 0, ZoneOffset.ofHours(8));
        OffsetDateTime next = ScheduleMath.next(ScheduleMath.TYPE_CRON, "0 0 1 * *", null, null,
                "Asia/Shanghai", endOfMonth);
        assertThat(next.getMonthValue()).isEqualTo(2);
        assertThat(next.getDayOfMonth()).isEqualTo(1);
    }

    @Test
    void cron_时区会影响绝对时刻() {
        // 同样"每天 9 点"，上海与 UTC 的绝对时刻差 8 小时
        OffsetDateTime shanghai = ScheduleMath.next(ScheduleMath.TYPE_CRON, "0 9 * * *", null, null,
                "Asia/Shanghai", BASE);
        OffsetDateTime utc = ScheduleMath.next(ScheduleMath.TYPE_CRON, "0 9 * * *", null, null,
                "UTC", BASE);
        assertThat(shanghai.toInstant()).isNotEqualTo(utc.toInstant());
        assertThat(shanghai.atZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai")).getHour())
                .isEqualTo(9);
        assertThat(utc.atZoneSameInstant(ZoneIdHolder.UTC).getHour()).isEqualTo(9);
    }

    @Test
    void cron_不存在的日期不会崩() {
        // 2 月 30 日永远不触发 → next 应为 null 而不是抛异常
        assertThat(ScheduleMath.next(ScheduleMath.TYPE_CRON, "0 0 30 2 *", null, null,
                "Asia/Shanghai", BASE)).isNull();
    }

    @Test
    void cron_语法错误报错信息可读() {
        assertThatThrownBy(() -> ScheduleMath.validateCron("not a cron"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScheduleMath.validateCron("0 25 * * *"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不合法");
    }

    // ===================== EVERY_MINUTES =====================

    @Test
    void every_按给定分钟顺延() {
        assertThat(ScheduleMath.next(ScheduleMath.TYPE_EVERY, null, 30, null, null, BASE))
                .isEqualTo(BASE.plusMinutes(30));
        assertThat(ScheduleMath.next(ScheduleMath.TYPE_EVERY, null, 1440, null, null, BASE))
                .isEqualTo(BASE.plusDays(1));
    }

    @Test
    void every_缺少或非法间隔回退到60分钟() {
        assertThat(ScheduleMath.next(ScheduleMath.TYPE_EVERY, null, null, null, null, BASE))
                .isEqualTo(BASE.plusMinutes(60));
        assertThat(ScheduleMath.next(ScheduleMath.TYPE_EVERY, null, 0, null, null, BASE))
                .isEqualTo(BASE.plusMinutes(60));
        assertThat(ScheduleMath.next(ScheduleMath.TYPE_EVERY, null, -5, null, null, BASE))
                .isEqualTo(BASE.plusMinutes(60));
    }

    // ===================== ONCE =====================

    @Test
    void once_未来时间保持原值() {
        OffsetDateTime at = BASE.plusHours(3);
        assertThat(ScheduleMath.next(ScheduleMath.TYPE_ONCE, null, null, at, null, BASE))
                .isEqualTo(at);
    }

    @Test
    void once_刚好等于基准时间视为已过期() {
        assertThat(ScheduleMath.next(ScheduleMath.TYPE_ONCE, null, null, BASE, null, BASE)).isNull();
        assertThat(ScheduleMath.next(ScheduleMath.TYPE_ONCE, null, null, BASE.minusSeconds(1), null, BASE))
                .isNull();
    }

    @Test
    void once_缺时间返回null() {
        assertThat(ScheduleMath.next(ScheduleMath.TYPE_ONCE, null, null, null, null, BASE)).isNull();
    }

    // ===================== 通用 =====================

    @Test
    void 未知类型抛错而不是静默() {
        assertThatThrownBy(() -> ScheduleMath.next("HOURLY", null, null, null, null, BASE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的调度类型");
    }

    @Test
    void 基准为空时以当前时间为准() {
        OffsetDateTime next = ScheduleMath.next(ScheduleMath.TYPE_EVERY, null, 5, null, null, null);
        assertThat(next).isAfter(OffsetDateTime.now().plusMinutes(4));
    }

    @Test
    void 错过判定_边界分钟() {
        OffsetDateTime now = BASE;
        // 落后 4 分 59 秒：还在宽限内，不补跑
        assertThat(ScheduleMath.shouldCoalesce("COALESCE", now.minusMinutes(4).minusSeconds(59), now, 5))
                .isFalse();
        // 落后 5 分 1 秒：算错过
        assertThat(ScheduleMath.shouldCoalesce("COALESCE", now.minusMinutes(5).minusSeconds(1), now, 5))
                .isTrue();
        // scheduledAt 为空 → 不算错过
        assertThat(ScheduleMath.shouldCoalesce("COALESCE", null, now, 5)).isFalse();
    }

    @Test
    void 描述_三种类型都可读() {
        assertThat(ScheduleMath.describe(ScheduleMath.TYPE_CRON, "0 9 * * *", null, null, "UTC"))
                .contains("0 9 * * *").contains("UTC");
        assertThat(ScheduleMath.describe(ScheduleMath.TYPE_EVERY, null, 5, null, null))
                .contains("每 5 分钟");
        assertThat(ScheduleMath.describe(ScheduleMath.TYPE_ONCE, null, null, BASE, null))
                .contains("一次性");
    }

    /** 便于在断言里表达 UTC。 */
    private static final class ZoneIdHolder {
        static final java.time.ZoneId UTC = ZoneOffset.UTC;
    }
}
