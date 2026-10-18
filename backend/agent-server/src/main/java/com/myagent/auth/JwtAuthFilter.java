package com.myagent.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 从 {@code Authorization: Bearer <token>} 解析身份并写入 SecurityContext。
 *
 * <p>刻意不做成 Spring Bean，避免被 Servlet 容器重复注册；由 SecurityFilterChain 显式装配。
 *
 * <p><b>令牌之外还要回查账号</b>（本次修复）：JWT 里带着 uid/role，但令牌签发后可能发生
 * "账号被停用/删除"或"角色被改"。只信令牌的话，这些变更在令牌过期前（默认 12 小时）都不生效：
 * 停用的人照样能调接口，被降级的管理员照样有管理员权限。所以每次请求按 uid 回查一次
 * {@code sys_user}（主键查询，代价很小）：
 * <ul>
 *   <li>账号不存在 / 已停用 → 直接不认证（等价于立刻失效）</li>
 *   <li>角色以**数据库当前值**为准 → 改角色立即生效（降级会立刻收回权限，不需要等令牌过期）</li>
 * </ul>
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final com.myagent.auth.mapper.SysUserMapper userMapper;

    public JwtAuthFilter(JwtService jwtService, com.myagent.auth.mapper.SysUserMapper userMapper) {
        this.jwtService = jwtService;
        this.userMapper = userMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            try {
                JwtPrincipal tokenPrincipal = jwtService.parse(token);
                // 回查账号：停用/删除立刻失效；角色以数据库当前值为准
                com.myagent.auth.entity.SysUser user =
                        tokenPrincipal.userId() == null ? null : userMapper.selectById(tokenPrincipal.userId());
                if (user == null || !"ENABLED".equals(user.getStatus())) {
                    SecurityContextHolder.clearContext();
                } else {
                    JwtPrincipal principal =
                            new JwtPrincipal(user.getId(), user.getUsername(), user.getRole());
                    var authorities = principal.role() == null
                            ? List.<SimpleGrantedAuthority>of()
                            : List.of(new SimpleGrantedAuthority("ROLE_" + principal.role()));
                    var authentication =
                            new UsernamePasswordAuthenticationToken(principal, null, authorities);
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (Exception ex) {
                // 令牌无效/过期：保持未认证，交给授权规则返回 401。
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }
}
