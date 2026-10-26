package com.myagent.agui.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 钉钉「媒体」收发客户端（官方渠道模块没有的部分）。
 *
 * <p>AgentScope 2.0.4 的钉钉渠道只做两件事：<b>入站</b>把图片/文件识别成
 * {@code channelMedia*} 元数据（不下载），<b>出站</b>只发 text / markdown。
 * 所以「下载收到的图/文件」和「把图/文件发出去」需要平台自己补，这里就是那一层。
 *
 * <p>用到的钉钉开放接口（均为官方文档接口）：
 *
 * <ul>
 *   <li>{@code GET /v1.0/robot/messageFiles/download} —— 用消息里的 downloadCode 换下载地址
 *   <li>{@code POST oapi/media/upload} —— 上传图片/文件拿 media_id（oapi access_token）
 *   <li>{@code POST /v1.0/robot/oToMessages/batchSend} —— 单聊发 sampleImageMsg / sampleFile
 *   <li>{@code POST /v1.0/robot/groupMessages/send} —— 群聊发同样的 msgKey
 * </ul>
 *
 * <p>HTTP 一律用 JDK 的 {@link HttpClient}（与 {@code SkillRepoFetcher} 一致，不引入新依赖）。
 */
public class DingTalkMediaClient {

    private static final Logger log = LoggerFactory.getLogger(DingTalkMediaClient.class);

    private static final String DEFAULT_API_BASE = "https://api.dingtalk.com";
    private static final String DEFAULT_OAPI_BASE = "https://oapi.dingtalk.com";

    /** 单个媒体文件上限（钉钉侧限制 + 我们自己的保护）。 */
    static final int MAX_MEDIA_BYTES = 20 * 1024 * 1024;

    /** 图片扩展名（决定发 sampleImageMsg 还是 sampleFile）。 */
    private static final java.util.Set<String> IMAGE_EXT =
            java.util.Set.of("png", "jpg", "jpeg", "gif", "bmp", "webp");

    private final String appKey;
    private final String appSecret;
    private final String robotCode;
    private final String apiBase;
    private final String oapiBase;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    /** appKey → token 缓存（v1.0 与 oapi 两套）。 */
    private final ConcurrentHashMap<String, CachedToken> v1Tokens = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, CachedToken> oapiTokens = new ConcurrentHashMap<>();

    private record CachedToken(String value, long refreshAtMs) {

        boolean expired() {
            return System.currentTimeMillis() >= refreshAtMs;
        }
    }

