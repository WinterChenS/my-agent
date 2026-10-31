package com.myagent.agui.browser.ext;

import com.myagent.common.BizException;
import com.myagent.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 浏览器扩展的 REST 接口：安装引导、配对、设备、控制会话。
 *
 * <p>配对相关的三个接口是**未登录**可访问的（扩展还没有账号身份），因此单独做了限流：
 * 创建配对每 IP 10 次/分钟、领取凭证每 IP 20 次/分钟，避免被刷。
 */
@RestController
@RequestMapping("/api/browser/extension")
public class BrowserExtensionController {

    private static final Logger log = LoggerFactory.getLogger(BrowserExtensionController.class);

    private final BrowserExtensionProperties properties;
    private final BrowserPairingService pairing;
    private final BrowserExtensionStore store;
    private final BrowserBridgeHub hub;
    private final BrowserControlSessionService sessions;
    private final BrowserToolService tools;
    private final RateLimiter rateLimiter = new RateLimiter();

    public BrowserExtensionController(
            BrowserExtensionProperties properties,
            BrowserPairingService pairing,
            BrowserExtensionStore store,
            BrowserBridgeHub hub,
            BrowserControlSessionService sessions,
            BrowserToolService tools) {
        this.properties = properties;
        this.pairing = pairing;
        this.store = store;
        this.hub = hub;
        this.sessions = sessions;
        this.tools = tools;
    }

    // ---------------- 网页：安装/配置 ----------------

