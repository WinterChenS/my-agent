package com.myagent.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CryptoServiceTest {

    private static final String KEY = "unit-test-crypto-key-0123456789";

    private final CryptoService cryptoService = new CryptoService(KEY);

    @Test
    void 加解密往返一致() {
        String plain = "sk-abcdef123456-中文-!@#";
        String cipher = cryptoService.encrypt(plain);
        assertThat(cipher).isNotEqualTo(plain);
        assertThat(cryptoService.decrypt(cipher)).isEqualTo(plain);
    }

    @Test
    void 相同明文每次密文不同_体现随机IV() {
        String plain = "same-plain-text";
        assertThat(cryptoService.encrypt(plain)).isNotEqualTo(cryptoService.encrypt(plain));
    }

    @Test
    void 空值原样返回() {
        assertThat(cryptoService.encrypt(null)).isNull();
        assertThat(cryptoService.encrypt("")).isEmpty();
        assertThat(cryptoService.decrypt(null)).isNull();
        assertThat(cryptoService.decrypt("")).isEmpty();
    }

    @Test
    void 换主密钥解密失败抛业务错误而非崩溃() {
        CryptoService other = new CryptoService("another-crypto-key-9876543210");
        String cipher = cryptoService.encrypt("secret");
        assertThatThrownBy(() -> other.decrypt(cipher))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("解密失败");
    }

    @Test
    void 非法密文抛业务错误() {
        assertThatThrownBy(() -> cryptoService.decrypt("not-a-valid-base64-cipher"))
                .isInstanceOf(BizException.class);
    }
}
