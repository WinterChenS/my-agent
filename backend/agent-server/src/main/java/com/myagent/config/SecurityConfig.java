package com.myagent.config;

import com.myagent.auth.JwtAuthFilter;
import com.myagent.auth.JwtService;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 安全规则（技术方案第 8 节）。
 *
 * <ul>
 *   <li>放行：{@code /api/auth/login}、{@code /api/health}
 *   <li>配置类接口：GET 需已认证，写操作需 ADMIN
 *   <li>其余（含 {@code /agui/**} 与 {@code /api/audit/**}）需已认证
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String[] CONFIG_PATHS = {
        "/api/model-providers/**",
        "/api/models/**",
        "/api/agents/**",
        "/api/skills/**",
        "/api/mcp-servers/**",
        // 用户管理（C1）：读要登录、写要 ADMIN。注意配额接口 /api/users/{id}/quota 也在这条路径下，
        // 它自身还额外判了 ADMIN（见 QuotaController）。
        "/api/users/**"
    };

    private final JwtService jwtService;
    private final RestAuthEntryPoint authEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    private final com.myagent.auth.mapper.SysUserMapper userMapper;

    public SecurityConfig(
            JwtService jwtService,
            RestAuthEntryPoint authEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler,
            com.myagent.auth.mapper.SysUserMapper userMapper) {
        this.jwtService = jwtService;
        this.authEntryPoint = authEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.userMapper = userMapper;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(authEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        // SSE 的异步派发与容器错误页不应该再走一遍鉴权：否则客户端断开后
                        // Spring Security 会在已提交的响应上抛异常（Task 4.2 实测发现）。
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR)
                        .permitAll()
                        .requestMatchers("/api/auth/login", "/api/health")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, CONFIG_PATHS).authenticated()
                        .requestMatchers(CONFIG_PATHS).hasRole("ADMIN")
                        .anyRequest().authenticated())
                .addFilterBefore(
                        new JwtAuthFilter(jwtService, userMapper), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
