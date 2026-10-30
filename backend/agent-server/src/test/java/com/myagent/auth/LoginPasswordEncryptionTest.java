package com.myagent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 登录密码必须加密传输。
 *
 * <p>覆盖：密文正常登录 / 明文默认被拒 / 放开兼容开关才收明文 / 带错 keyId 提示刷新。
 */
class LoginPasswordEncryptionTest {

    private static final String SESSION = "session-abcdefgh";

    private SysUserMapper userMapper;
    private PasswordEncoder passwordEncoder;
    private JwtService jwtService;
    private SliderCaptchaService captchaService;
    private LoginKeyService loginKeyService;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        userMapper = mock(SysUserMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtService = mock(JwtService.class);
        captchaService = mock(SliderCaptchaService.class);
        loginKeyService = new LoginKeyService(new ObjectMapper());
        ReflectionTestUtils.setField(loginKeyService, "payloadTtlSeconds", 120);
        ReflectionTestUtils.setField(loginKeyService, "keyTtlSeconds", 300);
        ReflectionTestUtils.setField(loginKeyService, "nonceCacheSize", 20000);

        controller = new AuthController(userMapper, passwordEncoder, jwtService, captchaService,
                loginKeyService);
        ReflectionTestUtils.setField(controller, "allowPlaintextPassword", false);

        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setPasswordHash("hash");
        user.setStatus("ENABLED");
        user.setRole("ADMIN");
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        when(jwtService.issue(1L, "admin", "ADMIN")).thenReturn("jwt-token");
    }

    @Test
    @DisplayName("默认拒绝明文密码（前端必须加密）")
    void plaintextIsRejectedByDefault() {
        assertThatThrownBy(() -> controller.login(
                new LoginRequest("admin", "secret", null, null, "t-1", SESSION)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("必须加密");
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    @DisplayName("密文登录成功：服务端解密后拿真实密码校验")
    void encryptedLoginSucceeds() throws Exception {
        String cipher = encryptPassword("secret", SESSION);

        var response = controller.login(
                new LoginRequest("admin", null, cipher, loginKeyService.publicKey().keyId(),
                        "t-1", SESSION));

        assertThat(response.data().token()).isEqualTo("jwt-token");
        verify(passwordEncoder).matches("secret", "hash");
    }

    @Test
    @DisplayName("同一段密文重复提交 → 被 nonce 去重拦下（防重放）")
    void replayedCipherRejected() throws Exception {
        String cipher = encryptPassword("secret", SESSION);

        controller.login(new LoginRequest("admin", null, cipher,
                loginKeyService.publicKey().keyId(), "t-1", SESSION));

        assertThatThrownBy(() -> controller.login(
                new LoginRequest("admin", null, cipher, loginKeyService.publicKey().keyId(),
                        "t-2", SESSION)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("重复");
    }

    @Test
    @DisplayName("公钥已轮换（keyId 对不上）→ 提示刷新，而不是密码错误")
    void staleKeyIdAsksForRefresh() throws Exception {
        String cipher = encryptPassword("secret", SESSION);

        assertThatThrownBy(() -> controller.login(
                new LoginRequest("admin", null, cipher, "0000000000000000", "t-1", SESSION)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("公钥已更新");
    }

    @Test
    @DisplayName("显式打开兼容开关时才接受明文（仅本地联调）")
    void plaintextAllowedWhenExplicitlyEnabled() {
        ReflectionTestUtils.setField(controller, "allowPlaintextPassword", true);

        var response = controller.login(
                new LoginRequest("admin", "secret", null, null, "t-1", SESSION));

        assertThat(response.data().token()).isEqualTo("jwt-token");
    }

    @Test
    @DisplayName("公钥接口只给公钥")
    void loginKeyEndpointExposesOnlyPublicKey() {
        var info = controller.loginKey().data();
        assertThat(info.publicKey()).startsWith("-----BEGIN PUBLIC KEY-----").doesNotContain("PRIVATE");
    }

    private String encryptPassword(String password, String session) throws Exception {
        String pem = loginKeyService.publicKey().publicKey()
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        PublicKey publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
        String json = "{\"p\":\"" + password + "\",\"t\":" + System.currentTimeMillis()
                + ",\"n\":\"" + UUID.randomUUID() + "\",\"s\":\"" + session + "\"}";
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey, new OAEPParameterSpec("SHA-256", "MGF1",
                MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        return Base64.getEncoder().encodeToString(cipher.doFinal(json.getBytes(StandardCharsets.UTF_8)));
    }
}
