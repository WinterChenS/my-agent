package com.myagent.agui.browser.ext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.websocket.CloseReason;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpointConfig;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * 扩展与平台之间的长连接（WSS/WS 双向命令通道）。
 *
 * <p>为什么用原生 Jakarta WebSocket 而不是 Spring 的 WebSocket 支持：本项目只需要「一条连接 + 帧收发」，
 * 不需要 STOMP/消息代理；Tomcat 自带 jakarta.websocket 实现，不引入额外依赖（离线构建友好）。
 *
 * <p>连接生命周期：扩展主动连 → 5 秒内发 {@code auth} 首帧（设备令牌）→ 校验通过才允许执行命令。
 * 未认证连接不能下发任何命令，也不会被注册为设备。
 */
@Component
public class BrowserBridgeHub {

    private static final Logger log = LoggerFactory.getLogger(BrowserBridgeHub.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final String ENDPOINT_PATH = "/api/browser/bridge/ws";

    /** 一条已认证连接。connectionEpoch 每次重连递增：旧连接下发的命令一律失效。 */
    public record Conn(
            String deviceId, long userId, Session session, Instant connectedAt, AtomicLong epoch) {}

    private final Map<String, Conn> byDevice = new ConcurrentHashMap<>();
    private final Map<String, Conn> bySessionId = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastSeen = new ConcurrentHashMap<>();
    private final AtomicLong epochs = new AtomicLong();

    public void register(Conn conn) {
        Conn previous = byDevice.put(conn.deviceId(), conn);
        if (previous != null && !previous.session().getId().equals(conn.session().getId())) {
            // 同一设备只保留一个主连接；旧连接必须可见地断开，不能让两个连接同时执行
            log.info("设备 {} 重新连接，关闭旧连接 {}", conn.deviceId(), previous.session().getId());
            closeQuietly(previous.session(), "新的连接已建立");
        }
        bySessionId.put(conn.session().getId(), conn);
        lastSeen.put(conn.deviceId(), Instant.now());
    }

    public void unregister(Session session) {
        Conn conn = bySessionId.remove(session.getId());
        if (conn == null) {
            return;
        }
        byDevice.remove(conn.deviceId(), conn);
        lastSeen.remove(conn.deviceId());
        failPendingOf(conn.deviceId(), "DEVICE_OFFLINE", "浏览器连接已断开");
        log.info("浏览器扩展连接关闭 deviceId={}", conn.deviceId());
    }

    public Optional<Conn> connection(String deviceId) {
        return Optional.ofNullable(byDevice.get(deviceId));
    }

    public Optional<Conn> connectionOf(Session session) {
        return Optional.ofNullable(bySessionId.get(session.getId()));
    }

    public boolean online(String deviceId) {
        return byDevice.containsKey(deviceId);
    }

    public void touch(String deviceId) {
        lastSeen.put(deviceId, Instant.now());
    }

    public Instant lastSeen(String deviceId) {
        return lastSeen.get(deviceId);
    }

    public long nextEpoch() {
        return epochs.incrementAndGet();
    }

    /** 发送一帧（不等响应）。连接不可用返回 false。 */
    public boolean send(String deviceId, ObjectNode frame) {
        Conn conn = byDevice.get(deviceId);
        if (conn == null) {
            return false;
        }
        return send(conn, frame);
    }

    public boolean send(Session session, ObjectNode frame) {
        Conn conn = bySessionId.get(session.getId());
        return conn != null && send(conn, frame);
    }

    private boolean send(Conn conn, ObjectNode frame) {
        try {
            String text = MAPPER.writeValueAsString(frame);
            // Tomcat 允许并发发送但内部会串行化；这里显式加锁，避免部分容器抛 IllegalStateException
            synchronized (conn.session()) {
                conn.session().getBasicRemote().sendText(text);
            }
            return true;
        } catch (IOException | IllegalStateException e) {
            log.warn("向设备 {} 发送帧失败：{}", conn.deviceId(), e.getMessage());
            return false;
        }
    }

    /**
     * 下发命令并等待扩展的结果。
     *
     * <p>超时不等于没执行：网络慢、动作已提交都可能发生，结果标记 {@code OUTCOME_UNKNOWN}，
     * 由上层决定是否重试（写动作一律不自动重放）。
     */
    public CompletableFuture<JsonNode> request(String deviceId, ObjectNode command, int timeoutSeconds) {
        String requestId = command.path("requestId").asText();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        if (!online(deviceId)) {
            future.completeExceptionally(new BridgeException("DEVICE_OFFLINE", "浏览器扩展不在线"));
            return future;
        }
        pending.put(requestId, future);
        if (!send(deviceId, command)) {
            pending.remove(requestId);
            future.completeExceptionally(new BridgeException("DEVICE_OFFLINE", "发送命令失败，浏览器可能已断开"));
            return future;
        }
        return future.completeOnTimeout(
                        MAPPER.createObjectNode()
                                .put("type", "result")
                                .put("requestId", requestId)
                                .put("status", "ERROR")
                                .put("errorCode", "OUTCOME_UNKNOWN")
                                .put("message", "浏览器扩展在 " + timeoutSeconds + " 秒内没有返回结果，操作可能已执行"),
                        Math.max(5, timeoutSeconds),
                        java.util.concurrent.TimeUnit.SECONDS)
                .whenComplete((r, e) -> pending.remove(requestId));
    }

    /** 收到扩展的结果帧。 */
    public boolean completePending(JsonNode frame) {
        String requestId = frame.path("requestId").asText();
        CompletableFuture<JsonNode> future = pending.remove(requestId);
        if (future == null) {
            log.debug("收到没有等待者的结果 requestId={}", requestId);
            return false;
        }
        future.complete(frame);
        return true;
    }

    public void failPendingOf(String deviceId, String errorCode, String message) {
        pending.entrySet().removeIf(entry -> {
            CompletableFuture<JsonNode> future = entry.getValue();
            future.complete(MAPPER.createObjectNode()
                    .put("type", "result")
                    .put("requestId", entry.getKey())
                    .put("status", "ERROR")
                    .put("errorCode", errorCode)
                    .put("message", message));
            return true;
        });
    }

    public static ObjectNode frame(String type) {
        return MAPPER.createObjectNode().put("type", type);
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public void closeQuietly(Session session, String reason) {
        try {
            session.close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, reason));
        } catch (IOException ignored) {
            // 已经断了就算了
        }
    }

