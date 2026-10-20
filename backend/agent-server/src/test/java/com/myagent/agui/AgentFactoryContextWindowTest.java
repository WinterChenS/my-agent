package com.myagent.agui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 上下文窗口的解析规则：**模型配了就用配的，没配（或配了非正数）就用兜底值**。
 *
 * <p>这个值会作为前端「上下文用量」的分母 —— 此前响应里只有压缩触发阈值，
 * 模型配置的上下文长度在界面上完全体现不出来。
 */
class AgentFactoryContextWindowTest {

    @Test
    void 配置了窗口就用配置的() {
        assertThat(AgentFactory.resolveContextWindow(200_000, 128_000)).isEqualTo(200_000);
        assertThat(AgentFactory.resolveContextWindow(32_000, 128_000)).isEqualTo(32_000);
    }

    @Test
    void 没配或配了非正数就用兜底() {
        assertThat(AgentFactory.resolveContextWindow(null, 128_000)).isEqualTo(128_000);
        assertThat(AgentFactory.resolveContextWindow(0, 128_000)).isEqualTo(128_000);
        assertThat(AgentFactory.resolveContextWindow(-5, 128_000)).isEqualTo(128_000);
    }
}
