package com.myagent.auth.captcha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.myagent.auth.captcha.dto.CaptchaResponse;
import com.myagent.auth.captcha.dto.CaptchaVerifyRequest;
import com.myagent.common.BizException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 验证码服务：服务端判定 / 会话绑定 / 一次性 / 防重放 / 防爆破 / 行为风险。
 *
 * <p>这里用真实的限流器与行为分析器（只是把阈值调小方便断言），mapper 用 mock
 * 来模拟"数据库原子抢占"的结果。
 */
class SliderCaptchaServiceTest {

    private static final String SESSION = "session-abcdefgh";
    private static final String IP = "10.0.0.1";

    private CaptchaChallengeMapper mapper;
    private CaptchaRateLimiter limiter;
    private SlideBehaviorAnalyzer analyzer;
    private SliderCaptchaService service;

    @BeforeEach
    void setUp() {
        mapper = mock(CaptchaChallengeMapper.class);
        limiter = new CaptchaRateLimiter();
        ReflectionTestUtils.setField(limiter, "createPerMinuteSession", 20);
        ReflectionTestUtils.setField(limiter, "createPerMinuteIp", 60);
        ReflectionTestUtils.setField(limiter, "verifyPerMinuteSession", 12);
        ReflectionTestUtils.setField(limiter, "verifyPerMinuteIp", 60);
        ReflectionTestUtils.setField(limiter, "cooldownThreshold", 5);
        ReflectionTestUtils.setField(limiter, "cooldownBaseSeconds", 15);
        ReflectionTestUtils.setField(limiter, "cooldownMaxSeconds", 300);
        ReflectionTestUtils.setField(limiter, "failWindowSeconds", 300);

        analyzer = new SlideBehaviorAnalyzer();
        ReflectionTestUtils.setField(analyzer, "minPoints", 5);
        ReflectionTestUtils.setField(analyzer, "minDurationMs", 130);
        ReflectionTestUtils.setField(analyzer, "uniformSpeedCv", 0.12);
        ReflectionTestUtils.setField(analyzer, "rejectScore", 60);
        ReflectionTestUtils.setField(analyzer, "historySize", 20);

        service = new SliderCaptchaService(mapper, limiter, analyzer);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "ttlSeconds", 90);
        ReflectionTestUtils.setField(service, "tolerance", 6);
        ReflectionTestUtils.setField(service, "maxAttempts", 5);
        ReflectionTestUtils.setField(service, "behaviorEnabled", true);
    }

    // ===================== 出题 =====================

    @Test
    @DisplayName("出题：答案只进库，响应里拿不到正确坐标")
    void createSliderKeepsAnswerServerSide() {
        CaptchaResponse response = service.create(SESSION, IP, null);

        assertThat(response.required()).isTrue();
        assertThat(response.mode()).isEqualTo("SLIDER");
        assertThat(response.background()).startsWith("data:image/png;base64,");
        assertThat(response.piece()).startsWith("data:image/png;base64,");
        assertThat(response.width()).isEqualTo(SliderCaptchaImage.WIDTH);
        assertThat(response.pieceSize()).isEqualTo(SliderCaptchaImage.CANVAS);
        assertThat(response.piecePad()).isEqualTo(SliderCaptchaImage.PAD);
        assertThat(response.ttlSeconds()).isEqualTo(90);

        CaptchaChallenge saved = captureInsert();
        assertThat(saved.getAnswerX()).isBetween(SliderCaptchaImage.MIN_X, SliderCaptchaImage.MAX_X);
        assertThat(saved.getSessionKey()).isEqualTo(SESSION);
        assertThat(saved.getUsed()).isFalse();
        // 响应结构里不允许有承载答案的字段（图片 base64 里偶然出现同样的数字不算泄露）
        List<String> fields = new ArrayList<>();
        for (var component : CaptchaResponse.class.getRecordComponents()) {
            fields.add(component.getName());
        }
        assertThat(fields).doesNotContain("answerX", "answer", "success", "correct");
        // 非图片的元信息里也不能夹带答案
        String meta = response.mode() + "|" + response.id() + "|" + response.pieceY() + "|"
                + response.width() + "|" + response.height() + "|" + response.ttlSeconds();
        assertThat(meta).doesNotContain("answer");
        assertThat(response.question()).isNull();
    }

    @Test
    @DisplayName("无障碍替代题：文字题答案同样只在服务端")
    void createTextMode() {
        CaptchaResponse response = service.create(SESSION, IP, "text");

        assertThat(response.mode()).isEqualTo("TEXT");
        assertThat(response.question()).contains("=");
        assertThat(response.background()).isNull();
        CaptchaChallenge saved = captureInsert();
        assertThat(saved.getTextAnswer()).isNotBlank();
        // 文字题的题面必须是"算式"（等号后面是问号，不能把结果写出来）
        String question = response.question();
        assertThat(question).endsWith("= ?");
        assertThat(question.contains("+") || question.contains("−")).isTrue();
        assertThat(question.replace(" ", "")).doesNotContain("=" + saved.getTextAnswer());
    }

    @Test
    @DisplayName("出题限流：同一会话一分钟内刷太多直接 429（换 IP 也没用）")
    void createIsRateLimitedPerSession() {
        ReflectionTestUtils.setField(limiter, "createPerMinuteSession", 3);
        for (int i = 0; i < 3; i++) {
            limiter.checkCreate(SESSION, "ip-" + i);
        }
        assertThatThrownBy(() -> limiter.checkCreate(SESSION, "ip-99"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("频繁");
    }

    @Test
    @DisplayName("没有会话标识不允许出题（会话绑定是防重放的基础）")
    void createRequiresSession() {
        assertThatThrownBy(() -> service.create(null, IP, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("会话标识");
    }

    // ===================== 验证 =====================

    @Test
    @DisplayName("正常用户：位置在容差内 + 人类轨迹 → 拿到 ticket")
    void verifySuccess() {
        CaptchaChallenge challenge = pending("c1", 150);
        when(mapper.selectById("c1")).thenReturn(challenge);
        when(mapper.claim(eq("c1"), anyString())).thenReturn(1);

        String ticket = service.verify(request("c1", 154, humanTrack()), IP);

        assertThat(ticket).isNotBlank();
        verify(mapper).claim(eq("c1"), eq(ticket));
    }

    @Test
    @DisplayName("位置不对：失败，且不消费题目（还允许用户再试）")
    void verifyWrongPosition() {
        when(mapper.selectById("c1")).thenReturn(pending("c1", 150));

        assertThatThrownBy(() -> service.verify(request("c1", 190, humanTrack()), IP))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("位置不对");
        verify(mapper, never()).claim(anyString(), anyString());
        verify(mapper).recordFailure(eq("c1"), any());
    }

    @Test
    @DisplayName("过期题目不能验证")
    void verifyExpired() {
        CaptchaChallenge challenge = pending("c1", 150);
        challenge.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(mapper.selectById("c1")).thenReturn(challenge);

        assertThatThrownBy(() -> service.verify(request("c1", 150, humanTrack()), IP))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("过期");
    }

    @Test
    @DisplayName("重放：已经用过的题目再提交必须被拒")
    void verifyRejectsUsedChallenge() {
        CaptchaChallenge challenge = pending("c1", 150);
        challenge.setUsed(true);
        when(mapper.selectById("c1")).thenReturn(challenge);

        assertThatThrownBy(() -> service.verify(request("c1", 150, humanTrack()), IP))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已经用过");
    }

    @Test
    @DisplayName("并发提交同一题：数据库抢占只成功一次，另一个必须被拒")
    void verifyConcurrentOnlyOneWins() {
        when(mapper.selectById("c1")).thenReturn(pending("c1", 150));
        when(mapper.claim(eq("c1"), anyString())).thenReturn(0); // 别人先抢到了

        assertThatThrownBy(() -> service.verify(request("c1", 150, humanTrack()), IP))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已经用过");
    }

    @Test
    @DisplayName("题目与会话绑定：换个会话（哪怕是同一道题）不能验证")
    void verifyRejectsForeignSession() {
        CaptchaChallenge challenge = pending("c1", 150);
        challenge.setSessionKey("someone-else-key");
        when(mapper.selectById("c1")).thenReturn(challenge);

        assertThatThrownBy(() -> service.verify(request("c1", 150, humanTrack()), IP))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("失效");
    }

    @Test
    @DisplayName("尝试次数超限：题目作废（防坐标枚举）")
    void verifyInvalidatesAfterMaxAttempts() {
        CaptchaChallenge challenge = pending("c1", 150);
        challenge.setAttempts(4);
        when(mapper.selectById("c1")).thenReturn(challenge);

        assertThatThrownBy(() -> service.verify(request("c1", 10, humanTrack()), IP))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("作废");
        verify(mapper).invalidate("c1");
    }

    @Test
    @DisplayName("脚本轨迹（瞬移/匀速/点太少）：即使位置对也拒绝，并作废题目")
    void verifyRejectsBotTrack() {
        when(mapper.selectById("c1")).thenReturn(pending("c1", 150));

        assertThatThrownBy(() -> service.verify(request("c1", 150, robotTrack()), IP))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("验证未通过");
        verify(mapper).invalidate("c1");
        verify(mapper, never()).claim(anyString(), anyString());
    }

    @Test
    @DisplayName("伪造轨迹不能把错位置变成成功（行为分只做加法，不做放行）")
    void forgedTrackCannotPassWrongPosition() {
        when(mapper.selectById("c1")).thenReturn(pending("c1", 150));

        assertThatThrownBy(() -> service.verify(request("c1", 250, humanTrack()), IP))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("位置不对");
    }

    @Test
    @DisplayName("连续高频失败：触发渐进式冷却（429）")
    void verifyTriggersCooldown() {
        ReflectionTestUtils.setField(limiter, "cooldownThreshold", 2);
        limiter.recordFailure(SESSION);
        limiter.recordFailure(SESSION);

        assertThatThrownBy(() -> service.verify(request("c1", 150, humanTrack()), IP))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("秒后再试");
    }

    @Test
    @DisplayName("请求体里不存在 success 之类的前端结论字段（前端说了不算）")
    void requestHasNoClientVerdictField() {
        List<String> components = new ArrayList<>();
        for (var component : CaptchaVerifyRequest.class.getRecordComponents()) {
            components.add(component.getName());
        }
        assertThat(components).containsExactlyInAnyOrder("id", "sessionKey", "x", "answer", "track");
    }

    // ===================== ticket =====================

    @Test
    @DisplayName("ticket 一次性：消费成功即删除，重复消费失败")
    void consumeTicketOnce() {
        when(mapper.consumeTicket("t-1", SESSION)).thenReturn(1);
        service.consumeTicket("t-1", SESSION);
        verify(mapper).consumeTicket("t-1", SESSION);

        when(mapper.consumeTicket("t-1", SESSION)).thenReturn(0);
        assertThatThrownBy(() -> service.consumeTicket("t-1", SESSION))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无效或已过期");
    }

    @Test
    @DisplayName("ticket 必须属于同一会话：换会话消费失败")
    void consumeTicketBindsSession() {
        when(mapper.consumeTicket(eq("t-1"), anyString())).thenReturn(0);
        assertThatThrownBy(() -> service.consumeTicket("t-1", "another-session-1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无效或已过期");
    }

    @Test
    @DisplayName("没有 ticket 不允许通过（跳过验证码接口也拿不到 ticket）")
    void consumeTicketRequiresTicket() {
        assertThatThrownBy(() -> service.consumeTicket(null, SESSION))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("滑动验证");
    }

    @Test
    @DisplayName("关掉验证码时：不出题、不校验")
    void disabledSkipsEverything() {
        ReflectionTestUtils.setField(service, "enabled", false);
        assertThat(service.create(SESSION, IP, null).required()).isFalse();
        service.consumeTicket(null, null);
    }

    // ===================== 工具 =====================

    private CaptchaChallenge captureInsert() {
        org.mockito.ArgumentCaptor<CaptchaChallenge> captor =
                org.mockito.ArgumentCaptor.forClass(CaptchaChallenge.class);
        verify(mapper).insert(captor.capture());
        return captor.getValue();
    }

    private CaptchaChallenge pending(String id, int answerX) {
        CaptchaChallenge challenge = new CaptchaChallenge();
        challenge.setId(id);
        challenge.setAnswerX(answerX);
        challenge.setPieceY(40);
        challenge.setMode("SLIDER");
        challenge.setVerified(false);
        challenge.setUsed(false);
        challenge.setAttempts(0);
        challenge.setSessionKey(SESSION);
        challenge.setCreatedAt(LocalDateTime.now());
        challenge.setExpiresAt(LocalDateTime.now().plusSeconds(90));
        return challenge;
    }

    private CaptchaVerifyRequest request(String id, int x, List<List<Double>> track) {
        return new CaptchaVerifyRequest(id, SESSION, x, null, track);
    }

    /** 像人的轨迹：带加速、减速、微小回退、采样间隔不均匀。 */
    static List<List<Double>> humanTrack() {
        List<List<Double>> track = new ArrayList<>();
        double x = 0;
        double t = 0;
        for (int i = 0; i < 24; i++) {
            double step = i < 4 ? 0.01 : (i < 18 ? 0.05 + (i % 3) * 0.012 : 0.02);
            x += step;
            t += 18 + (i * 7) % 23; // 间隔不均匀
            if (i == 12) { // 一次小幅回退
                x -= 0.02;
            }
            track.add(List.of(Math.min(0.85, x), t));
        }
        return track;
    }

    /** 机器轨迹：点极少、总时长极短、单步瞬移到大半距离。 */
    static List<List<Double>> robotTrack() {
        return List.of(
                List.of(0.0, 0.0),
                List.of(0.85, 5.0));
    }
}
