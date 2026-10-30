package com.myagent.agui.browser;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * 浏览器自动化（服务端真 Chromium + 实时画面）配置。
 *
 * <p>定位：给 Agent 一个"真的浏览器"，用户在产物预览区能看到画面、能接管操作。
 * 与 {@code AgentReach}（只读联网）互补：Reach 负责"读内容"，浏览器负责"操作页面"。
 *
 * <p>资源现实：一个 Chromium 约 150~300MB 内存、打开重页面时 CPU 很吃；这台机器只有 2 核，
 * 所以**默认并发 1**，且有空闲回收，避免把后端拖死。
 */
@Configuration
public class BrowserProperties {

    private final boolean enabled;
    private final String chromeBinary;
    private final int viewportWidth;
    private final int viewportHeight;
    private final int jpegQuality;
    private final int maxFps;
    private final int idleTimeoutSeconds;
    private final int maxConcurrentSessions;
    private final int navigateTimeoutMs;
    private final boolean allowUserInput;

    public BrowserProperties(
            @Value("${myagent.browser.enabled:true}") boolean enabled,
            @Value("${myagent.browser.chrome-binary:chrome-headless-shell}") String chromeBinary,
            @Value("${myagent.browser.viewport-width:1280}") int viewportWidth,
            @Value("${myagent.browser.viewport-height:800}") int viewportHeight,
            @Value("${myagent.browser.jpeg-quality:60}") int jpegQuality,
            @Value("${myagent.browser.max-fps:15}") int maxFps,
            @Value("${myagent.browser.idle-timeout-seconds:600}") int idleTimeoutSeconds,
            @Value("${myagent.browser.max-concurrent-sessions:1}") int maxConcurrentSessions,
            @Value("${myagent.browser.navigate-timeout-ms:30000}") int navigateTimeoutMs,
            @Value("${myagent.browser.allow-user-input:true}") boolean allowUserInput) {
        this.enabled = enabled;
        this.chromeBinary = chromeBinary;
        this.viewportWidth = viewportWidth;
        this.viewportHeight = viewportHeight;
        this.jpegQuality = jpegQuality;
        this.maxFps = maxFps;
        this.idleTimeoutSeconds = idleTimeoutSeconds;
        this.maxConcurrentSessions = maxConcurrentSessions;
        this.navigateTimeoutMs = navigateTimeoutMs;
        this.allowUserInput = allowUserInput;
    }

    public boolean enabled() {
        return enabled;
    }

    public String chromeBinary() {
        return chromeBinary;
    }

    public int viewportWidth() {
        return viewportWidth;
    }

    public int viewportHeight() {
        return viewportHeight;
    }

    public int jpegQuality() {
        return jpegQuality;
    }

    /** 推帧上限：帧率越高带宽越贵（实测 60fps × 56KB ≈ 3.4MB/s，太肥）。 */
    public int maxFps() {
        return maxFps;
    }

    public int idleTimeoutSeconds() {
        return idleTimeoutSeconds;
    }

    public int maxConcurrentSessions() {
        return maxConcurrentSessions;
    }

    public int navigateTimeoutMs() {
        return navigateTimeoutMs;
    }

    /** 是否允许用户从画面上接管点击/输入。 */
    public boolean allowUserInput() {
        return allowUserInput;
    }

    /** 多域名白名单（留空 = 只靠 ReachUrlPolicy 的通用拦截）。 */
    public List<String> hostAllowList() {
        String raw = System.getenv("MYAGENT_BROWSER_ALLOW_HOSTS");
        return raw == null || raw.isBlank() ? List.of()
                : Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
