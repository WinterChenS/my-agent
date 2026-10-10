package com.myagent.modelconfig.probe;

import java.util.Map;

/**
 * 模型连通性测试契约。
 *
 * <p>接口刻意放在 {@code modelconfig} 包，实现放在 {@code agui} 包：门禁 G2 要求配置类包不得引用
 * AgentScope 的 Java 包，因此真正调用 AgentScope 官方模型扩展的代码集中在一处。
 */
public interface ModelConnectivityProbe {

    /**
     * 发起一次最小补全，验证供应商可达且密钥有效。
     *
     * @param providerType 供应商类型
     * @param baseUrl 接口地址（可为空，使用扩展默认值）
     * @param apiKey 明文密钥
     * @param modelId 模型标识
     * @param params 采样参数（可为空）
     */
    ProbeResult probe(
            String providerType,
            String baseUrl,
            String apiKey,
            String modelId,
            Map<String, Object> params);

    /**
     * 测试结果。失败时 {@code error} 给出原因，接口本身仍返回 HTTP 200（由调用方包装为 Result）。
     *
     * @param ok 是否连通
     * @param latencyMs 端到端耗时（毫秒）
     * @param error 失败原因，成功时为 null
     */
    record ProbeResult(boolean ok, long latencyMs, String error) {}
}
