package com.myagent.auth.captcha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.myagent.auth.captcha.dto.CaptchaResponse;
import com.myagent.common.BizException;
import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/** 滑动验证：出题、判定容差、一次一用、过期。 */
class SliderCaptchaServiceTest {

    private CaptchaChallengeMapper mapper;
    private SliderCaptchaService service;

    @BeforeEach
    void setUp() {
        mapper = mock(CaptchaChallengeMapper.class);
        service = new SliderCaptchaService(mapper);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "ttlSeconds", 120);
        ReflectionTestUtils.setField(service, "tolerance", 6);
        ReflectionTestUtils.setField(service, "maxAttempts", 5);
    }

    @Test
    @DisplayName("出题：两张图都是能解码的 PNG，尺寸符合约定，答案落在合法区间")
    void createRendersImages() throws Exception {
        CaptchaResponse response = service.create();

        assertThat(response.required()).isTrue();
        assertThat(response.id()).isNotBlank();
        assertThat(response.background()).startsWith("data:image/png;base64,");
        assertThat(response.piece()).startsWith("data:image/png;base64,");

        var background = ImageIO.read(new ByteArrayInputStream(
                Base64.getDecoder().decode(response.background().substring("data:image/png;base64,".length()))));
        var piece = ImageIO.read(new ByteArrayInputStream(
                Base64.getDecoder().decode(response.piece().substring("data:image/png;base64,".length()))));
        assertThat(background.getWidth()).isEqualTo(SliderCaptchaImage.WIDTH);
        assertThat(background.getHeight()).isEqualTo(SliderCaptchaImage.HEIGHT);
        assertThat(piece.getWidth()).isEqualTo(SliderCaptchaImage.PIECE);
        assertThat(piece.getHeight()).isEqualTo(SliderCaptchaImage.PIECE);

        ArgumentCaptor<CaptchaChallenge> saved = ArgumentCaptor.forClass(CaptchaChallenge.class);
        verify(mapper).insert(saved.capture());
        CaptchaChallenge challenge = saved.getValue();
        assertThat(challenge.getAnswerX()).isBetween(SliderCaptchaImage.MIN_X, SliderCaptchaImage.MAX_X);
        assertThat(challenge.getPieceY()).isEqualTo(response.pieceY());
        assertThat(challenge.getVerified()).isFalse();
        assertThat(challenge.getExpiresAt()).isAfter(LocalDateTime.now());
        // 答案不能出现在返回给前端的任何字段里（只给 id 和图片）
        assertThat(response.toString()).doesNotContain(String.valueOf(challenge.getAnswerX()));
    }

    @Test
    @DisplayName("容差内算对：±tolerance 都能过，拿到一次性 ticket")
    void verifyAcceptsWithinTolerance() {
        CaptchaChallenge challenge = pending("c1", 150);
        when(mapper.selectById("c1")).thenReturn(challenge);

        String ticket = service.verify("c1", 156); // +6

        assertThat(ticket).isNotBlank();
        assertThat(challenge.getVerified()).isTrue();
        assertThat(challenge.getTicket()).isEqualTo(ticket);
        verify(mapper).updateById(challenge);

        CaptchaChallenge other = pending("c2", 150);
        when(mapper.selectById("c2")).thenReturn(other);
        assertThat(service.verify("c2", 144)).isNotBlank(); // -6
    }

    @Test
    @DisplayName("超出容差算错：不改 verified，只累加 attempts")
    void verifyRejectsOutsideTolerance() {
        CaptchaChallenge challenge = pending("c1", 150);
        when(mapper.selectById("c1")).thenReturn(challenge);

        assertThatThrownBy(() -> service.verify("c1", 157))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("位置不对");
        assertThat(challenge.getVerified()).isFalse();
        assertThat(challenge.getAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("试太多次直接作废这道题（防止脚本慢慢试）")
    void verifyInvalidatesAfterMaxAttempts() {
        CaptchaChallenge challenge = pending("c1", 150);
        challenge.setAttempts(4);
        when(mapper.selectById("c1")).thenReturn(challenge);

        assertThatThrownBy(() -> service.verify("c1", 10))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("作废");
        verify(mapper).deleteById("c1");
    }

    @Test
    @DisplayName("过期的题不能用")
    void verifyRejectsExpired() {
        CaptchaChallenge challenge = pending("c1", 150);
        challenge.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(mapper.selectById("c1")).thenReturn(challenge);

        assertThatThrownBy(() -> service.verify("c1", 150))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("过期");
    }

    @Test
    @DisplayName("ticket 一次一用：登录时消费即删除，重复用会失败")
    void consumeTicketIsOneTime() {
        CaptchaChallenge verified = pending("c1", 150);
        verified.setVerified(true);
        verified.setTicket("t-1");
        when(mapper.selectOne(any())).thenReturn(verified);

        service.consumeTicket("t-1");
        verify(mapper).deleteById("c1");

        when(mapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.consumeTicket("t-1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("请重新验证");
    }

    @Test
    @DisplayName("没带 ticket 不允许登录")
    void consumeTicketRequiresTicket() {
        assertThatThrownBy(() -> service.consumeTicket(null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("滑动验证");
    }

    @Test
    @DisplayName("关掉验证时：不出图，登录也不查 ticket")
    void disabledSkipsEverything() {
        ReflectionTestUtils.setField(service, "enabled", false);

        CaptchaResponse response = service.create();
        assertThat(response.required()).isFalse();
        assertThat(response.background()).isNull();

        service.consumeTicket(null); // 不抛
    }

    private CaptchaChallenge pending(String id, int answerX) {
        CaptchaChallenge challenge = new CaptchaChallenge();
        challenge.setId(id);
        challenge.setAnswerX(answerX);
        challenge.setPieceY(40);
        challenge.setVerified(false);
        challenge.setAttempts(0);
        challenge.setCreatedAt(LocalDateTime.now());
        challenge.setExpiresAt(LocalDateTime.now().plusSeconds(120));
        return challenge;
    }
}
