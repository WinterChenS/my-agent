package com.myagent.agui;

import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.encoder.AguiEventEncoder;
import io.agentscope.core.agui.runtime.AguiRequestBodyParser;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AG-UI 官方组件的装配。
 *
 * <p>门禁 G8：解析、适配、编码全部使用 {@code agentscope-extensions-agui} 的官方实现，
 * 不自行实现 AG-UI 协议或 SSE 帧格式。
 */
@Configuration
public class AguiConfig {

    @Bean
    public AguiRequestBodyParser aguiRequestBodyParser() {
        return new AguiRequestBodyParser();
    }

    @Bean
    public AguiEventEncoder aguiEventEncoder() {
        return new AguiEventEncoder();
    }

    /**
     * 适配器配置。
     *
     * <p>开启工具参数与 token 用量事件：前者供前端工具卡渲染，后者供 Phase 8 的 Token 审计消费。
     *
     * <p>{@code enableReasoning(true)}：把模型的思考过程（{@code ThinkingBlock}）转成 AG-UI 的
     * {@code REASONING_*} 事件。官方默认是 **false**，开启前思考内容根本不会进 SSE——
     * 前端就算写了渲染逻辑也永远收不到东西（实测确认过这条链路）。
     * 只有 reasoning 类模型（deepseek-reasoner / qwen3 等）才会真的产出内容。
     */
    @Bean
    public AguiAdapterConfig aguiAdapterConfig() {
        return AguiAdapterConfig.builder()
                .emitToolCallArgs(true)
                .emitStateEvents(true)
                .emitTokenUsage(true)
                .emitRunFinishedAfterError(true)
                .enableReasoning(true)
                .runTimeout(Duration.ofMinutes(10))
                .build();
    }
}
