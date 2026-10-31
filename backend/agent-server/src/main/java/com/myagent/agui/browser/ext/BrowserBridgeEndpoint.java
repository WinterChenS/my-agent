package com.myagent.agui.browser.ext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.Session;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 扩展长连接的端点：负责「首帧鉴权」和帧分发。
 *
 * <p>安全边界：未认证连接只允许 {@code auth} / {@code auth.refresh} 两种帧；
 * 其他任何帧一律拒绝并关闭连接，避免未认证连接被当成执行通道。
 */
@Component
public class BrowserBridgeEndpoint extends Endpoint {

    private static final Logger log = LoggerFactory.getLogger(BrowserBridgeEndpoint.class);

    /** 首帧鉴权窗口：超过这个时间没发 auth 就断开（防止白占连接）。 */
    private static final long AUTH_WINDOW_MILLIS = 5_000;

    private final BrowserBridgeHub hub;
    private final DeviceTokenService tokens;
    private final BrowserExtensionStore store;
    private final BrowserControlSessionService sessions;
    private final BrowserExtensionProperties properties;

    public BrowserBridgeEndpoint(
            BrowserBridgeHub hub,
            DeviceTokenService tokens,
            BrowserExtensionStore store,
            BrowserControlSessionService sessions,
            BrowserExtensionProperties properties) {
        this.hub = hub;
        this.tokens = tokens;
        this.store = store;
        this.sessions = sessions;
        this.properties = properties;
    }

    @Override
    public void onOpen(Session session, EndpointConfig config) {
        session.setMaxTextMessageBufferSize(Math.max(16 * 1024, properties.maxCommandBytes() + 16 * 1024));
        session.addMessageHandler(new jakarta.websocket.MessageHandler.Whole<String>() {
            @Override
            public void onMessage(String message) {
                onText(session, message);
            }
        });
        // 5 秒内必须完成鉴权，否则断开（避免匿名连接白占槽位）
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(AUTH_WINDOW_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!isAuthenticated(session)) {
                hub.closeQuietly(session, "未在时限内完成鉴权");
            }
        }, "browser-bridge-auth-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private boolean isAuthenticated(Session session) {
        return authenticated.contains(session.getId());
    }

    private final java.util.Set<String> authenticated = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override
    public void onClose(Session session, jakarta.websocket.CloseReason reason) {
        authenticated.remove(session.getId());
        hub.unregister(session);
    }

    @Override
    public void onError(Session session, Throwable error) {
        log.debug("浏览器扩展连接异常：{}", error.getMessage());
        authenticated.remove(session.getId());
        hub.unregister(session);
    }

    void onText(Session session, String text) {
        JsonNode frame;
        try {
            frame = BrowserBridgeHub.mapper().readTree(text);
        } catch (Exception e) {
            hub.closeQuietly(session, "帧不是合法 JSON");
            return;
        }
        String type = frame.path("type").asText("");
        boolean authed = isAuthenticated(session);
        try {
            switch (type) {
                case "auth" -> authenticate(session, frame, false);
                case "auth.refresh" -> authenticate(session, frame, true);
                case "heartbeat" -> {
                    if (authed) {
                        hub.connectionOf(session).ifPresent(c -> hub.touch(c.deviceId()));
                    }
                }
                case "result" -> {
                    if (authed) {
                        hub.completePending(frame);
                    }
                }
                case "tab.state" -> {
                    if (authed) {
                        hub.connectionOf(session).ifPresent(conn -> sessions.onTabState(conn, frame));
                    }
                }
                case "event" -> {
                    if (authed) {
                        hub.connectionOf(session).ifPresent(conn -> sessions.onExtensionEvent(conn, frame));
                    }
                }
                default -> {
                    if (!authed) {
                        hub.closeQuietly(session, "未鉴权");
                    } else {
                        log.debug("收到未知帧类型：{}", type);
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("处理扩展帧失败 type={}：{}", type, e.getMessage());
            if (authed) {
                ObjectNode error = BrowserBridgeHub.frame("error");
                error.put("message", e.getMessage() == null ? "服务端处理失败" : e.getMessage());
                hub.send(session, error);
            } else {
                hub.closeQuietly(session, "鉴权失败");
            }
        }
    }

    private void authenticate(Session session, JsonNode frame, boolean refresh) {
        String token = frame.path("token").asText("");
        DeviceTokenService.Claims claims = tokens.verify(token);
        if (claims == null || claims.expired()) {
            reject(session, "AUTH_INVALID", "设备令牌无效或已过期");
            return;
        }
        Optional<BrowserExtensionStore.DeviceRow> device = store.deviceById(claims.deviceId());
        if (device.isEmpty() || !"ACTIVE".equals(device.get().status())) {
            reject(session, "DEVICE_REVOKED", "设备已被撤销，请重新配对");
            return;
        }
        if (device.get().userId() != claims.userId()
                || device.get().credentialVersion() != claims.credentialVersion()) {
            reject(session, "CREDENTIAL_STALE", "凭证版本已更新，请重新连接");
            return;
        }
        int protocol = frame.path("protocolVersion").asInt(properties.protocolVersion());
        String extensionVersion = frame.path("extensionVersion").asText(null);
        store.touchDevice(device.get().id(), extensionVersion, protocol);
        BrowserBridgeHub.Conn conn = new BrowserBridgeHub.Conn(
                device.get().id(), device.get().userId(), session, java.time.Instant.now(),
                new java.util.concurrent.atomic.AtomicLong(hub.nextEpoch()));
        hub.register(conn);
        authenticated.add(session.getId());
        ObjectNode ok = BrowserBridgeHub.frame("auth.ok");
        ok.put("userId", conn.userId());
        ok.put("deviceId", conn.deviceId());
        ok.put("connectionEpoch", conn.epoch().get());
        ok.put("protocolVersion", properties.protocolVersion());
        ok.put("commandTimeoutSeconds", properties.commandTimeoutSeconds());
        hub.send(session, ok);
        if (refresh) {
            log.info("设备 {} 续签连接成功", conn.deviceId());
        } else {
            log.info("浏览器扩展已连接 deviceId={} version={} protocol={}",
                    conn.deviceId(), extensionVersion, protocol);
        }
    }

    private void reject(Session session, String code, String message) {
        ObjectNode error = BrowserBridgeHub.frame("auth.error");
        error.put("errorCode", code);
        error.put("message", message);
        hub.send(session, error);
        hub.closeQuietly(session, message);
    }
}
