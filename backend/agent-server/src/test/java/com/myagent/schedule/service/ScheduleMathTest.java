package com.myagent.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** 定时计划计算：cron / 间隔 / 一次性 / 错过补偿。 */
class ScheduleMathTest {

    private static final OffsetDateTime BASE =
            OffsetDateTime.of(2026, 3, 10, 8, 0, 0, 0, ZoneOffset.ofHours(8));

    @Test
    void 五段cron会自动补秒() {
        assertThat(ScheduleMath.normalizeCron("0 9 * * *")).isEqualTo("0 0 9 * * *");
        assertThat(ScheduleMath.normalizeCron("0 0 9 * * ?")).isEqualTo("0 0 9 * * ?");
    }

    @Test
    void 段数不对会报错且提示清楚() {
        assertThatThrownBy(() -> ScheduleMath.normalizeCron("0 9 *"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5 或 6 段");
        assertThatThrownBy(() -> ScheduleMath.validateCron("0 0 9 * * NOT_A_DAY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不合法");
    }

    @Test
    void 每天9点的下一次是当天9点() {
        OffsetDateTime next = ScheduleMath.next(
                ScheduleMath.TYPE_CRON, "0 9 * * *", null, null, "Asia/Shanghai", BASE);

        assertThat(next).isEqualTo(BASE.plusHours(1));
    }

    @Test
    void 已经过了9点则顺延到明天() {
        OffsetDateTime afterNine = BASE.plusHours(2);

        OffsetDateTime next = ScheduleMath.next(
                ScheduleMath.TYPE_CRON, "0 9 * * *", null, null, "Asia/Shanghai", afterNine);

        assertThat(next).isEqualTo(BASE.plusDays(1).plusHours(1));
    }

    @Test
    void 间隔任务按分钟顺延() {
        OffsetDateTime next = ScheduleMath.next(
                ScheduleMath.TYPE_EVERY, null, 15, null, "Asia/Shanghai", BASE);

        assertThat(next).isEqualTo(BASE.plusMinutes(15));
    }

    @Test
    void 一次性任务只在未来才有效() {
        OffsetDateTime future = BASE.plusMinutes(30);
        assertThat(ScheduleMath.next(
                ScheduleMath.TYPE_ONCE, null, null, future, "Asia/Shanghai", BASE))
                .isEqualTo(future);
        // 已经过了时间 → 不再有下次
        assertThat(ScheduleMath.next(
                ScheduleMath.TYPE_ONCE, null, null, BASE.minusMinutes(1), "Asia/Shanghai", BASE))
                .isNull();
    }

    @Test
    void 非法时区退回上海() {
        assertThat(ScheduleMath.zone("Mars/Olympus").getId()).isEqualTo("Asia/Shanghai");
        assertThat(ScheduleMath.zone("UTC").getId()).isEqualTo("UTC");
    }

    @Test
    void 错过判定只看策略与落后时长() {
        OffsetDateTime now = BASE;
        OffsetDateTime longAgo = BASE.minusHours(2);

        // COALESCE：落后超过阈值才算错过（需要补跑）
        assertThat(ScheduleMath.shouldCoalesce("COALESCE", longAgo, now, 5)).isTrue();
        assertThat(ScheduleMath.shouldCoalesce("COALESCE", BASE.minusMinutes(1), now, 5)).isFalse();
        // SKIP 永不补跑
        assertThat(ScheduleMath.shouldCoalesce("SKIP", longAgo, now, 5)).isFalse();
        assertThat(ScheduleMath.shouldCoalesce(null, longAgo, now, 5)).isFalse();
    }

    @Test
    void 描述文本人类可读() {
        assertThat(ScheduleMath.describe(
                ScheduleMath.TYPE_CRON, "0 9 * * *", null, null, "Asia/Shanghai"))
                .contains("cron").contains("0 9 * * *");
        assertThat(ScheduleMath.describe(
                ScheduleMath.TYPE_EVERY, null, 30, null, "Asia/Shanghai")).contains("30 分钟");
        assertThat(ScheduleMath.describe(
                ScheduleMath.TYPE_ONCE, null, null, BASE, "Asia/Shanghai")).contains("一次性");
    }
}
