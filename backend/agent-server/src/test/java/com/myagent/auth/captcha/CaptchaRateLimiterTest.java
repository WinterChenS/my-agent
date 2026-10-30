package com.myagent.auth.captcha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.myagent.common.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 限流与渐进式冷却。 */
class CaptchaRateLimiterTest {

    private CaptchaRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new CaptchaRateLimiter();
        ReflectionTestUtils.setField(limiter, "createPerMinuteSession", 2);
        ReflectionTestUtils.setField(limiter, "createPerMinuteIp", 100);
        ReflectionTestUtils.setField(limiter, "verifyPerMinuteSession", 3);
        ReflectionTestUtils.setField(limiter, "verifyPerMinuteIp", 100);
        ReflectionTestUtils.setField(limiter, "cooldownThreshold", 3);
        ReflectionTestUtils.setField(limiter, "cooldownBaseSeconds", 10);
        ReflectionTestUtils.setField(limiter, "cooldownMaxSeconds", 300);
        ReflectionTestUtils.setField(limiter, "failWindowSeconds", 300);
    }

    @Test
    @DisplayName("会话维度出题限流：超过阈值 429")
    void createLimitedPerSession() {
        limiter.checkCreate("s-1", "1.1.1.1");
        limiter.checkCreate("s-1", "1.1.1.1");

        assertThatThrownBy(() -> limiter.checkCreate("s-1", "1.1.1.1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("频繁");
        // 换个会话不受影响（不误伤别人）
        limiter.checkCreate("s-2", "1.1.1.1");
    }

    @Test
    @DisplayName("IP 只做辅助：阈值明显更宽，不易误伤 NAT 用户")
    void ipLimitIsLooser() {
        ReflectionTestUtils.setField(limiter, "createPerMinuteIp", 2);
        limiter.checkCreate("a", "9.9.9.9");
        limiter.checkCreate("b", "9.9.9.9");
        assertThatThrownBy(() -> limiter.checkCreate("c", "9.9.9.9"))
                .isInstanceOf(BizException.class);
        // 另一个 IP 正常
        limiter.checkCreate("d", "8.8.8.8");
    }

    @Test
    @DisplayName("连续失败到阈值后进入渐进式冷却，且等待时间随失败次数增长")
    void progressiveCooldown() {
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure("s-1");
        }
        long first = limiter.cooldownRemainingSeconds("s-1");
        assertThat(first).isGreaterThan(0);

        for (int i = 0; i < 3; i++) {
            limiter.recordFailure("s-1");
        }
        long later = limiter.cooldownRemainingSeconds("s-1");
        assertThat(later).isGreaterThanOrEqualTo(first);
        assertThat(later).isLessThanOrEqualTo(300);

        assertThatThrownBy(() -> limiter.checkVerify("s-1", "1.1.1.1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("秒后再试");
    }

    @Test
    @DisplayName("成功后清空失败计数：正常用户不会被自己之前的错误长期拖累")
    void successResetsFailures() {
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("s-1");
        }
        assertThat(limiter.cooldownRemainingSeconds("s-1")).isGreaterThan(0);

        limiter.recordSuccess("s-1");
        assertThat(limiter.cooldownRemainingSeconds("s-1")).isZero();
    }

    @Test
    @DisplayName("失败次数没到阈值不冷却（避免正常用户输错一次就被罚）")
    void noCooldownBelowThreshold() {
        limiter.recordFailure("s-1");
        limiter.recordFailure("s-1");
        assertThat(limiter.cooldownRemainingSeconds("s-1")).isZero();
    }

    @Test
    @DisplayName("清理不会影响仍在窗口内的计数")
    void cleanupKeepsRecentWindows() {
        limiter.checkCreate("s-1", "1.1.1.1");
        limiter.cleanup();
        limiter.checkCreate("s-1", "1.1.1.1");
        assertThatThrownBy(() -> limiter.checkCreate("s-1", "1.1.1.1"))
                .isInstanceOf(BizException.class);
    }
}
