package com.myagent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.auth.captcha.SliderCaptchaService;
import com.myagent.auth.crypto.LoginKeyService;
import com.myagent.auth.dto.LoginRequest;
import com.myagent.auth.entity.SysUser;
import com.myagent.auth.mapper.SysUserMapper;
import com.myagent.common.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 业务接口（登录）与验证码的绑定关系。
 *
 * <p>要点：登录**必须先消费一个有效的、同会话的 ticket**；跳过后端验证直接打登录接口是过不去的。
 */
class AuthControllerCaptchaTest {

    private SysUserMapper userMapper;
    private PasswordEncoder passwordEncoder;
    private JwtService jwtService;
    private SliderCaptchaService captchaService;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        userMapper = mock(SysUserMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtService = mock(JwtService.class);
        captchaService = mock(SliderCaptchaService.class);
        controller = new AuthController(userMapper, passwordEncoder, jwtService, captchaService,
                new LoginKeyService(new ObjectMapper()));
        // 这些用例只关心"验证码票据是否被消费"，密码走明文兼容开关
        org.springframework.test.util.ReflectionTestUtils.setField(
                controller, "allowPlaintextPassword", true);
    }

    @Test
    @DisplayName("没有验证码 ticket 时登录直接被拒，且不会去查库校验密码")
    void loginRequiresCaptchaTicket() {
        org.mockito.Mockito.doThrow(BizException.badRequest("请先完成滑动验证"))
                .when(captchaService).consumeTicket(null, "session-abcdefgh");

        assertThatThrownBy(() -> controller.login(
                new LoginRequest("admin", "secret", null, null, null, "session-abcdefgh")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("验证");

        verify(userMapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("ticket 无效/已被用过时登录失败")
    void loginFailsWithReplayedTicket() {
        org.mockito.Mockito.doThrow(BizException.badRequest("滑动验证无效或已过期，请重新验证"))
                .when(captchaService).consumeTicket("t-used", "session-abcdefgh");

        assertThatThrownBy(() -> controller.login(
                new LoginRequest("admin", "secret", null, null, "t-used", "session-abcdefgh")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无效");
    }

    @Test
    @DisplayName("验证码通过 + 密码正确 → 发 token，且 ticket 是按会话消费的")
    void loginSucceedsAfterCaptcha() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setPasswordHash("hash");
        user.setStatus("ENABLED");
        user.setRole("ADMIN");

        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("secret", "hash")).thenReturn(true);
        when(jwtService.issue(1L, "admin", "ADMIN")).thenReturn("jwt-token");

        var response = controller.login(new LoginRequest("admin", "secret", null, null, "t-1", "session-abcdefgh"));

        assertThat(response.data().token()).isEqualTo("jwt-token");
        ArgumentCaptor<String> ticket = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> session = ArgumentCaptor.forClass(String.class);
        verify(captchaService).consumeTicket(ticket.capture(), session.capture());
        assertThat(ticket.getValue()).isEqualTo("t-1");
        assertThat(session.getValue()).isEqualTo("session-abcdefgh");
    }
}
