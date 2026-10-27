package com.myagent.agui;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.model.ExecutionConfig;
import java.io.IOException;
import java.net.ConnectException;
import org.junit.jupiter.api.Test;

/**
 * 模型重试判定：用官方 {@link ExecutionConfig}，只在其谓词上补"无状态码的传输失败"。
 *
 * <p>为什么不能只依赖官方默认：{@code isRetryableHttpStatus()} 对 {@code statusCode == null}
 * 返回 false，而"连不上 / 流被对端关闭"恰恰是最常见的抖动（实测 ConnectException 完全不重试）。
 */
class ModelRetryConfigTest {

    /** 模拟"有状态码"的模型异常（OpenAIException 就 implements ModelHttpException）。 */
    private static final class FakeHttpError extends RuntimeException
            implements io.agentscope.core.model.ModelHttpException {
        private final Integer status;

        FakeHttpError(Integer status) {
            this(status, "fake model error " + status);
        }

        FakeHttpError(Integer status, String message) {
            super(message);
            this.status = status;
        }

        @Override
        public Integer getStatusCode() {
            return status;
        }
    }

    @Test
    void 无状态码的模型错误被视为可重试() {
        assertThat(ModelFactory.isStatuslessModelError(new FakeHttpError(null))).isTrue();
        assertThat(ModelFactory.isStatuslessModelError(new ConnectException("Connection refused"))).isTrue();
        // 纯 IOException 由官方谓词负责，不属于本补丁范围（避免重复/越界判断）
        assertThat(ModelFactory.isStatuslessModelError(new IOException("closed"))).isFalse();
        assertThat(io.agentscope.core.model.ExecutionConfig.RETRYABLE_ERRORS.test(new IOException("closed")))
                .as("官方谓词自己覆盖 IOException").isTrue();
    }

    @Test
    void 有状态码的错误交给官方谓词判断() {
        // 400 不该被我们的"补丁"判成可重试（它是确定性错误，重试没意义）
        assertThat(ModelFactory.isStatuslessModelError(new FakeHttpError(400))).isFalse();
        // 而被包装在 cause 里的连不上，仍应识别出来
        assertThat(ModelFactory.isStatuslessModelError(
                new RuntimeException("wrap", new ConnectException("refused")))).isTrue();
    }

    @Test
    void 流到一半断掉视为可重试_哪怕被包成400() {
        // 真实实测的原话：deepseek 代理中途断流，官方扩展包成 BadRequestException(400)
        var midStream = new FakeHttpError(400,
                "OpenAI API error in streaming response: deepseek-日抛: connection lost mid-reply (unexpected EOF)");
        assertThat(ModelFactory.isTransientStreamFailure(midStream)).isTrue();
        assertThat(ModelFactory.isTransientStreamFailure(new RuntimeException("w", midStream))).isTrue();
        assertThat(ModelFactory.isTransientStreamFailure(new java.io.IOException("connection reset"))).isTrue();
        assertThat(ModelFactory.isTransientStreamFailure(new java.io.IOException("unexpected EOF"))).isTrue();
    }

    @Test
    void 真正的400不被当作可重试() {
        // 官方的通用包装："HTTP transport error during streaming: HTTP request failed with status 400 | ..."
        // 里面带 "during streaming"，若拿它当判据会把所有 400 都重试（实测踩过）
        var real400 = new FakeHttpError(400,
                "HTTP transport error during streaming: HTTP request failed with status 400"
                        + " | {\"error\": {\"message\": \"invalid request: messages must not be empty\"}}");
        assertThat(ModelFactory.isTransientStreamFailure(real400)).isFalse();
        assertThat(ModelFactory.isTransientStreamFailure(new FakeHttpError(400, "invalid api key"))).isFalse();
        assertThat(ModelFactory.isTransientStreamFailure(new IllegalArgumentException("bad input"))).isFalse();
    }

    @Test
    void 官方默认值与我们的默认值一致_都是3次() {
        // 官方 ExecutionConfig.MODEL_DEFAULTS：maxAttempts=3 / 初始 2s / 上限 30s / x2
        assertThat(ExecutionConfig.MODEL_DEFAULTS.getMaxAttempts()).isEqualTo(3);
        assertThat(ExecutionConfig.MODEL_DEFAULTS.getInitialBackoff().toSeconds()).isEqualTo(2);
        assertThat(ExecutionConfig.MODEL_DEFAULTS.getMaxBackoff().toSeconds()).isEqualTo(30);
    }

    @Test
    void 构造时按配置生成重试策略() {
        // max-retries=3 → 框架 maxAttempts=4（首次 + 3 次重试）
        ModelFactory factory = new ModelFactory(3, 500, 5000, 3.0, 2, true, true);
        io.agentscope.core.model.GenerateOptions options = invokeBuildOptions(factory);

        ExecutionConfig config = options.getExecutionConfig();
        assertThat(config.getMaxAttempts()).as("max-retries=3 → maxAttempts=4").isEqualTo(4);
        assertThat(config.getInitialBackoff().toMillis()).isEqualTo(500);
        assertThat(config.getMaxBackoff().toMillis()).isEqualTo(5000);
        assertThat(config.getBackoffMultiplier()).isEqualTo(3.0);
        assertThat(config.getTimeout().toMinutes()).isEqualTo(2);
        // 无状态码的失败也应命中（我们补的谓词）
        assertThat(config.getRetryOn().test(new ConnectException("refused"))).isTrue();
    }

    private static io.agentscope.core.model.GenerateOptions invokeBuildOptions(ModelFactory factory) {
        try {
            var method = ModelFactory.class.getDeclaredMethod("buildOptions", java.util.Map.class);
            method.setAccessible(true);
            return (io.agentscope.core.model.GenerateOptions) method.invoke(factory, new java.util.HashMap<>());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
