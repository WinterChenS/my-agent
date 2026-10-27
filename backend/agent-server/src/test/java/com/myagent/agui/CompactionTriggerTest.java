package com.myagent.agui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 压缩触发阈值：**按窗口比例算**（默认 90%），不再是固定 12 万。
 *
 * <p>用户实测反馈：模型配了 1,000,000 窗口，用量条却显示「窗口 1,000,000 / 触发阈值 120,000」，
 * 也就是用掉 11% 就开始压缩 —— 大窗口等于白买。原来的规则是「固定 120000 与窗口 60% 取小」，
 * 对 128k 窗口够用，对 1M 窗口就成了灾难。
 */
class CompactionTriggerTest {

    private static final int FALLBACK = 128_000;

    @Test
    void 大窗口按90percent触发_不再固定12万() {
        assertThat(AgentFactory.resolveCompactionTrigger(1_000_000, FALLBACK, 0.9, 0))
                .isEqualTo(900_000);
        assertThat(AgentFactory.resolveCompactionTrigger(200_000, FALLBACK, 0.9, 0))
                .isEqualTo(180_000);
    }

    @Test
    void 普通128k窗口仍是约90percent() {
        assertThat(AgentFactory.resolveCompactionTrigger(128_000, FALLBACK, 0.9, 0))
                .isEqualTo(115_200);
    }

    @Test
    void 没配窗口时用兜底窗口算() {
        assertThat(AgentFactory.resolveCompactionTrigger(null, FALLBACK, 0.9, 0))
                .isEqualTo(115_200);
        assertThat(AgentFactory.resolveCompactionTrigger(0, FALLBACK, 0.9, 0))
                .isEqualTo(115_200);
    }

    @Test
    void 比例可配置() {
        assertThat(AgentFactory.resolveCompactionTrigger(1_000_000, FALLBACK, 0.75, 0))
                .isEqualTo(750_000);
        // 非法比例退回 90%，避免配成 1.5 或 0 之后行为诡异
        assertThat(AgentFactory.resolveCompactionTrigger(1_000_000, FALLBACK, 1.5, 0))
                .isEqualTo(900_000);
        assertThat(AgentFactory.resolveCompactionTrigger(1_000_000, FALLBACK, 0, 0))
                .isEqualTo(900_000);
    }

    @Test
    void 绝对上限仍然生效_便于运维限成本() {
        assertThat(AgentFactory.resolveCompactionTrigger(1_000_000, FALLBACK, 0.9, 200_000))
                .isEqualTo(200_000);
        // 上限比算出来的大时不起作用
        assertThat(AgentFactory.resolveCompactionTrigger(128_000, FALLBACK, 0.9, 500_000))
                .isEqualTo(115_200);
    }

    @Test
    void 阈值不会超过窗口本身_也不会小到一上来就压() {
        assertThat(AgentFactory.resolveCompactionTrigger(1_000, FALLBACK, 0.9, 0)).isEqualTo(1_000);
        assertThat(AgentFactory.resolveCompactionTrigger(500, FALLBACK, 0.9, 0))
                .as("极小窗口也要留 1000 的下限，否则一开始就压缩").isEqualTo(1_000);
    }
}
