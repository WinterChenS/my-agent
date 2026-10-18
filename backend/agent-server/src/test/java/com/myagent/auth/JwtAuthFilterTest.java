package com.myagent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.myagent.auth.entity.SysUser;
import com.myagent.auth.mapper.SysUserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * JwtAuthFilter 单测：**令牌之外还要回查账号**。
 *
 * <p>回归背景（实测发现）：过滤器原先只解析 JWT、不回查数据库，于是
 * "停用账号""改角色"在令牌过期（默认 12 小时）前都不生效 —— 停用的人照样能调接口、
 * 被降级的管理员照样有管理员权限。现在每次请求按 uid 回查一次 sys_user。
 */
class JwtAuthFilterTest {

    private final JwtService jwtService = mock(JwtService.class);
    private final SysUserMapper userMapper = mock(SysUserMapper.class);
    private final JwtAuthFilter filter = new JwtAuthFilter(jwtService, userMapper);

    private void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private Authentication run() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer any-token");
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        return SecurityContextHolder.getContext().getAuthentication();
    }

    private static SysUser user(long id, String name, String role, String status) {
        SysUser u = new SysUser();
        u.setId(id);
        u.setUsername(name);
        u.setRole(role);
        u.setStatus(status);
        return u;
    }

    @Test
    void authenticatesWithDatabaseRole() throws Exception {
        when(jwtService.parse("any-token")).thenReturn(new JwtPrincipal(7L, "bob", "USER"));
        when(userMapper.selectById(7L)).thenReturn(user(7L, "bob", "USER", "ENABLED"));

        Authentication auth = run();

        assertThat(auth).isNotNull();
        assertThat(((JwtPrincipal) auth.getPrincipal()).role()).isEqualTo("USER");
        tearDown();
    }

    /** 关键回归：**停用账号后，旧令牌立刻失效**（修复前仍会认证通过）。 */
    @Test
    void disabledAccountIsRejectedEvenWithValidToken() throws Exception {
        when(jwtService.parse("any-token")).thenReturn(new JwtPrincipal(7L, "bob", "ADMIN"));
        when(userMapper.selectById(7L)).thenReturn(user(7L, "bob", "ADMIN", "DISABLED"));

        assertThat(run()).isNull();
        tearDown();
    }

    /** 账号已删除：同样立刻失效。 */
    @Test
    void deletedAccountIsRejected() throws Exception {
        when(jwtService.parse("any-token")).thenReturn(new JwtPrincipal(7L, "bob", "USER"));
        when(userMapper.selectById(7L)).thenReturn(null);

        assertThat(run()).isNull();
        tearDown();
    }

    /** 关键回归：**角色以数据库当前值为准** —— 降级后旧令牌不再带管理员权限，升级也立即生效。 */
    @Test
    void roleComesFromDatabaseNotFromToken() throws Exception {
        // 令牌里写的是 ADMIN，但库里已经降级为 USER
        when(jwtService.parse("any-token")).thenReturn(new JwtPrincipal(7L, "bob", "ADMIN"));
        when(userMapper.selectById(7L)).thenReturn(user(7L, "bob", "USER", "ENABLED"));

        Authentication auth = run();

        assertThat(auth).isNotNull();
        assertThat(((JwtPrincipal) auth.getPrincipal()).role()).isEqualTo("USER");
        assertThat(auth.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_USER");
        tearDown();
    }
}
