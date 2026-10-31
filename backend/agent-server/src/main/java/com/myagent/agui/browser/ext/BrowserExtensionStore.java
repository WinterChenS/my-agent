package com.myagent.agui.browser.ext;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * 浏览器扩展相关的全部持久化（配对、设备、凭证、控制会话、审计）。
 *
 * <p>为什么用 JdbcTemplate 而不是 MyBatis Mapper：这里几乎每条语句都是**带条件的原子更新**
 * （配对只能被消费一次、refresh 只能被轮换一次、暂停要递增 lease 并取消旧命令），
 * 直接写 SQL 比在实体上拼条件更清楚，也更容易在测试里核对行数。
 */
@Component
public class BrowserExtensionStore {

    private final JdbcTemplate jdbc;

    public BrowserExtensionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------- 配对 ----------------

    public record PairingRow(
            String id,
            String deviceCodeHash,
            String userCodeHash,
            String pkceChallenge,
            String installId,
            String displayName,
            String browser,
            String status,
            Long approvedBy,
            OffsetDateTime approvedAt,
            OffsetDateTime consumedAt,
            int attemptCount,
            OffsetDateTime expiresAt) {}

    private static final RowMapper<PairingRow> PAIRING = (rs, i) -> new PairingRow(
            rs.getString("id"),
            rs.getString("device_code_hash"),
            rs.getString("user_code_hash"),
            rs.getString("pkce_challenge"),
            rs.getString("install_id"),
            rs.getString("display_name"),
            rs.getString("browser"),
            rs.getString("status"),
            (Long) rs.getObject("approved_by"),
            rs.getObject("approved_at", OffsetDateTime.class),
            rs.getObject("consumed_at", OffsetDateTime.class),
            rs.getInt("attempt_count"),
            rs.getObject("expires_at", OffsetDateTime.class));

