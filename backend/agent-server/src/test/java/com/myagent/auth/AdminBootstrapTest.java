package com.myagent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.myagent.auth.entity.SysUser;
import com.myagent.auth.mapper.SysUserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 管理员初始口令引导：只在「还是默认口令」时动手。
 *
 * <p>守两件事：① 配了 MYAGENT_ADMIN_PASSWORD 就按它重设（避免开源默认口令上公网）；
 * ② 已经改过密码的库**绝不覆盖**（否则每次重启都会把用户密码重置回环境变量）。
 */
class AdminBootstrapTest {

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private final SysUserMapper mapper = mock(SysUserMapper.class);

    private SysUser adminWith(String rawPassword) {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setPasswordHash(encoder.encode(rawPassword));
        return user;
    }

    @Test
    @DisplayName("默认口令 + 配了环境变量 → 改成环境变量的口令")
    void replacesDefaultPassword() {
        SysUser admin = adminWith("admin123");
        when(mapper.selectOne(any())).thenReturn(admin);

        new AdminBootstrap(mapper, encoder, "MyStrongPass2026!").run(null);

        verify(mapper).updateById(any(SysUser.class));
        assertThat(encoder.matches("MyStrongPass2026!", admin.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("默认口令 + 没配环境变量 → 不修改（只打 WARN）")
    void keepsDefaultWhenNoEnv() {
        SysUser admin = adminWith("admin123");
        when(mapper.selectOne(any())).thenReturn(admin);

        new AdminBootstrap(mapper, encoder, "").run(null);

        verify(mapper, never()).updateById(any(SysUser.class));
    }

    @Test
    @DisplayName("已经改过密码 → 环境变量也不覆盖（避免每次重启重置用户密码）")
    void neverTouchesChangedPassword() {
        SysUser admin = adminWith("AlreadyChanged!2026");
        when(mapper.selectOne(any())).thenReturn(admin);

        new AdminBootstrap(mapper, encoder, "AnotherPass2026!").run(null);

        verify(mapper, never()).updateById(any(SysUser.class));
        assertThat(encoder.matches("AlreadyChanged!2026", admin.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("库里没有 admin（不是本平台的库）→ 什么都不做")
    void ignoresMissingAdmin() {
        when(mapper.selectOne(any())).thenReturn(null);
        new AdminBootstrap(mapper, encoder, "X12345678").run(null);
        verify(mapper, never()).updateById(any(SysUser.class));
    }
}
