package com.myagent.agui.browser.ext;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 浏览器扩展跨域（CORS）。
 *
 * <p><b>为什么需要</b>：扩展的 service worker 里 {@code fetch} 的 Origin 是
 * {@code chrome-extension://<扩展ID>}，对平台来说是跨域请求。不配 CORS 的表现是：
 * 预检 OPTIONS 被 Spring Security 拦成 401、响应没有 {@code Access-Control-Allow-Origin}，
 * 浏览器直接报 "Failed to fetch" —— 前端网络面板只能看到请求发出去了（实测踩过）。
 *
 * <p><b>安全边界</b>：CORS 只是浏览器侧的准入，真正的鉴权是设备令牌 + PKCE 配对；
 * 而且这里只对 {@code /api/browser/extension/**} 生效，不影响其它 API 的跨域策略。
 * 生产建议把 {@code allowed-extension-origins} 配成具体的 {@code chrome-extension://<固定ID>}；
 * 留空时（内测）才放开到任意 {@code chrome-extension://} 来源。
 */
@Configuration
public class BrowserExtensionCorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource(BrowserExtensionProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        List<String> configured = properties.allowedExtensionOrigins();
        if (configured.isEmpty()) {
            config.setAllowedOriginPatterns(List.of("chrome-extension://*", "extension://*"));
        } else {
            config.setAllowedOrigins(configured);
        }
        config.setAllowedMethods(List.of("GET", "POST", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        // 配对请求不需要带 Cookie（设备身份走 body/header），关掉凭据让规则更简单
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/browser/extension/**", config);
        return source;
    }
}