    /** 通道异常：错误码与方案第 9 节的错误码表一致。 */
    public static class BridgeException extends RuntimeException {
        private final String errorCode;

        public BridgeException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        public String errorCode() {
            return errorCode;
        }
    }

    /**
     * 把原生 WebSocket 端点注册进 Tomcat（等价于 Spring 的 ServerEndpointExporter）。
     *
     * <p>为什么挂 {@link jakarta.servlet.ServletContextListener} 而不是直接在
     * {@code ServletContextInitializer} 里注册：Tomcat 的 {@code ServerContainer} 属性是由
     * {@code WsSci}（一个 ServletContainerInitializer）在启动早期设置的，而 Boot 的
     * {@code ServletContextInitializer} 回调可能早于它 —— 直接注册会拿到 null（实测就是这个，
     * 表现为 WS 握手 404）。Listener 的 {@code contextInitialized} 一定在所有 SCI 之后。
     */
    @Bean
    public ServletContextInitializer browserBridgeEndpointRegistrar(BrowserBridgeEndpoint endpoint) {
        return servletContext -> {
            servletContext.addListener(new jakarta.servlet.ServletContextListener() {
                @Override
                public void contextInitialized(jakarta.servlet.ServletContextEvent event) {
                    registerEndpoint(event.getServletContext(), endpoint);
                }
            });
        };
    }

    private void registerEndpoint(jakarta.servlet.ServletContext servletContext, BrowserBridgeEndpoint endpoint) {
        Object attribute = servletContext.getAttribute(ServerContainer.class.getName());
        if (!(attribute instanceof ServerContainer container)) {
            log.warn("当前容器不支持 WebSocket，浏览器扩展通道不可用（path={}）", ENDPOINT_PATH);
            return;
        }
        ServerEndpointConfig config = ServerEndpointConfig.Builder
                .create(BrowserBridgeEndpoint.class, ENDPOINT_PATH)
                .configurator(new ServerEndpointConfig.Configurator() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <T> T getEndpointInstance(Class<T> clazz) {
                        return (T) endpoint;
                    }
                })
                .build();
        try {
            container.addEndpoint(config);
            log.info("浏览器扩展通道已注册：{}", ENDPOINT_PATH);
        } catch (jakarta.websocket.DeploymentException | IllegalStateException e) {
            log.error("注册浏览器扩展通道失败：{}", e.getMessage(), e);
        }
    }
}