    @GetMapping("/config")
    public Result<Map<String, Object>> config() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", properties.mode());
        body.put("extensionId", properties.extensionId());
        body.put("protocolVersion", properties.protocolVersion());
        body.put("minimumVersion", properties.minimumVersion());
        body.put("chromeStoreUrl", properties.chromeStoreUrl());
        body.put("edgeStoreUrl", properties.edgeStoreUrl());
        String packagePath = properties.internalPackagePath();
        body.put("internalPackagePath", packagePath);
        // 绝对地址由配置推导：配了 public-base-url 就给绝对链接，没配就用相对路径
        // （浏览器会用当前来源，内网/多域名部署也不用改代码）
        body.put("extensionPackageUrl", absolute(properties.platformBaseUrl(), packagePath));
        body.put("platformUrl", properties.platformBaseUrl());
        body.put("commandTimeoutSeconds", properties.commandTimeoutSeconds());
        body.put("controlSessionTtlSeconds", properties.controlSessionTtlSeconds());
        return Result.ok(body);
    }

    // ---------------- 扩展：配对 ----------------

    public record CreatePairingRequest(
            String installId, String pkceChallenge, String displayName, String browser) {}

    @PostMapping("/pairings")
    public Result<Map<String, Object>> createPairing(
            @RequestBody CreatePairingRequest request, HttpServletRequest http) {
        rateLimiter.check("pair-create:" + clientIp(http), 10);
        var created = pairing.create(
                request.installId(), request.pkceChallenge(), request.displayName(), request.browser());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", created.id());
        body.put("deviceCode", created.deviceCode());
        body.put("userCode", created.userCode());
        body.put("verificationUri", created.verificationUri());
        body.put("expiresIn", created.expiresIn());
        body.put("interval", created.interval());
        return Result.ok(body);
    }

    /** 配对页预览（需要登录）：展示设备名/浏览器/剩余时间，让用户确认。 */
    @GetMapping("/pairings/{userCode}")
    public Result<Map<String, Object>> previewPairing(@PathVariable String userCode) {
        var preview = pairing.preview(userCode);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userCode", preview.userCode());
        body.put("displayName", preview.displayName());
        body.put("browser", preview.browser());
        body.put("status", preview.status());
        body.put("expiresIn", preview.expiresInSeconds());
        return Result.ok(body);
    }

    @PostMapping("/pairings/approve")
    public Result<Map<String, Object>> approvePairing(
            @AuthenticationPrincipal com.myagent.auth.JwtPrincipal principal,
            @RequestBody Map<String, String> body) {
        pairing.approve(principal.userId(), body.get("userCode"));
        return Result.ok(Map.of("approved", true));
    }

    public record ClaimRequest(
            String deviceCode, String verifier, String extensionVersion, Integer protocolVersion) {}

    @PostMapping("/pairings/token")
    public Result<Map<String, Object>> claimPairing(@RequestBody ClaimRequest request, HttpServletRequest http) {
        rateLimiter.check("pair-claim:" + clientIp(http), 40);
        var tokens = pairing.claim(
                request.deviceCode(), request.verifier(), request.extensionVersion(),
                request.protocolVersion() == null ? properties.protocolVersion() : request.protocolVersion());
        return Result.ok(tokenBody(tokens));
    }

    @PostMapping("/token/refresh")
    public Result<Map<String, Object>> refresh(@RequestBody Map<String, String> body, HttpServletRequest http) {
        rateLimiter.check("token-refresh:" + clientIp(http), 30);
        return Result.ok(tokenBody(pairing.refresh(body.get("refreshToken"))));
    }

    private static Map<String, Object> tokenBody(BrowserPairingService.DeviceTokens tokens) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accessToken", tokens.accessToken());
        body.put("refreshToken", tokens.refreshToken());
        body.put("expiresIn", tokens.expiresIn());
        body.put("deviceId", tokens.deviceId());
        body.put("userId", tokens.userId());
        body.put("credentialVersion", tokens.credentialVersion());
        return body;
    }

    // ---------------- 网页：设备 ----------------

    @GetMapping("/devices")
    public Result<List<Map<String, Object>>> devices(@AuthenticationPrincipal com.myagent.auth.JwtPrincipal p) {
        List<Map<String, Object>> list = store.devicesOf(p.userId()).stream().map(d -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", d.id());
            item.put("displayName", d.displayName());
            item.put("browser", d.browser());
            item.put("extensionVersion", d.extensionVersion());
            item.put("status", d.status());
            item.put("online", hub.online(d.id()));
            item.put("createdAt", null);
            item.put("lastSeenAt", d.lastSeenAt() == null ? null : d.lastSeenAt().toString());
            return item;
        }).toList();
        return Result.ok(list);
    }

    @DeleteMapping("/devices/{id}")
    public Result<Map<String, Object>> revokeDevice(
            @AuthenticationPrincipal com.myagent.auth.JwtPrincipal p, @PathVariable String id) {
        int changed = store.revokeDevice(id, p.userId());
        store.bumpCredentialVersion(id);
        hub.connection(id).ifPresent(conn -> {
            hub.send(id, BrowserBridgeHub.frame("revoked").put("message", "设备已被用户在网页端撤销"));
            hub.closeQuietly(conn.session(), "设备已被撤销");
        });
        log.info("用户 {} 撤销浏览器设备 {}（changed={}）", p.userId(), id, changed);
        return Result.ok(Map.of("revoked", changed > 0));
    }

    // ---------------- 网页：状态与控制会话 ----------------

    @GetMapping("/status")
    public Result<Map<String, Object>> status(
            @AuthenticationPrincipal com.myagent.auth.JwtPrincipal p,
            @RequestParam(required = false) String conversationId) {
        Map<String, Object> body = tools.status(p.userId(), conversationId);
        body.put("sessions", sessions.activeOf(p.userId(), conversationId).map(List::of).orElse(List.of()));
        return Result.ok(body);
    }

    public record CreateSessionRequest(String conversationId, String deviceId) {}

    @PostMapping("/control-sessions")
    public Result<Map<String, Object>> createSession(
            @AuthenticationPrincipal com.myagent.auth.JwtPrincipal p,
            @RequestBody CreateSessionRequest request) {
        var row = sessions.ensureSession(
                p.userId(), request.conversationId(),
                request.deviceId() != null ? request.deviceId() : sessions.defaultDevice(p.userId()));
        return Result.ok(sessionBody(row));
    }

    @GetMapping("/control-sessions/{id}")
    public Result<Map<String, Object>> getSession(
            @AuthenticationPrincipal com.myagent.auth.JwtPrincipal p, @PathVariable String id) {
        return Result.ok(sessionBody(sessions.requireOwned(p.userId(), id)));
    }

    @PostMapping("/control-sessions/{id}/pause")
    public Result<Map<String, Object>> pause(
            @AuthenticationPrincipal com.myagent.auth.JwtPrincipal p, @PathVariable String id) {
        return Result.ok(sessionBody(sessions.pause(p.userId(), id)));
    }

    @PostMapping("/control-sessions/{id}/resume")
    public Result<Map<String, Object>> resume(
            @AuthenticationPrincipal com.myagent.auth.JwtPrincipal p, @PathVariable String id) {
        return Result.ok(sessionBody(sessions.resume(p.userId(), id)));
    }

    @DeleteMapping("/control-sessions/{id}")
    public Result<Map<String, Object>> revoke(
            @AuthenticationPrincipal com.myagent.auth.JwtPrincipal p, @PathVariable String id) {
        sessions.revoke(p.userId(), id);
        return Result.ok(Map.of("revoked", true));
    }

    private static Map<String, Object> sessionBody(BrowserExtensionStore.SessionRow row) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", row.id());
        body.put("deviceId", row.deviceId());
        body.put("conversationId", row.conversationId());
        body.put("tabHandle", row.tabHandle());
        body.put("origin", row.origin());
        body.put("title", row.title());
        body.put("state", row.state());
        body.put("leaseVersion", row.leaseVersion());
        body.put("pageRevision", row.pageRevision());
        body.put("lastError", row.lastError());
        body.put("expiresAt", row.expiresAt().toString());
        return body;
    }

    private static String absolute(String baseUrl, String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            return path;
        }
        return baseUrl + (path.startsWith("/") ? path : "/" + path);
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /** 极简滑动窗口限流（单实例够用；多实例应换 Redis）。 */
    static class RateLimiter {
        private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

        void check(String key, int perMinute) {
            long now = Instant.now().toEpochMilli();
            Deque<Long> window = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
            synchronized (window) {
                while (!window.isEmpty() && now - window.peekFirst() > 60_000) {
                    window.pollFirst();
                }
                if (window.size() >= perMinute) {
                    throw new BizException(42902, "操作太频繁，请稍后再试", HttpStatus.TOO_MANY_REQUESTS);
                }
                window.addLast(now);
            }
            if (hits.size() > 10_000) {
                hits.clear();
            }
        }
    }
}
