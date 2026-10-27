package com.myagent.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 计划预览：管理页"接下来 N 次"必须与真实调度一致。 */
class SchedulePreviewTest {

    private static final OffsetDateTime BASE =
            OffsetDateTime.of(2026, 3, 10, 8, 0, 0, 0, ZoneOffset.ofHours(8));

    @Test
    void cron预览给出连续5次且严格递增() {
        List<OffsetDateTime> runs = ScheduleMath.nextRuns(
                ScheduleMath.TYPE_CRON, "0 9 * * *", null, null, "Asia/Shanghai", BASE, 5);

        assertThat(runs).hasSize(5);
        for (int i = 1; i < runs.size(); i++) {
            assertThat(runs.get(i)).isAfter(runs.get(i - 1));
            assertThat(runs.get(i).toLocalTime().getHour()).isEqualTo(9);
            assertThat(runs.get(i).toLocalTime().getMinute()).isZero();
        }
        // 每天一次：相邻间隔 24 小时
        assertThat(java.time.Duration.between(runs.get(0), runs.get(1)).toHours()).isEqualTo(24);
    }

    @Test
    void 间隔任务预览按步长递增() {
        List<OffsetDateTime> runs = ScheduleMath.nextRuns(
                ScheduleMath.TYPE_EVERY, null, 30, null, "Asia/Shanghai", BASE, 3);

        assertThat(runs).hasSize(3);
        assertThat(runs.get(0)).isEqualTo(BASE.plusMinutes(30));
        assertThat(runs.get(2)).isEqualTo(BASE.plusMinutes(90));
    }

    @Test
    void 一次性只预览一条() {
        OffsetDateTime at = BASE.plusHours(2);
        List<OffsetDateTime> runs = ScheduleMath.nextRuns(
                ScheduleMath.TYPE_ONCE, null, null, at, "Asia/Shanghai", BASE, 5);

        assertThat(runs).containsExactly(at);
    }

    @Test
    void 数量上限与下限都被夹住() {
        assertThat(ScheduleMath.nextRuns(ScheduleMath.TYPE_EVERY, null, 1, null, null, BASE, 0))
                .hasSize(1);
        assertThat(ScheduleMath.nextRuns(ScheduleMath.TYPE_EVERY, null, 1, null, null, BASE, 999))
                .hasSize(20);
    }

    @Test
    void 永不触发的表达式预览为空而不是报错() {
        assertThat(ScheduleMath.nextRuns(
                ScheduleMath.TYPE_CRON, "0 0 30 2 *", null, null, "Asia/Shanghai", BASE, 5))
                .isEmpty();
    }
}
