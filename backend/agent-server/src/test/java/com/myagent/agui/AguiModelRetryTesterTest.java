package com.myagent.agui;

import static org.assertj.core.api.Assertions.assertThat;

import com.myagent.modelconfig.probe.ModelRetryTester;
import org.junit.jupiter.api.Test;

/**
 * 重试自检：用内嵌假端点造一次"流到一半断"，验证官方重试链路真的会重试。
 *
 * <p>为什么值得跑真链路：重试是静默的，配置写错（比如 AgentFactory 把 executionConfig 盖空）
 * 从界面完全看不出来——上一版就是配 3 次只重试 2 次。这个测试直接盯最终行为。
 */
class AguiModelRetryTesterTest {

    /** 退避给得很小，测试才快。 */
    private static ModelFactory fastFactory() {
        return new ModelFactory(3, 50, 200, 2.0, 5, true, true);
    }

    @Test
    void 模拟断流会重试并拿到第二次的内容() {
        ModelRetryTester.TestResult result = new AguiModelRetryTester(fastFactory()).simulate();

        assertThat(result.ok()).as("重试后应成功，实际错误: %s", result.error()).isTrue();
        assertThat(result.failures()).as("第一次断流算一次可重试失败").isEqualTo(1);
        assertThat(result.retries()).isEqualTo(1);
        assertThat(result.sampleText()).contains("重试成功");
    }


    @Test
    void 策略视图反映配置值() {
        ModelRetryTester.PolicyView policy = new AguiModelRetryTester(fastFactory()).policy();

        assertThat(policy.maxRetries()).isEqualTo(3);
        assertThat(policy.initialBackoffMs()).isEqualTo(50);
        assertThat(policy.maxBackoffMs()).isEqualTo(200);
        assertThat(policy.timeoutMinutes()).isEqualTo(5);
        assertThat(policy.retryOnStreamFailure()).isTrue();
        assertThat(policy.summary()).contains("最多重试 3 次").contains("流式返回中途断连");
    }

    @Test
    void 重试关闭时断流会直接失败() {
        // maxRetries=0 → 框架 maxAttempts=1，第一次断流就结束
        ModelFactory noRetry = new ModelFactory(0, 10, 10, 1.0, 5, false, false);
        ModelRetryTester.TestResult result = new AguiModelRetryTester(noRetry).simulate();

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isNotBlank();
    }
}
