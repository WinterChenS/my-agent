package com.myagent.modelconfig.probe;

import java.util.Map;

/**
 * 「模型重试自检」端口（放在 modelconfig 侧，实现放到 {@code agui} 包以满足门禁 G2：
 * AgentScope 类型只允许出现在 {@code agui} 包）。
 *
 * <p>为什么需要它：重试是静默的，用户看不到也无法验证（有过"到底加没加"的反馈）。
 * 这里把"当前重试策略"和"重试链路是否真的生效"做成管理台里可以点的自检。
 */
public interface ModelRetryTester {

    /** 当前生效的重试策略（来自 application.yml 的 myagent.model.retry.*）。 */
    PolicyView policy();

    /**
     * 用真实模型发一次最小请求，记录过程中发生了几次失败（=重试次数）。
     *
     * @return 结果；失败不抛异常，包在结果里
     */
    TestResult liveTest(
            String providerType,
            String baseUrl,
            String apiKey,
            String modelId,
            Map<String, Object> params);

    /**
     * 模拟断流自检：本地起一个假端点，第 1 次请求流出半句后断开，第 2 次正常返回。
     * 用来验证"重试链路本身"是否工作（不依赖真实模型，也不会真的失败）。
     */
    TestResult simulate();

    /** 重试策略视图。 */
    record PolicyView(
            int maxRetries,
            long initialBackoffMs,
            long maxBackoffMs,
            double multiplier,
            long timeoutMinutes,
            boolean retryOnUnknownStatus,
            boolean retryOnStreamFailure,
            String summary) {}

    /**
     * 自检结果。
     *
     * @param ok 最终是否成功
     * @param failures 被判定为"可重试"的失败次数
     * @param retries 实际重试次数（= 失败次数 - 1，最后那次失败不重试）
     * @param elapsedMs 总耗时
     * @param sampleText 拿到的回答片段（成功时）
     * @param error 失败原因（失败时）
     */
    record TestResult(
            boolean ok,
            int failures,
            int retries,
            long elapsedMs,
            String sampleText,
            String error) {}
}