    public void insertPairing(PairingRow row) {
        jdbc.update(
                """
                INSERT INTO browser_pairing (id, device_code_hash, user_code_hash, pkce_challenge,
                       install_id, display_name, browser, status, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                row.id(), row.deviceCodeHash(), row.userCodeHash(), row.pkceChallenge(),
                row.installId(), row.displayName(), row.browser(), row.status(), row.expiresAt());
    }

    public Optional<PairingRow> pairingByDeviceCodeHash(String hash) {
        return jdbc.query("SELECT * FROM browser_pairing WHERE device_code_hash = ?", PAIRING, hash)
                .stream().findFirst();
    }

    public Optional<PairingRow> pairingByUserCodeHash(String hash) {
        return jdbc.query("SELECT * FROM browser_pairing WHERE user_code_hash = ?", PAIRING, hash)
                .stream().findFirst();
    }

    /** 网页批准：只有 PENDING + 未过期能批准，返回受影响行数（并发下只有一个成功）。 */
    public int approvePairing(String id, long userId) {
        return jdbc.update(
                """
                UPDATE browser_pairing
                   SET status = 'APPROVED', approved_by = ?, approved_at = NOW()
                 WHERE id = ? AND status = 'PENDING' AND expires_at > NOW()
                """,
                userId, id);
    }

    /** 领取凭证：原子消费，防止同一个 deviceCode 被兑换两次。 */
    public int consumePairing(String id) {
        return jdbc.update(
                """
                UPDATE browser_pairing
                   SET status = 'CONSUMED', consumed_at = NOW()
                 WHERE id = ? AND status = 'APPROVED' AND consumed_at IS NULL AND expires_at > NOW()
                """,
                id);
    }

    public void bumpPairingAttempt(String id) {
        jdbc.update("UPDATE browser_pairing SET attempt_count = attempt_count + 1 WHERE id = ?", id);
    }

    // ---------------- 设备 ----------------

    public record DeviceRow(
            String id,
            long userId,
            String installId,
            String displayName,
            String browser,
            String extensionVersion,
            int protocolVersion,
            int credentialVersion,
            String status,
            OffsetDateTime lastSeenAt,
            OffsetDateTime revokedAt) {}

    private static final RowMapper<DeviceRow> DEVICE = (rs, i) -> new DeviceRow(
            rs.getString("id"),
            rs.getLong("user_id"),
            rs.getString("install_id"),
            rs.getString("display_name"),
            rs.getString("browser"),
            rs.getString("extension_version"),
            rs.getInt("protocol_version"),
            rs.getInt("credential_version"),
            rs.getString("status"),
            rs.getObject("last_seen_at", OffsetDateTime.class),
            rs.getObject("revoked_at", OffsetDateTime.class));

    public void insertDevice(DeviceRow d) {
        jdbc.update(
                """
                INSERT INTO browser_device (id, user_id, install_id, display_name, browser,
                       extension_version, protocol_version, credential_version, status, last_seen_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                ON CONFLICT (install_id) DO UPDATE
                   SET user_id = EXCLUDED.user_id,
                       display_name = EXCLUDED.display_name,
                       browser = EXCLUDED.browser,
                       extension_version = EXCLUDED.extension_version,
                       protocol_version = EXCLUDED.protocol_version,
                       credential_version = browser_device.credential_version + 1,
                       status = 'ACTIVE',
                       revoked_at = NULL,
                       last_seen_at = NOW()
                """,
                d.id(), d.userId(), d.installId(), d.displayName(), d.browser(),
                d.extensionVersion(), d.protocolVersion(), d.credentialVersion(), d.status());
    }

    public Optional<DeviceRow> deviceById(String id) {
        return jdbc.query("SELECT * FROM browser_device WHERE id = ?", DEVICE, id).stream().findFirst();
    }

    public Optional<DeviceRow> deviceByInstallId(String installId) {
        return jdbc.query("SELECT * FROM browser_device WHERE install_id = ?", DEVICE, installId)
                .stream().findFirst();
    }

    public List<DeviceRow> devicesOf(long userId) {
        return jdbc.query(
                "SELECT * FROM browser_device WHERE user_id = ? ORDER BY last_seen_at DESC NULLS LAST",
                DEVICE, userId);
    }

    public void touchDevice(String id, String extensionVersion, int protocolVersion) {
        jdbc.update(
                """
                UPDATE browser_device
                   SET last_seen_at = NOW(),
                       extension_version = COALESCE(?, extension_version),
                       protocol_version = ?
                 WHERE id = ?
                """,
                extensionVersion, protocolVersion, id);
    }

    public int revokeDevice(String id, long userId) {
        return jdbc.update(
                """
                UPDATE browser_device SET status = 'REVOKED', revoked_at = NOW()
                 WHERE id = ? AND user_id = ? AND status <> 'REVOKED'
                """,
                id, userId);
    }

    public int bumpCredentialVersion(String deviceId) {
        return jdbc.update(
                "UPDATE browser_device SET credential_version = credential_version + 1 WHERE id = ?", deviceId);
    }

    // ---------------- 设备凭证 ----------------

    public record CredentialRow(
            String id,
            String deviceId,
            String refreshHash,
            String tokenFamily,
            int rotationVersion,
            OffsetDateTime expiresAt,
            OffsetDateTime consumedAt,
            OffsetDateTime revokedAt) {}

    private static final RowMapper<CredentialRow> CREDENTIAL = (rs, i) -> new CredentialRow(
            rs.getString("id"),
            rs.getString("device_id"),
            rs.getString("refresh_hash"),
            rs.getString("token_family"),
            rs.getInt("rotation_version"),
            rs.getObject("expires_at", OffsetDateTime.class),
            rs.getObject("consumed_at", OffsetDateTime.class),
            rs.getObject("revoked_at", OffsetDateTime.class));

    public void insertCredential(CredentialRow c) {
        jdbc.update(
                """
                INSERT INTO browser_device_credential (id, device_id, refresh_hash, token_family,
                       rotation_version, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                c.id(), c.deviceId(), c.refreshHash(), c.tokenFamily(), c.rotationVersion(), c.expiresAt());
    }

    public Optional<CredentialRow> credentialByRefreshHash(String hash) {
        return jdbc.query("SELECT * FROM browser_device_credential WHERE refresh_hash = ?", CREDENTIAL, hash)
                .stream().findFirst();
    }

    /**
     * 轮换：把旧 refresh 标记为已消费，只在「未消费 + 未撤销 + 未过期」时成功。
     * 返回 0 表示这个 refresh 已经被用过（或已失效）—— 按方案要求应视为凭证重用，冻结整族。
     */
    public int consumeCredential(String id) {
        return jdbc.update(
                """
                UPDATE browser_device_credential
                   SET consumed_at = NOW()
                 WHERE id = ? AND consumed_at IS NULL AND revoked_at IS NULL AND expires_at > NOW()
                """,
                id);
    }

    public int revokeCredentialFamily(String deviceId, String family) {
        return jdbc.update(
                """
                UPDATE browser_device_credential SET revoked_at = NOW()
                 WHERE device_id = ? AND token_family = ? AND revoked_at IS NULL
                """,
                deviceId, family);
    }

    // ---------------- 控制会话 ----------------

    public record SessionRow(
            String id,
            long userId,
            String conversationId,
            String deviceId,
            String tabHandle,
            String origin,
            String title,
            int pageRevision,
            String state,
            int leaseVersion,
            String lastError,
            OffsetDateTime expiresAt,
            OffsetDateTime endedAt) {

        public boolean active() {
            return endedAt == null && expiresAt.isAfter(OffsetDateTime.now());
        }
    }

    private static final RowMapper<SessionRow> SESSION = (rs, i) -> new SessionRow(
            rs.getString("id"),
            rs.getLong("user_id"),
            rs.getString("conversation_id"),
            rs.getString("device_id"),
            rs.getString("tab_handle"),
            rs.getString("origin"),
            rs.getString("title"),
            rs.getInt("page_revision"),
            rs.getString("state"),
            rs.getInt("lease_version"),
            rs.getString("last_error"),
            rs.getObject("expires_at", OffsetDateTime.class),
            rs.getObject("ended_at", OffsetDateTime.class));

    public void insertSession(SessionRow s) {
        jdbc.update(
                """
                INSERT INTO browser_control_session (id, user_id, conversation_id, device_id, tab_handle,
                       origin, title, page_revision, state, lease_version, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                s.id(), s.userId(), s.conversationId(), s.deviceId(), s.tabHandle(), s.origin(),
                s.title(), s.pageRevision(), s.state(), s.leaseVersion(), s.expiresAt());
    }

    public Optional<SessionRow> sessionById(String id) {
        return jdbc.query("SELECT * FROM browser_control_session WHERE id = ?", SESSION, id)
                .stream().findFirst();
    }

    /** 同一会话（conversation）当前未结束的控制会话；有多个时取最新。 */
    public Optional<SessionRow> activeSession(long userId, String conversationId) {
        return jdbc.query(
                """
                SELECT * FROM browser_control_session
                 WHERE user_id = ? AND conversation_id = ? AND ended_at IS NULL
                 ORDER BY created_at DESC LIMIT 1
                """,
                SESSION, userId, conversationId).stream().findFirst();
    }

    public List<SessionRow> sessionsOf(long userId, int limit) {
        return jdbc.query(
                """
                SELECT * FROM browser_control_session
                 WHERE user_id = ? ORDER BY created_at DESC LIMIT ?
                """,
                SESSION, userId, limit);
    }

    /** 状态迁移：递增 lease_version，让之前下发的命令全部失效。 */
    public int transition(String id, String state, String lastError) {
        return jdbc.update(
                """
                UPDATE browser_control_session
                   SET state = ?,
                       last_error = ?,
                       lease_version = lease_version + 1,
                       last_active_at = NOW()
                 WHERE id = ? AND ended_at IS NULL
                """,
                state, lastError, id);
    }

    /** 扩展上报标签页/页面版本（不递增 lease：这不是控制权变化）。 */
    public int updateTab(String id, String tabHandle, String origin, String title, int pageRevision) {
        return jdbc.update(
                """
                UPDATE browser_control_session
                   SET tab_handle = ?, origin = ?, title = ?, page_revision = ?, last_active_at = NOW()
                 WHERE id = ? AND ended_at IS NULL
                """,
                tabHandle, origin, title, pageRevision, id);
    }

    public void touchSession(String id) {
        jdbc.update("UPDATE browser_control_session SET last_active_at = NOW() WHERE id = ?", id);
    }

    public int endSession(String id) {
        return jdbc.update(
                """
                UPDATE browser_control_session
                   SET ended_at = NOW(), state = 'REVOKED', lease_version = lease_version + 1
                 WHERE id = ? AND ended_at IS NULL
                """,
                id);
    }

    /** 过期清理：到期的会话直接结束（断线/关页面后兜底）。 */
    public int expireStale() {
        return jdbc.update(
                """
                UPDATE browser_control_session
                   SET ended_at = NOW(), state = 'EXPIRED', lease_version = lease_version + 1
                 WHERE ended_at IS NULL AND expires_at < NOW()
                """);
    }

    // ---------------- 审计 ----------------

    public void audit(
            String requestId, String sessionId, Long userId, String deviceId, String action,
            String origin, String risk, String status, String errorCode, Integer durationMs) {
        jdbc.update(
                """
                INSERT INTO browser_action_audit (request_id, session_id, user_id, device_id, action,
                       origin, risk, status, error_code, duration_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                requestId, sessionId, userId, deviceId, action, origin, risk, status, errorCode, durationMs);
    }
}
