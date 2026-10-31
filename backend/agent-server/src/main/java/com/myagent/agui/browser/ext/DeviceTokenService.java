package com.myagent.agui.browser.ext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 设备令牌：扩展用它连 WSS，**与网页 JWT 完全隔离**（scope 只有浏览器控制）。
 *
 * <p>格式：{@code base64url(payload).base64url(hmacSha256(payload))}，payload 是紧凑 JSON。
 * 没引 jjwt 是因为载荷只有 5 个字段，而且必须能独立校验 credential_version（撤销后旧令牌立刻失效）。
 */
@Component
public class DeviceTokenService {

    private static final Logger log = LoggerFactory.getLogger(DeviceTokenService.class);
    private static final String HMAC = "HmacSHA256";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final BrowserExtensionProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();
    private final byte[] secret;

    public DeviceTokenService(BrowserExtensionProperties properties) {
        this.properties = properties;
        String configured = properties.tokenSecret();
        if (configured.isBlank()) {
            byte[] generated = new byte[32];
            RANDOM.nextBytes(generated);
            this.secret = generated;
            log.warn("未配置 myagent.browser.extension.token-secret，已生成临时密钥："
                    + "后端重启后已配对的扩展需要重新连接（凭证仍可通过 refresh 续期）");
        } else {
            this.secret = configured.getBytes(StandardCharsets.UTF_8);
        }
    }

    public record Claims(long userId, String deviceId, String installId, int credentialVersion, long expiresAt) {
        public boolean expired() {
            return Instant.now().getEpochSecond() >= expiresAt;
        }
    }

    public String issue(long userId, String deviceId, String installId, int credentialVersion, int ttlSeconds) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("u", userId);
        payload.put("d", deviceId);
        payload.put("i", installId);
        payload.put("cv", credentialVersion);
        payload.put("exp", Instant.now().getEpochSecond() + Math.max(30, ttlSeconds));
        try {
            byte[] json = mapper.writeValueAsBytes(payload);
            String body = Base64.getUrlEncoder().withoutPadding().encodeToString(json);
            return body + "." + sign(body);
        } catch (Exception e) {
            throw new IllegalStateException("签发设备令牌失败", e);
        }
    }

    public Claims verify(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        int dot = token.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        String body = token.substring(0, dot);
        String mac = token.substring(dot + 1);
        if (!constantTimeEquals(mac, sign(body))) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(Base64.getUrlDecoder().decode(body));
            return new Claims(
                    node.path("u").asLong(),
                    node.path("d").asText(),
                    node.path("i").asText(),
                    node.path("cv").asInt(),
                    node.path("exp").asLong());
        } catch (Exception e) {
            return null;
        }
    }

    public String newRefreshToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String newDeviceCode() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 用户码：去掉易混淆字符（0/O/1/I），8 位，5 分钟一次性。 */
    public String newUserCode() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** PKCE S256：base64url(sha256(verifier))，与扩展侧实现必须一致。 */
    public static String pkceS256(String verifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String newId(String prefix) {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        return prefix + HexFormat.of().formatHex(bytes);
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret, HMAC));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("令牌签名失败", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    public BrowserExtensionProperties properties() {
        return properties;
    }
}
