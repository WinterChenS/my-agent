package com.myagent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String SECRET = "unit-test-jwt-secret-0123456789abcdef";

    private final JwtService jwtService = new JwtService(SECRET, 60);

    @Test
    void 签发解析往返一致() {
        String token = jwtService.issue(7L, "admin", "ADMIN");
        JwtPrincipal principal = jwtService.parse(token);
        assertThat(principal.userId()).isEqualTo(7L);
        assertThat(principal.username()).isEqualTo("admin");
        assertThat(principal.role()).isEqualTo("ADMIN");
    }

    @Test
    void 不存在的令牌解析失败() {
        assertThatThrownBy(() -> jwtService.parse("not.a.jwt")).isInstanceOf(Exception.class);
    }

    @Test
    void 其他密钥签发的令牌验签失败() {
        JwtService other = new JwtService("another-jwt-secret-9876543210zyxwvuts", 60);
        String foreignToken = other.issue(1L, "someone", "USER");
        assertThatThrownBy(() -> jwtService.parse(foreignToken)).isInstanceOf(Exception.class);
    }
}
