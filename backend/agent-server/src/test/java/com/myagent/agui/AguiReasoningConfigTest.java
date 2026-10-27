package com.myagent.agui;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 回归：{@code enableReasoning} 必须是开的。
 *
 * <p>官方 {@code ThinkingBlockEventConverter} 第一句就是
 * {@code if (!config.isEnableReasoning()) return;}，默认值是 false —— 一旦被改回去，
 * 模型再会思考，前端也永远收不到 {@code REASONING_*} 事件，而且**不报错、不打日志**。
 */
@SpringBootTest
@ActiveProfiles("test")
class AguiReasoningConfigTest {

    @Autowired
    private AguiAdapterConfig config;

    @Test
    void 思考过程事件必须开启() {
        assertThat(config.isEnableReasoning()).isTrue();
    }
}