    public DingTalkMediaClient(
            String appKey, String appSecret, String robotCode, String apiBase, String oapiBase) {
        this.appKey = appKey;
        this.appSecret = appSecret;
        this.robotCode = robotCode;
        this.apiBase = isBlank(apiBase) ? DEFAULT_API_BASE : apiBase;
        this.oapiBase = isBlank(oapiBase) ? DEFAULT_OAPI_BASE : oapiBase;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** 从渠道配置建一个客户端。 */
    public static DingTalkMediaClient fromConfig(Map<String, String> config) {
        return new DingTalkMediaClient(
                config.get("appKey"),
                config.get("appSecret"),
                config.get("robotCode"),
                config.get("apiBase"),
                config.get("oapiBase"));
    }

    // ===================== 入站：下载 =====================

    /**
     * 用 downloadCode 下载机器人收到的媒体内容。
     *
     * @return 文件字节；失败抛 {@link ChannelMediaException}
     */
    public byte[] download(String downloadCode) {
        if (isBlank(downloadCode)) {
            throw new ChannelMediaException("downloadCode 为空");
        }
        String token = v1Token();
        String url = apiBase + "/v1.0/robot/messageFiles/download"
                + "?downloadCode=" + enc(downloadCode)
                + "&robotCode=" + enc(robotCode);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("x-acs-dingtalk-access-token", token)
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new ChannelMediaException("钉钉换取下载地址失败 HTTP " + response.statusCode());
            }
            JsonNode body = json.readTree(response.body());
            String downloadUrl = body.path("downloadUrl").asText(null);
            if (isBlank(downloadUrl)) {
                throw new ChannelMediaException("钉钉未返回 downloadUrl");
            }
            return fetchBytes(downloadUrl);
        } catch (ChannelMediaException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ChannelMediaException("下载钉钉媒体失败: " + ex.getMessage(), ex);
        }
    }

    private byte[] fetchBytes(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2) {
            throw new ChannelMediaException("下载媒体内容失败 HTTP " + response.statusCode());
        }
        byte[] bytes = response.body();
        if (bytes != null && bytes.length > MAX_MEDIA_BYTES) {
            throw new ChannelMediaException("媒体文件超过上限 " + (MAX_MEDIA_BYTES / 1024 / 1024) + "MB");
        }
        return bytes;
    }

    // ===================== 出站：发图片 / 文件 =====================

    /**
     * 发一个媒体消息。
     *
     * @param direct true=单聊（to 是 staffId），false=群聊（to 是 openConversationId）
     * @param fileName 文件名（决定扩展名与图片判定）
     * @param bytes 内容
     */
    public void sendMedia(boolean direct, String to, String fileName, byte[] bytes) {
        if (isBlank(to)) {
            throw new ChannelMediaException("缺少接收方");
        }
        if (bytes == null || bytes.length == 0) {
            throw new ChannelMediaException("媒体内容为空");
        }
        if (bytes.length > MAX_MEDIA_BYTES) {
            throw new ChannelMediaException("媒体文件超过上限 " + (MAX_MEDIA_BYTES / 1024 / 1024) + "MB");
        }
        String safeName = isBlank(fileName) ? "file" : fileName;
        String mediaId = uploadMedia(safeName, bytes);
        boolean image = isImage(safeName);
        Map<String, Object> param = new LinkedHashMap<>();
        if (image) {
            param.put("photoURL", mediaId);
        } else {
            param.put("mediaId", mediaId);
            param.put("fileName", safeName);
            param.put("fileType", extension(safeName));
        }
        sendRobotMessage(direct, to, image ? "sampleImageMsg" : "sampleFile", param);
    }

    /** 上传媒体拿 media_id（oapi，multipart）。 */
    String uploadMedia(String fileName, byte[] bytes) {
        String token = oapiToken();
        String type = isImage(fileName) ? "image" : "file";
        String boundary = "----myagent" + System.nanoTime();
        byte[] body = multipartBody(boundary, fileName, bytes);
        String url = oapiBase + "/media/upload?access_token=" + enc(token) + "&type=" + type;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode node = json.readTree(response.body());
            String mediaId = node.path("media_id").asText(null);
            if (isBlank(mediaId)) {
                throw new ChannelMediaException("钉钉上传媒体失败: " + abbreviate(response.body()));
            }
            return mediaId;
        } catch (ChannelMediaException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ChannelMediaException("上传钉钉媒体失败: " + ex.getMessage(), ex);
        }
    }

    /** 机器人发消息（v1.0）。 */
    void sendRobotMessage(boolean direct, String to, String msgKey, Map<String, Object> msgParam) {
        String token = v1Token();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("robotCode", robotCode);
        String path;
        if (direct) {
            path = "/v1.0/robot/oToMessages/batchSend";
            payload.put("userIds", java.util.List.of(to));
        } else {
            path = "/v1.0/robot/groupMessages/send";
            payload.put("openConversationId", to);
        }
        payload.put("msgKey", msgKey);
        try {
            payload.put("msgParam", json.writeValueAsString(msgParam));
            HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("x-acs-dingtalk-access-token", token)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            json.writeValueAsString(payload), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new ChannelMediaException("钉钉发送媒体消息失败 HTTP " + response.statusCode()
                        + ": " + abbreviate(response.body()));
            }
        } catch (ChannelMediaException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ChannelMediaException("发送钉钉媒体消息失败: " + ex.getMessage(), ex);
        }
    }

    // ===================== token =====================

    String v1Token() {
        CachedToken cached = v1Tokens.get(appKey);
        if (cached != null && !cached.expired()) {
            return cached.value();
        }
        try {
            String body = json.writeValueAsString(Map.of("appKey", appKey, "appSecret", appSecret));
            HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + "/v1.0/oauth2/accessToken"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode node = json.readTree(response.body());
            String token = node.path("accessToken").asText(null);
            if (isBlank(token)) {
                throw new ChannelMediaException("获取钉钉 accessToken 失败: " + abbreviate(response.body()));
            }
            long expiresIn = node.path("expireIn").asLong(7200);
            v1Tokens.put(appKey, new CachedToken(token, System.currentTimeMillis() + (expiresIn - 120) * 1000));
            return token;
        } catch (ChannelMediaException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ChannelMediaException("获取钉钉 accessToken 失败: " + ex.getMessage(), ex);
        }
    }

    String oapiToken() {
        CachedToken cached = oapiTokens.get(appKey);
        if (cached != null && !cached.expired()) {
            return cached.value();
        }
        String url = oapiBase + "/gettoken?appkey=" + enc(appKey) + "&appsecret=" + enc(appSecret);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode node = json.readTree(response.body());
            String token = node.path("access_token").asText(null);
            if (isBlank(token)) {
                throw new ChannelMediaException("获取钉钉 oapi token 失败: " + abbreviate(response.body()));
            }
            long expiresIn = node.path("expires_in").asLong(7200);
            oapiTokens.put(appKey, new CachedToken(token, System.currentTimeMillis() + (expiresIn - 120) * 1000));
            return token;
        } catch (ChannelMediaException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ChannelMediaException("获取钉钉 oapi token 失败: " + ex.getMessage(), ex);
        }
    }

    // ===================== 工具方法 =====================

    static boolean isImage(String fileName) {
        return IMAGE_EXT.contains(extension(fileName));
    }

    static String extension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private static byte[] multipartBody(String boundary, String fileName, byte[] bytes) {
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"media\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        String tail = "\r\n--" + boundary + "--\r\n";
        byte[] headBytes = head.getBytes(StandardCharsets.UTF_8);
        byte[] tailBytes = tail.getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[headBytes.length + bytes.length + tailBytes.length];
        System.arraycopy(headBytes, 0, body, 0, headBytes.length);
        System.arraycopy(bytes, 0, body, headBytes.length, bytes.length);
        System.arraycopy(tailBytes, 0, body, headBytes.length + bytes.length, tailBytes.length);
        return body;
    }

    private static String enc(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 300 ? text : text.substring(0, 300) + "…";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 媒体收发的业务异常（渠道层会把它转成给用户的提示）。 */
    public static class ChannelMediaException extends RuntimeException {

        public ChannelMediaException(String message) {
            super(message);
        }

        public ChannelMediaException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 便于单测：把 base64 直接当字节（钉钉接口返回的是 URL）。 */
    static byte[] decodeBase64(String data) {
        return Base64.getDecoder().decode(data);
    }
}
