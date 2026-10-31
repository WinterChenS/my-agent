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
 *   <li>放行：{@code /api/auth/login}、{@code /api/auth/captcha**}（滑动验证）、{@code /api/health}
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
        // IM 渠道（钉钉/飞书）：读要登录、写要 ADMIN
        "/api/channels/**",
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
                // 浏览器扩展的 Origin 是 chrome-extension://<id>，必须走 CORS 才能 fetch。
                // 规则只在 BrowserExtensionCorsConfig 里注册给 /api/browser/extension/**。
                .cors(org.springframework.security.config.Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(authEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        // SSE 的异步派发与容器错误页不应该再走一遍鉴权：否则客户端断开后
                        // Spring Security 会在已提交的响应上抛异常（Task 4.2 实测发现）。
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR)
                        .permitAll()
                        // CORS 预检：不带 Authorization，必须放行，否则扩展连配对都发不出去
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/auth/login", "/api/auth/login-key", "/api/auth/captcha",
                                "/api/auth/captcha/**", "/api/health")
                        .permitAll()
                        // 浏览器扩展配对：扩展还没有账号身份，用 deviceCode+PKCE 换设备凭证（自带限流）
                        .requestMatchers(HttpMethod.POST, "/api/browser/extension/pairings",
                                "/api/browser/extension/pairings/token",
                                "/api/browser/extension/token/refresh")
                        .permitAll()
                        // 扩展长连接：握手阶段没有 Bearer，改由**首帧 auth**校验设备令牌
                        .requestMatchers("/api/browser/bridge/ws").permitAll()
                        // 定时任务：登录即可，普通用户只能看到/管理自己的（服务层按 created_by 过滤）
                        .requestMatchers("/api/scheduled-tasks/**").authenticated()
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
