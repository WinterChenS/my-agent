package com.myagent.auth.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.util.ReflectionTestUtils;

/** 登录密码传输加密：能解、防重放、防陈旧、防篡改、防串会话。 */
class LoginKeyServiceTest {

    private LoginKeyService service;

    @BeforeEach
    void setUp() {
        service = new LoginKeyService(new ObjectMapper());
        ReflectionTestUtils.setField(service, "payloadTtlSeconds", 120);
        ReflectionTestUtils.setField(service, "keyTtlSeconds", 300);
        ReflectionTestUtils.setField(service, "nonceCacheSize", 20000);
    }

    @Test
    @DisplayName("下发的只有公钥（PEM，没有 PRIVATE），并带 keyId")
    void exposesOnlyPublicKey() {
        LoginKeyService.PublicKeyInfo info = service.publicKey();

        assertThat(info.publicKey()).startsWith("-----BEGIN PUBLIC KEY-----");
        assertThat(info.publicKey()).doesNotContain("PRIVATE");
        assertThat(info.algorithm()).isEqualTo("RSA-OAEP-256");
        assertThat(info.keyId()).hasSize(16);
        assertThat(info.ttlSeconds()).isEqualTo(300);
    }

    @Test
    @DisplayName("正常解密：能拿回密码与会话标识")
    void decryptsValidPayload() throws Exception {
        String nonce = UUID.randomUUID().toString();
        String cipher = encrypt("{\"p\":\"s3cret\",\"t\":" + System.currentTimeMillis()
                + ",\"n\":\"" + nonce + "\",\"s\":\"session-abcdefgh\"}");

        LoginKeyService.DecryptedPayload payload = service.decrypt(cipher, "session-abcdefgh");

        assertThat(payload.password()).isEqualTo("s3cret");
        assertThat(payload.nonce()).isEqualTo(nonce);
    }

    @Test
    @DisplayName("同一段密文不能重复使用（nonce 去重 → 防重放）")
    void rejectsReplayedCipher() throws Exception {
        String cipher = encrypt(payload("p1", UUID.randomUUID().toString(), System.currentTimeMillis(), ""));

        assertThat(service.decrypt(cipher, "").password()).isEqualTo("p1");
        assertThatThrownBy(() -> service.decrypt(cipher, ""))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("重复");
    }

    @Test
    @DisplayName("太旧的载荷被拒（防离线重放）")
    void rejectsStalePayload() throws Exception {
        String cipher = encrypt(payload("p1", UUID.randomUUID().toString(),
                System.currentTimeMillis() - 10 * 60 * 1000L, ""));

        assertThatThrownBy(() -> service.decrypt(cipher, ""))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("过期");
    }

    @Test
    @DisplayName("密文里绑定的会话与请求不一致 → 拒绝（防止搬别人的密文到自己会话）")
    void rejectsSessionMismatch() throws Exception {
        String cipher = encrypt(payload("p1", UUID.randomUUID().toString(),
                System.currentTimeMillis(), "session-of-victim"));

        assertThatThrownBy(() -> service.decrypt(cipher, "session-of-attacker"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("会话不一致");
    }

    @Test
    @DisplayName("密文被篡改 → 统一报解密失败（不区分原因，避免 padding oracle）")
    void rejectsTamperedCipher() {
        byte[] fake = new byte[256];
        fake[0] = 7;
        String cipher = Base64.getEncoder().encodeToString(fake);

        assertThatThrownBy(() -> service.decrypt(cipher, ""))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("解密失败");
    }

    @Test
    @DisplayName("空密文 / 非法 base64 都有明确错误")
    void rejectsEmptyOrMalformed() {
        assertThatThrownBy(() -> service.decrypt(null, ""))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("必须加密");
        assertThatThrownBy(() -> service.decrypt("!!!not-base64!!!", ""))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("格式不正确");
    }

    @Test
    @DisplayName("keyId 校验：空/相同放行，不同则要前端刷新重取")
    void keyIdCheck() {
        String current = service.publicKey().keyId();
        assertThat(service.keyIdMatches(null)).isTrue();
        assertThat(service.keyIdMatches("")).isTrue();
        assertThat(service.keyIdMatches(current)).isTrue();
        assertThat(service.keyIdMatches("ffffffffffffffff")).isFalse();
    }

    // ===================== 工具：模拟前端做 OAEP(SHA-256) 加密 =====================

    private String payload(String password, String nonce, long ts, String session) {
        return "{\"p\":\"" + password + "\",\"t\":" + ts + ",\"n\":\"" + nonce
                + "\",\"s\":\"" + session + "\"}";
    }

    private String encrypt(String json) throws Exception {
        String pem = service.publicKey().publicKey()
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        PublicKey publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));

        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey,
                new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256,
                        PSource.PSpecified.DEFAULT));
        return Base64.getEncoder().encodeToString(cipher.doFinal(json.getBytes(StandardCharsets.UTF_8)));
    }
}
