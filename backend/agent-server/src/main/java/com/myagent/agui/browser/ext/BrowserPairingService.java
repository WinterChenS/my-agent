package com.myagent.agui.browser.ext;

import com.myagent.common.BizException;
import java.time.OffsetDateTime;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 设备配对（PCI 式 device code + PKCE）。
 *
 * <p>流程：扩展创建配对 → 用户在已登录网页批准 → 扩展用 {@code deviceCode + verifier} 领取凭证。
 * 三处都做了原子性：批准只能成功一次（PENDING→APPROVED）、领取只能成功一次（APPROVED→CONSUMED）、
 * refresh 只能轮换一次（复用即冻结整族凭证）。
 */
@Service
public class BrowserPairingService {

    private static final Logger log = LoggerFactory.getLogger(BrowserPairingService.class);

    private final BrowserExtensionStore store;
    private final DeviceTokenService tokens;
    private final BrowserExtensionProperties properties;

    public BrowserPairingService(
            BrowserExtensionStore store, DeviceTokenService tokens, BrowserExtensionProperties properties) {
        this.store = store;
        this.tokens = tokens;
        this.properties = properties;
    }

    public record PairingCreated(
            String id, String deviceCode, String userCode, String verificationUri, int expiresIn, int interval) {}

    public record DeviceTokens(
            String accessToken, String refreshToken, int expiresIn, String deviceId, long userId, int credentialVersion) {}

    @Transactional
    public PairingCreated create(String installId, String pkceChallenge, String displayName, String browser) {
        if (installId == null || installId.length() < 8 || installId.length() > 64) {
            throw new BizException(40001, "installId 不合法", HttpStatus.BAD_REQUEST);
        }
        if (pkceChallenge == null || !pkceChallenge.matches("[A-Za-z0-9_-]{43,44}")) {
            throw new BizException(40002, "pkceChallenge 不合法", HttpStatus.BAD_REQUEST);
        }
        String deviceCode = tokens.newDeviceCode();
        String userCode = tokens.newUserCode();
        String id = DeviceTokenService.newId("pr-");
        store.insertPairing(new BrowserExtensionStore.PairingRow(
                id,
                DeviceTokenService.sha256Hex(deviceCode),
                DeviceTokenService.sha256Hex(normalizeUserCode(userCode)),
                pkceChallenge,
                installId,
                trimTo(displayName, 120),
                trimTo(browser, 32),
                "PENDING",
                null,
                null,
                null,
                0,
                OffsetDateTime.now().plusSeconds(properties.pairingTtlSeconds())));
        log.info("创建浏览器配对 installId={} 设备名={} 有效期={}s", installId, displayName,
                properties.pairingTtlSeconds());
        return new PairingCreated(
                id, deviceCode, userCode, "/browser/pair", properties.pairingTtlSeconds(), 3);
    }

    /** 配对页展示用：只有持码用户自己能看到申请详情（码是一次性短码，不泄露敏感信息）。 */
    public record PairingPreview(String userCode, String displayName, String browser, String status, long expiresInSeconds) {}

    public PairingPreview preview(String userCode) {
        BrowserExtensionStore.PairingRow row = byUserCode(userCode);
        long left = row.expiresAt().toEpochSecond() - OffsetDateTime.now().toEpochSecond();
        return new PairingPreview(
                normalizeUserCode(userCode), row.displayName(), row.browser(), row.status(), Math.max(0, left));
    }

    @Transactional
    public void approve(long userId, String userCode) {
        BrowserExtensionStore.PairingRow row = byUserCode(userCode);
        if (row.attemptCount() > 20) {
            throw new BizException(42901, "配对码尝试次数过多，请重新开始配对", HttpStatus.TOO_MANY_REQUESTS);
        }
        if (store.approvePairing(row.id(), userId) != 1) {
            store.bumpPairingAttempt(row.id());
            throw new BizException(40004, "配对码已失效、已被批准或已过期", HttpStatus.BAD_REQUEST);
        }
        log.info("配对已批准 pairingId={} userId={}", row.id(), userId);
    }

