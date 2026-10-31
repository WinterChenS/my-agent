package com.myagent.agui.browser.ext;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * 本机浏览器扩展模式配置。
 *
 * <p>模式（{@code myagent.browser.mode}）：
 * <ul>
 *   <li>{@code local-extension}：操作用户本机已登录的真实标签页（本方案默认）；</li>
 *   <li>{@code remote}：旧的「服务端无头 Chromium + 实时画面」模式（保留，可回退）；</li>
 *   <li>{@code disabled}：都不启用。</li>
 * </ul>
 */
@Configuration
public class BrowserExtensionProperties {

    private final String mode;
    private final String extensionId;
    private final int protocolVersion;
    private final String minimumVersion;
    private final String chromeStoreUrl;
    private final String edgeStoreUrl;
    private final List<String> allowedExtensionOrigins;
    private final int pairingTtlSeconds;
    private final int deviceTokenTtlSeconds;
    private final int refreshTokenTtlSeconds;
    private final int controlSessionTtlSeconds;
    private final int maxCommandBytes;
    private final int commandTimeoutSeconds;
    private final String tokenSecret;
    private final int heartbeatTimeoutSeconds;
    private final String internalPackagePath;

    public BrowserExtensionProperties(
            @Value("${myagent.browser.mode:local-extension}") String mode,
            @Value("${myagent.browser.extension.id:}") String extensionId,
            @Value("${myagent.browser.extension.protocol-version:1}") int protocolVersion,
            @Value("${myagent.browser.extension.minimum-version:0.1.0}") String minimumVersion,
            @Value("${myagent.browser.extension.chrome-store-url:}") String chromeStoreUrl,
            @Value("${myagent.browser.extension.edge-store-url:}") String edgeStoreUrl,
            @Value("${myagent.browser.extension.allowed-extension-origins:}") String allowedExtensionOrigins,
            @Value("${myagent.browser.extension.pairing-ttl-seconds:300}") int pairingTtlSeconds,
            @Value("${myagent.browser.extension.device-token-ttl-seconds:600}") int deviceTokenTtlSeconds,
            @Value("${myagent.browser.extension.refresh-token-ttl-seconds:2592000}") int refreshTokenTtlSeconds,
            @Value("${myagent.browser.extension.control-session-ttl-seconds:1800}") int controlSessionTtlSeconds,
            @Value("${myagent.browser.extension.max-command-bytes:65536}") int maxCommandBytes,
            @Value("${myagent.browser.extension.command-timeout-seconds:25}") int commandTimeoutSeconds,
            @Value("${myagent.browser.extension.token-secret:}") String tokenSecret,
            @Value("${myagent.browser.extension.heartbeat-timeout-seconds:60}") int heartbeatTimeoutSeconds,
            @Value("${myagent.browser.extension.internal-package-path:}") String internalPackagePath) {
        this.mode = mode == null ? "local-extension" : mode.trim();
        this.extensionId = extensionId == null ? "" : extensionId.trim();
        this.protocolVersion = protocolVersion;
        this.minimumVersion = minimumVersion;
        this.chromeStoreUrl = blankToNull(chromeStoreUrl);
        this.edgeStoreUrl = blankToNull(edgeStoreUrl);
        this.allowedExtensionOrigins = split(allowedExtensionOrigins);
        this.pairingTtlSeconds = pairingTtlSeconds;
        this.deviceTokenTtlSeconds = deviceTokenTtlSeconds;
        this.refreshTokenTtlSeconds = refreshTokenTtlSeconds;
        this.controlSessionTtlSeconds = controlSessionTtlSeconds;
        this.maxCommandBytes = maxCommandBytes;
        this.commandTimeoutSeconds = commandTimeoutSeconds;
        this.tokenSecret = tokenSecret == null ? "" : tokenSecret.trim();
        this.heartbeatTimeoutSeconds = heartbeatTimeoutSeconds;
        this.internalPackagePath = blankToNull(internalPackagePath);
    }

    private static java.util.List<String> split(String raw) {
        if (raw == null || raw.isBlank()) {
            return java.util.List.of();
        }
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public String mode() {
        return mode;
    }

    public boolean localMode() {
        return "local-extension".equalsIgnoreCase(mode);
    }

    public boolean remoteMode() {
        return "remote".equalsIgnoreCase(mode);
    }

    public boolean disabled() {
        return "disabled".equalsIgnoreCase(mode);
    }

    /** 固定扩展 ID（manifest.key 决定）：网页用它做 externally_connectable 检测/一键连接。 */
    public String extensionId() {
        return extensionId;
    }

    public int protocolVersion() {
        return protocolVersion;
    }

    public String minimumVersion() {
        return minimumVersion;
    }

    public String chromeStoreUrl() {
        return chromeStoreUrl;
    }

    public String edgeStoreUrl() {
        return edgeStoreUrl;
    }

    public List<String> allowedExtensionOrigins() {
        return allowedExtensionOrigins;
    }

    public int pairingTtlSeconds() {
        return pairingTtlSeconds;
    }

    public int deviceTokenTtlSeconds() {
        return deviceTokenTtlSeconds;
    }

    public int refreshTokenTtlSeconds() {
        return refreshTokenTtlSeconds;
    }

    public int controlSessionTtlSeconds() {
        return controlSessionTtlSeconds;
    }

    public int maxCommandBytes() {
        return maxCommandBytes;
    }

    public int commandTimeoutSeconds() {
        return commandTimeoutSeconds;
    }

    public String tokenSecret() {
        return tokenSecret;
    }

    public int heartbeatTimeoutSeconds() {
        return heartbeatTimeoutSeconds;
    }

    public String internalPackagePath() {
        return internalPackagePath;
    }
}
