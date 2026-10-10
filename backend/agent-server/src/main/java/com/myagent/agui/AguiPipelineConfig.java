package com.myagent.agui;

import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapterFactory;
import io.agentscope.core.agui.processor.AguiRequestProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AG-UI 官方请求处理管线。
 *
 * <p>使用 {@link AguiRequestProcessor} 而不是手动 {@code adapter.run(...)}：
 * 只有走这条管线，AG-UI 的 {@code resume}（HITL 恢复）等语义才会被官方正确翻译
 * （实测：绕过管线时 agent 会报 “supplied no confirmation”）。
 */
@Configuration
public class AguiPipelineConfig {

    @Bean
    public AguiRequestProcessor aguiRequestProcessor(
            PlatformAgentResolver agentResolver,
            PlatformRuntimeContextResolver runtimeContextResolver,
            AguiAdapterConfig aguiAdapterConfig) {
        return AguiRequestProcessor.builder()
                .agentResolver(agentResolver)
                .runtimeContextResolver(runtimeContextResolver)
                .adapterFactory(AguiAgentAdapterFactory.defaultFactory())
                .config(aguiAdapterConfig)
                .build();
    }
}