    @Transactional
    public DeviceTokens claim(String deviceCode, String verifier, String extensionVersion, int protocolVersion) {
        if (deviceCode == null || verifier == null) {
            throw new BizException(40005, "缺少 deviceCode 或 verifier", HttpStatus.BAD_REQUEST);
        }
        BrowserExtensionStore.PairingRow row = store
                .pairingByDeviceCodeHash(DeviceTokenService.sha256Hex(deviceCode))
                .orElseThrow(() -> new BizException(40006, "配对不存在或已过期", HttpStatus.BAD_REQUEST));
        if (!row.pkceChallenge().equals(DeviceTokenService.pkceS256(verifier))) {
            store.bumpPairingAttempt(row.id());
            throw new BizException(40007, "PKCE 校验失败", HttpStatus.BAD_REQUEST);
        }
        if (row.approvedBy() == null) {
            throw new BizException(40008, "尚未在网页批准", HttpStatus.BAD_REQUEST);
        }
        if (store.consumePairing(row.id()) != 1) {
            throw new BizException(40009, "配对已被领取或已过期", HttpStatus.BAD_REQUEST);
        }
        long userId = row.approvedBy();
        String deviceId;
        int credentialVersion;
        var existing = store.deviceByInstallId(row.installId());
        if (existing.isPresent()) {
            // 重新配对（比如重装扩展）：复用设备 id，抬升凭证版本 → 旧令牌立刻失效
            deviceId = existing.get().id();
            store.bumpCredentialVersion(deviceId);
            store.touchDevice(deviceId, extensionVersion, protocolVersion);
            credentialVersion = existing.get().credentialVersion() + 1;
        } else {
            deviceId = DeviceTokenService.newId("dv-");
            credentialVersion = 1;
            store.insertDevice(new BrowserExtensionStore.DeviceRow(
                    deviceId, userId, row.installId(), row.displayName(), row.browser(),
                    extensionVersion, protocolVersion, credentialVersion, "ACTIVE", null, null));
        }
        return issueTokens(userId, deviceId, row.installId(), credentialVersion);
    }

    /** refresh 轮换：单次消费，复用旧 token 视为泄露并冻结该设备凭证。 */
    @Transactional
    public DeviceTokens refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BizException(40010, "缺少 refreshToken", HttpStatus.BAD_REQUEST);
        }
        var row = store.credentialByRefreshHash(DeviceTokenService.sha256Hex(refreshToken))
                .orElseThrow(() -> new BizException(40011, "refreshToken 无效", HttpStatus.UNAUTHORIZED));
        if (row.revokedAt() != null || row.expiresAt().isBefore(OffsetDateTime.now())) {
            throw new BizException(40012, "凭证已失效，请重新配对", HttpStatus.UNAUTHORIZED);
        }
        if (store.consumeCredential(row.id()) != 1) {
            // 复用被消费过的 refresh → 冻结整族 + 抬版本，迫使重新配对
            store.revokeCredentialFamily(row.deviceId(), row.tokenFamily());
            store.bumpCredentialVersion(row.deviceId());
            log.warn("检测到 refreshToken 重用，已冻结设备凭证 deviceId={}", row.deviceId());
            throw new BizException(40013, "凭证异常，请重新配对", HttpStatus.UNAUTHORIZED);
        }
        var device = store.deviceById(row.deviceId())
                .orElseThrow(() -> new BizException(40014, "设备不存在", HttpStatus.UNAUTHORIZED));
        if (!"ACTIVE".equals(device.status())) {
            throw new BizException(40015, "设备已被撤销", HttpStatus.UNAUTHORIZED);
        }
        return issueTokens(device.userId(), device.id(), device.installId(), device.credentialVersion());
    }

    private DeviceTokens issueTokens(long userId, String deviceId, String installId, int credentialVersion) {
        String access = tokens.issue(userId, deviceId, installId, credentialVersion,
                properties.deviceTokenTtlSeconds());
        String refresh = tokens.newRefreshToken();
        store.insertCredential(new BrowserExtensionStore.CredentialRow(
                DeviceTokenService.newId("cr-"),
                deviceId,
                DeviceTokenService.sha256Hex(refresh),
                DeviceTokenService.newId("tf-"),
                credentialVersion,
                OffsetDateTime.now().plusSeconds(properties.refreshTokenTtlSeconds()),
                null,
                null));
        return new DeviceTokens(access, refresh, properties.deviceTokenTtlSeconds(), deviceId, userId,
                credentialVersion);
    }

    private BrowserExtensionStore.PairingRow byUserCode(String userCode) {
        return store.pairingByUserCodeHash(DeviceTokenService.sha256Hex(normalizeUserCode(userCode)))
                .orElseThrow(() -> new BizException(40003, "配对码不正确", HttpStatus.BAD_REQUEST));
    }

    private static String normalizeUserCode(String code) {
        return code == null ? "" : code.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    private static String trimTo(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
