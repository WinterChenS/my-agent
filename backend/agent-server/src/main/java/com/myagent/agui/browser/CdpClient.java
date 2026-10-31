package com.myagent.agui.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 极简 Chrome DevTools Protocol 客户端（JDK 自带 WebSocket，**不引入任何新依赖**）。
 *
 * <p>为什么不用 Playwright/Selenium：本项目一直是"离线构建 + 服务器只跑运行时镜像"，
 * 多一个 Maven 依赖就要多一条下载链路；而 CDP 本身就是一个 JSON 协议，
 * JDK 11+ 自带 {@link WebSocket}，够用且完全可控。
 *
 * <p>能力：命令-响应匹配（id）、事件按 {@code sessionId + method} 分发、扁平化会话（flatten）。
 */
public class CdpClient {

    private static final Logger log = LoggerFactory.getLogger(CdpClient.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final List<BiConsumer<String, JsonNode>> listeners = new CopyOnWriteArrayList<>();
    private final StringBuilder buffer = new StringBuilder();

    private volatile WebSocket socket;

    /** 连上 browser 级 WS 端点。 */
    public void connect(String webSocketUrl) {
        HttpClient client = HttpClient.newHttpClient();
        WebSocket.Listener listener = new WebSocket.Listener() {
            @Override
            public void onOpen(WebSocket webSocket) {
                webSocket.request(Long.MAX_VALUE);
            }

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                // CDP 的一条消息可能被拆成多片（last=false），必须自己拼回去
                synchronized (buffer) {
                    buffer.append(data);
                    if (last) {
                        String text = buffer.toString();
                        buffer.setLength(0);
                        handle(text);
                    }
                }
                return null;
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                log.warn("CDP WebSocket 出错：{}", error.toString());
            }
        };
        this.socket = client.newWebSocketBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(15))
                .buildAsync(URI.create(webSocketUrl), listener)
                .join();
        log.info("CDP 已连接 {}", webSocketUrl);
    }

    public boolean isOpen() {
        WebSocket current = socket;
        return current != null && !current.isOutputClosed();
    }

    public void close() {
        WebSocket current = socket;
        socket = null;
        if (current != null) {
            try {
                current.sendClose(WebSocket.NORMAL_CLOSURE, "bye").orTimeout(2, TimeUnit.SECONDS);
            } catch (RuntimeException ignored) {
                // 关闭失败无所谓
            }
        }
        pending.values().forEach(future -> future.completeExceptionally(new IllegalStateException("CDP 已关闭")));
        pending.clear();
    }

    /** 事件订阅：{@code (method, params) -> void}。 */
    public void on(String method, BiConsumer<String, JsonNode> handler) {
        listeners.add((name, payload) -> {
            if (method == null || method.equals(name)) {
                handler.accept(name, payload);
            }
        });
    }

    /** 发命令（page 级命令要带 sessionId）。返回 params 节点。 */
    public JsonNode send(String method, ObjectNode params, String sessionId) {
        WebSocket current = socket;
        if (current == null) {
            throw new IllegalStateException("CDP 未连接");
        }
        long id = ids.incrementAndGet();
        ObjectNode message = mapper.createObjectNode();
        message.put("id", id);
        message.put("method", method);
        message.set("params", params == null ? mapper.createObjectNode() : params);
        if (sessionId != null) {
            message.put("sessionId", sessionId);
        }
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            current.sendText(mapper.writeValueAsString(message), true).join();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            pending.remove(id);
            throw new IllegalStateException("CDP 消息序列化失败", e);
        }
        try {
            JsonNode response = future.get(30, TimeUnit.SECONDS);
            JsonNode error = response.path("error");
            if (!error.isMissingNode() && !error.isNull()) {
                throw new IllegalStateException("CDP " + method + " 失败：" + error.path("message").asText(""));
            }
            return response.path("result");
        } catch (java.util.concurrent.TimeoutException e) {
            pending.remove(id);
            throw new IllegalStateException("CDP " + method + " 超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("CDP 调用被中断");
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("CDP " + method + " 异常：" + e.getCause());
        }
    }

    /**
     * 只发不等响应（fire-and-forget）。
     *
     * <p><b>为什么必须有它</b>：JDK 的 WebSocket 是**顺序回调**——{@code onText} 里如果同步等另一个
     * CDP 响应，接收线程就被自己堵死，后续消息（包括我们正等的那个响应）永远读不到，表现为
     * "命令莫名其妙超时 30 秒"。实测踩过：在 screencastFrame 事件里同步 ack，导致 navigate/click 全部超时。
     * 所以事件回调里发起的命令一律用这个。
     *
     * <p><b>但 id 必须带</b>：CDP 规定"没有 id 的消息"是浏览器→客户端的**事件**；
     * 客户端发出去的命令如果不带 id，Chrome 会当成非法 notification **直接忽略**。
     * 实测踩过：ack 不带 id → Chrome 认为没被确认 → **推完第一帧就不再推帧**，
     * 面板永远停在第一次的画面（用户反馈"后面再操作画面不动"就是这个）。
     * 所以要带 id，只是**不注册等待 future**。
     */
    public void fire(String method, ObjectNode params, String sessionId) {
        WebSocket current = socket;
        if (current == null) {
            return;
        }
        ObjectNode message = mapper.createObjectNode();
        message.put("id", ids.incrementAndGet());
        message.put("method", method);
        message.set("params", params == null ? mapper.createObjectNode() : params);
        if (sessionId != null) {
            message.put("sessionId", sessionId);
        }
        try {
            current.sendText(mapper.writeValueAsString(message), true);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.debug("CDP fire 失败 {}: {}", method, e.toString());
        }
    }

    private void handle(String text) {
        JsonNode node;
        try {
            node = mapper.readTree(text);
        } catch (Exception e) {
            log.debug("CDP 消息解析失败：{}", e.getMessage());
            return;
        }
        JsonNode idNode = node.path("id");
        if (!idNode.isMissingNode()) {
            CompletableFuture<JsonNode> future = pending.remove(idNode.asLong());
            if (future != null) {
                future.complete(node);
            }
            return;
        }
        String method = node.path("method").asText("");
        if (method.isEmpty()) {
            return;
        }
        JsonNode params = node.path("params");
        for (BiConsumer<String, JsonNode> listener : listeners) {
            try {
                listener.accept(method, params);
            } catch (RuntimeException e) {
                log.debug("CDP 事件处理失败 {}: {}", method, e.getMessage());
            }
        }
    }
}
