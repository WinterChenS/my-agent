package com.myagent.auth.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.common.BizException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.security.spec.MGF1ParameterSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 登录密码的传输加密（前端加密 / 后端解密）。
 *
 * <p><b>先说清楚它的定位</b>：浏览器里的公钥和加密代码对使用者是可见的，所以这层**不能替代 TLS**，
 * 它防的是"链路上被动嗅探拿到明文密码"（例如内网 http、被抓的代理日志、被镜像的流量）。
 * 想要真正安全，仍然必须上 HTTPS —— 这层是纵深防御，不是终点。
 *
 * <p>方案：RSA-2048 + **OAEP(SHA-256)**。
 * <ul>
 *   <li>非对称：只有服务端有私钥，被动嗅探者拿不到明文（对称/XOR 方案做不到这点，因为密钥也在链路上）
 *   <li>OAEP 而不是 PKCS#1 v1.5：避免 padding oracle
 *   <li>明文里带 `t`(毫秒时间戳) + `n`(一次性随机数) + `s`(会话标识)：服务端校验新鲜度、
 *       nonce 不复用、会话一致 —— 所以"重放整段密文"也没用
 *   <li>私钥只在内存里，进程重启即换新（前端会重新取公钥）
 * </ul>
 *
 * <p>注意：前端**不能**用 `crypto.subtle`（内网 http 非安全上下文里它不存在），
 * 所以前端用纯 JS 的 node-forge 做 OAEP，参数必须与服务端这里严格一致
 * （SHA-256 摘要 + MGF1(SHA-256)）。
 */
@Service
public class LoginKeyService {

    private static final Logger log = LoggerFactory.getLogger(LoginKeyService.class);

    private static final String ALGORITHM = "RSA-OAEP-256";
    private static final String JCE_TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";

    private final ObjectMapper objectMapper;
    private final KeyPair keyPair;
    private final String publicKeyPem;
    private final String keyId;
    /** 已用过的 nonce → 过期时间（毫秒），防重放 */
    private final Map<String, Long> usedNonces = new ConcurrentHashMap<>();

    /** 密文里允许的时间偏差（秒）：太旧的密文直接拒 */
    @Value("${myagent.auth.login-key.payload-ttl-seconds:120}")
    private int payloadTtlSeconds;

    /** 公钥有效期（秒），下发给前端做缓存用 */
    @Value("${myagent.auth.login-key.ttl-seconds:300}")
    private int keyTtlSeconds;

    /** nonce 缓存上限，超过就直接清一遍过期的（防止内存被刷爆） */
    @Value("${myagent.auth.login-key.nonce-cache-size:20000}")
    private int nonceCacheSize;

    public LoginKeyService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.keyPair = generate();
        this.publicKeyPem = toPem((RSAPublicKey) keyPair.getPublic());
        this.keyId = fingerprint((RSAPublicKey) keyPair.getPublic());
        log.info("登录密码传输加密已启用：RSA-2048 / OAEP-SHA256，keyId={}", keyId);
    }

    /** 下发给前端的公钥（不含任何私钥信息）。 */
    public PublicKeyInfo publicKey() {
        return new PublicKeyInfo(keyId, ALGORITHM, publicKeyPem, keyTtlSeconds);
    }

    public boolean keyIdMatches(String candidate) {
        return candidate == null || candidate.isBlank() || keyId.equals(candidate);
    }

    /**
     * 解密并校验载荷。
     *
     * @param encryptedBase64 前端 RSA(OAEP-SHA256) 加密后的 base64
     * @param sessionKey      同一次登录请求里带的会话标识（应与密文里的 s 一致）
     */
    public DecryptedPayload decrypt(String encryptedBase64, String sessionKey) {
        if (encryptedBase64 == null || encryptedBase64.isBlank()) {
            throw BizException.badRequest("密码必须加密传输");
        }
        byte[] cipherBytes;
        try {
            cipherBytes = Base64.getDecoder().decode(encryptedBase64.trim());
        } catch (IllegalArgumentException e) {
            throw BizException.badRequest("密码密文格式不正确");
        }

        byte[] plain;
        try {
            Cipher cipher = Cipher.getInstance(JCE_TRANSFORMATION);
            OAEPParameterSpec spec = new OAEPParameterSpec("SHA-256", "MGF1",
                    MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
            cipher.init(Cipher.DECRYPT_MODE, keyPair.getPrivate(), spec);
            plain = cipher.doFinal(cipherBytes);
        } catch (Exception e) {
            // 统一话术：不区分"填充错误/密文损坏/公钥过期"，避免 padding oracle
            log.debug("登录密码解密失败：{}", e.getClass().getSimpleName());
            throw BizException.badRequest("密码解密失败，请刷新页面后重试");
        }

        JsonNode node;
        try {
            node = objectMapper.readTree(new String(plain, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw BizException.badRequest("密码载荷格式不正确");
        }

        String password = node.path("p").asText(null);
        if (password == null || password.isEmpty()) {
            throw BizException.badRequest("密码不能为空");
        }
        long timestamp = node.path("t").asLong(0);
        String nonce = node.path("n").asText("");
        String boundSession = node.path("s").asText("");

        if (timestamp <= 0 || Math.abs(System.currentTimeMillis() - timestamp) > payloadTtlSeconds * 1000L) {
            throw BizException.badRequest("请求已过期，请重新登录");
        }
        if (nonce.isBlank()) {
            throw BizException.badRequest("请求缺少随机数，请重新登录");
        }
        if (sessionKey != null && !sessionKey.isBlank() && !boundSession.isBlank()
                && !boundSession.equals(sessionKey)) {
            throw BizException.badRequest("会话不一致，请重新登录");
        }
        if (usedNonces.putIfAbsent(nonce, System.currentTimeMillis() + payloadTtlSeconds * 1000L) != null) {
            throw BizException.badRequest("重复的登录请求，请重试");
        }
        return new DecryptedPayload(password, nonce, timestamp, boundSession);
    }

    /** 清理过期的 nonce 记录。 */
    @Scheduled(fixedDelayString = "${myagent.auth.login-key.nonce-cleanup-millis:60000}")
    public void cleanupNonces() {
        long now = System.currentTimeMillis();
        usedNonces.entrySet().removeIf(entry -> entry.getValue() < now);
        if (usedNonces.size() > nonceCacheSize) {
            log.warn("登录 nonce 缓存过大（{}），执行一次清理", usedNonces.size());
            usedNonces.clear();
        }
    }

    public record PublicKeyInfo(String keyId, String algorithm, String publicKey, int ttlSeconds) {}

    public record DecryptedPayload(String password, String nonce, long timestamp, String sessionKey) {}

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("生成登录加密密钥失败", e);
        }
    }

    private static String toPem(RSAPublicKey key) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(key.getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----\n";
    }

    private static String fingerprint(RSAPublicKey key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (Exception e) {
            return "unknown";
        }
    }
}
