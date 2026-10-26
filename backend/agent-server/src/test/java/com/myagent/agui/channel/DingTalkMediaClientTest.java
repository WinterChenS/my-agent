package com.myagent.agui.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 钉钉媒体客户端：用 JDK 内置 HttpServer 桩住钉钉接口，验证「换 token → 上传 → 发消息」的完整契约。
 *
 * <p>为什么要这么测：这段是官方渠道模块没有、我们手写的协议代码，出错的代价是用户发的图/文件悄悄丢掉。
 */
class DingTalkMediaClientTest {

    private HttpServer server;
    private final Map<String, String> requests = new ConcurrentHashMap<>();
    private final List<String> bodies = new ArrayList<>();
    private DingTalkMediaClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(null);
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new DingTalkMediaClient("app-key", "app-secret", "robot-1", base, base);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();
        requests.put(path, query == null ? "" : query);
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        bodies.add(path + "|" + body);
        String response;
        switch (path) {
            case "/v1.0/oauth2/accessToken" -> response = "{\"accessToken\":\"v1-token\",\"expireIn\":7200}";
            case "/gettoken" -> response = "{\"access_token\":\"oapi-token\",\"expires_in\":7200}";
            case "/v1.0/robot/messageFiles/download" ->
                    response = "{\"downloadUrl\":\"http://127.0.0.1:" + server.getAddress().getPort() + "/files/a.png\"}";
            case "/files/a.png" -> {
                byte[] bytes = "PNGDATA".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
                return;
            }
            case "/media/upload" -> response = "{\"media_id\":\"@media-1\"}";
            case "/v1.0/robot/oToMessages/batchSend", "/v1.0/robot/groupMessages/send" ->
                    response = "{\"processQueryKey\":\"q1\"}";
            default -> response = "{}";
        }
        byte[] out = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    @Test
    void 下载用downloadCode换地址再取字节() {
        byte[] bytes = client.download("code-1");

        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("PNGDATA");
        assertThat(requests.get("/v1.0/robot/messageFiles/download"))
                .contains("downloadCode=code-1")
                .contains("robotCode=robot-1");
        assertThat(requests).containsKey("/v1.0/oauth2/accessToken");
    }

    @Test
    void 发图片走sampleImageMsg_单聊() {
        client.sendMedia(true, "staff-1", "a.png", "PNGDATA".getBytes(StandardCharsets.UTF_8));

        assertThat(requests).containsKey("/media/upload");
        assertThat(requests.get("/media/upload")).contains("type=image");
        assertThat(bodies).anySatisfy(body -> assertThat(body)
                .contains("/v1.0/robot/oToMessages/batchSend")
                .contains("sampleImageMsg")
                .contains("@media-1")
                .contains("staff-1"));
    }

    @Test
    void 发文件走sampleFile_群聊() {
        client.sendMedia(false, "cid-1", "报告.pdf", "PDF".getBytes(StandardCharsets.UTF_8));

        assertThat(requests.get("/media/upload")).contains("type=file");
        assertThat(bodies).anySatisfy(body -> assertThat(body)
                .contains("/v1.0/robot/groupMessages/send")
                .contains("sampleFile")
                .contains("openConversationId")
                .contains("cid-1")
                .contains("fileType")
                .contains("pdf"));
    }

    @Test
    void token会被缓存() {
        client.v1Token();
        client.v1Token();
        client.oapiToken();
        client.oapiToken();
        // 换 token 的请求各只发一次
        long v1Calls = bodies.stream().filter(b -> b.startsWith("/v1.0/oauth2/accessToken")).count();
        long oapiCalls = bodies.stream().filter(b -> b.startsWith("/gettoken")).count();
        assertThat(v1Calls).isEqualTo(1);
        assertThat(oapiCalls).isEqualTo(1);
    }

    @Test
    void 超大媒体直接拒绝() {
        byte[] tooLarge = new byte[DingTalkMediaClient.MAX_MEDIA_BYTES + 1];
        assertThatThrownBy(() -> client.sendMedia(true, "staff-1", "big.zip", tooLarge))
                .isInstanceOf(DingTalkMediaClient.ChannelMediaException.class)
                .hasMessageContaining("上限");
    }

    @Test
    void 扩展名与图片判定() {
        assertThat(DingTalkMediaClient.extension("a.PNG")).isEqualTo("png");
        assertThat(DingTalkMediaClient.extension("noext")).isEmpty();
        assertThat(DingTalkMediaClient.isImage("x.jpeg")).isTrue();
        assertThat(DingTalkMediaClient.isImage("x.pdf")).isFalse();
    }
}
