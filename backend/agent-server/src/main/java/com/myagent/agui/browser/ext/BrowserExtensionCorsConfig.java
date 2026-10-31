package com.myagent.agui.browser.ext;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 浏览器扩展跨域（CORS）。
 *
 * <p><b>为什么需要</b>：扩展 service worker 里 {@code fetch} 的 Origin 是
 * {@code chrome-extension://<扩展ID>}，对平台是跨域请求。不配 CORS 的表现是预检 401、
 * 响应没有 {@code Access-Control-Allow-Origin}，浏览器只报 "Failed to fetch"。
 *
 * <p><b>踩过的坑（重要）</b>：只按 Origin 白名单放行会误伤**平台自己的网页**。经 nginx 反代后，
 * Spring 看到的 server 是 {@code backend:8080}，而浏览器 Origin 是 {@code http://<公网IP>:18080}
 * —— 被判定为跨域且不在白名单 → {@code CorsFilter} 直接回 403 "Invalid CORS request"，
 * 表现就是网页里点「批准配对」403。所以这里还接受「与请求自身 Host / X-Forwarded-Host 一致」的来源：
 * 那是同源请求，本来就是浏览器自己发起的，放行不扩大攻击面。
 *
 * <p><b>安全边界</b>：CORS 只是浏览器侧准入；真正鉴权是设备令牌 + PKCE 配对。
 * 规则只对 {@code /api/browser/extension/**} 生效，不影响其它 API。
 */
@Configuration
public class BrowserExtensionCorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource(BrowserExtensionProperties properties) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource() {
            @Override
            public CorsConfiguration getCorsConfiguration(HttpServletRequest request) {
                // 只处理浏览器扩展相关的接口：其它路径返回 null = 不做 CORS 处理（保持原行为，
                // 不会因为这里的规则把整个 API 的跨域放宽）
                String path = request.getRequestURI();
                if (path == null || !path.startsWith("/api/browser/extension/")) {
                    return null;
                }
                CorsConfiguration config = new CorsConfiguration();
                Set<String> origins = new LinkedHashSet<>();

                List<String> configured = properties.allowedExtensionOrigins();
                if (configured.isEmpty()) {
                    // 内测：任意扩展来源（真正的身份校验是设备令牌）
                    config.setAllowedOriginPatterns(List.of("chrome-extension://*", "extension://*"));
                } else {
                    origins.addAll(configured);
                }

                String origin = request.getHeader("Origin");
                if (origin != null && !origin.isBlank() && sameAsThisHost(origin, request)) {
                    // 同源请求（反代后 Spring 认不出自己的对外地址，这里补上判断）
                    origins.add(origin);
                }
                if (!origins.isEmpty()) {
                    if (config.getAllowedOriginPatterns() == null) {
                        config.setAllowedOriginPatterns(new ArrayList<>(origins));
                    } else {
                        List<String> patterns = new ArrayList<>(config.getAllowedOriginPatterns());
                        patterns.addAll(origins);
                        config.setAllowedOriginPatterns(patterns);
                    }
                }

                config.setAllowedMethods(List.of("GET", "POST", "DELETE", "OPTIONS"));
                config.setAllowedHeaders(List.of("*"));
                config.setExposedHeaders(List.of("Authorization"));
                // 配对/会话都靠 Authorization 头，不靠 Cookie → 关掉凭据，规则更简单
                config.setAllowCredentials(false);
                config.setMaxAge(3600L);
                return config;
            }
        };
        return source;
    }

    /** 判断 Origin 是否就是「这次请求自己的对外地址」（可能经过反代）。 */
    private static boolean sameAsThisHost(String origin, HttpServletRequest request) {
        String originHost = hostOf(origin);
        if (originHost == null) {
            return false;
        }
        for (String candidate : candidates(request)) {
            if (originHost.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static String hostOf(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            int port = uri.getPort();
            String scheme = uri.getScheme().toLowerCase();
            if (port == -1) {
                port = "https".equals(scheme) ? 443 : 80;
            }
            return uri.getHost().toLowerCase() + ":" + port;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static List<String> candidates(HttpServletRequest request) {
        Set<String> values = new LinkedHashSet<>();
        String forwardedHost = request.getHeader("X-Forwarded-Host");
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        String host = request.getHeader("Host");
        for (String raw : List.of(forwardedHost == null ? "" : forwardedHost, host == null ? "" : host)) {
            if (raw.isBlank()) {
                continue;
            }
            for (String one : raw.split(",")) {
                String value = one.trim();
                if (value.isEmpty()) {
                    continue;
                }
                for (String scheme : List.of("http", "https")) {
                    values.add(scheme + "://" + value);
                }
                if (forwardedProto != null && !forwardedProto.isBlank()) {
                    values.add(forwardedProto.split(",")[0].trim() + "://" + value);
                }
            }
        }
        List<String> hosts = new ArrayList<>();
        for (String value : values) {
            String parsed = hostOf(value);
            if (parsed != null) {
                hosts.add(parsed);
            }
        }
        return hosts;
    }
}
