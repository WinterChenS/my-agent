package com.myagent.connectorconfig;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 连接器的持久化（JDBC）。
 *
 * <p>为什么不用 MyBatis-Plus 实体：这里的查询都是「带条件的少量行」——
 * 按属主、按 Agent、按类型、租约抢占（{@code SKIP LOCKED} 之外的行级条件更新）、
 * 计数与审计，用 SQL 直写比造三个实体 + 三个 mapper 更短也更清楚
 * （记忆模块的台账查询也是这个风格）。
 */
@Repository
public class ConnectorStore {

    private static final String COLUMNS = """
            id, type, name, alias, owner_user_id, config_enc, enabled, status, last_error,
            last_checked_at, inbound_mode, uid_validity, last_uid, created_at, updated_at
            """;

    /** 联表查询时带表别名的列（避免手拼字符串出错）。 */
    private static final String COLUMNS_C = "c.id, c.type, c.name, c.alias, c.owner_user_id, c.config_enc,"
            + " c.enabled, c.status, c.last_error, c.last_checked_at, c.inbound_mode, c.uid_validity,"
            + " c.last_uid, c.created_at, c.updated_at";

    private static final RowMapper<ConnectorRecord> MAPPER = ConnectorStore::map;

    private final JdbcTemplate jdbc;

    public ConnectorStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static ConnectorRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new ConnectorRecord(
                rs.getLong("id"),
                rs.getString("type"),
                rs.getString("name"),
                rs.getString("alias"),
                rs.getLong("owner_user_id"),
                rs.getString("config_enc"),
                rs.getBoolean("enabled"),
                rs.getString("status"),
                rs.getString("last_error"),
                rs.getObject("last_checked_at", OffsetDateTime.class),
                rs.getString("inbound_mode"),
                rs.getObject("uid_validity") == null ? null : rs.getLong("uid_validity"),
                rs.getLong("last_uid"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    // ===================== 查询 =====================

    public Optional<ConnectorRecord> find(long id) {
        List<ConnectorRecord> rows = jdbc.query(
                "select " + COLUMNS + " from connector where id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    public List<ConnectorRecord> listByOwner(long ownerUserId) {
        return jdbc.query(
                "select " + COLUMNS + " from connector where owner_user_id = ? order by id desc",
                MAPPER, ownerUserId);
    }

    public List<ConnectorRecord> listAll() {
        return jdbc.query("select " + COLUMNS + " from connector order by id desc", MAPPER);
    }

    /** 某 Agent 绑定的连接器（AgentFactory 构建工具时用）。 */
    public List<ConnectorRecord> listByAgent(long agentId) {
        return jdbc.query(
                "select " + COLUMNS_C
                        + " from connector c join agent_connector ac on ac.connector_id = c.id"
                        + " where ac.agent_id = ? and c.enabled = true order by c.id",
                MAPPER, agentId);
    }

    /** 某个用户在某 Agent 上可用的连接器（调用工具时解析"我自己的那个邮箱"）。 */
    public List<ConnectorRecord> listByAgentAndOwner(long agentId, long ownerUserId) {
        return listByAgent(agentId).stream()
                .filter(c -> c.ownerUserId() == ownerUserId)
                .toList();
    }

    /** 某 Agent 绑定的连接器类型（去重，用于决定注册哪些工具）。 */
    public List<String> typesByAgent(long agentId) {
        return jdbc.queryForList(
                "select distinct c.type from connector c join agent_connector ac on ac.connector_id = c.id"
                        + " where ac.agent_id = ? and c.enabled = true order by c.type",
                String.class, agentId);
    }

    /** 入站轮询：所有启用且配了入站模式的连接器。 */
    public List<ConnectorRecord> listInboundEnabled() {
        return jdbc.query(
                "select " + COLUMNS + " from connector"
                        + " where enabled = true and inbound_mode <> 'OFF' order by id",
                MAPPER);
    }

    // ===================== 写 =====================

    public long insert(
            String type,
            String name,
            String alias,
            long ownerUserId,
            String configEnc,
            boolean enabled,
            String inboundMode) {
        return jdbc.queryForObject(
                "insert into connector (type, name, alias, owner_user_id, config_enc, enabled, inbound_mode)"
                        + " values (?, ?, ?, ?, ?, ?, ?) returning id",
                Long.class,
                type, name, alias, ownerUserId, configEnc, enabled, inboundMode);
    }

    /**
     * 更新配置。
     *
     * @param configChanged 凭据/配置**是否真的变了**：变了才把探测状态重置为 UNKNOWN。
     *        不要用「密文是否相等于列里的值」来判断 —— AES-GCM 每次加密 IV 随机，
     *        即使明文没变密文也一定不同，那样每次改个名字都会把状态打回"未测试"。
     */
    public void update(
            long id,
            String name,
            String alias,
            String configEnc,
            boolean enabled,
            String inboundMode,
            boolean configChanged) {
        jdbc.update(
                "update connector set name = ?, alias = ?, config_enc = ?, enabled = ?, inbound_mode = ?,"
                        + " status = case when ? then 'UNKNOWN' else status end,"
                        + " updated_at = now() where id = ?",
                name, alias, configEnc, enabled, inboundMode, configChanged, id);
    }

    public void delete(long id) {
        jdbc.update("delete from connector where id = ?", id);
    }

    /** 探测结果（成功/失败都会记时间，界面能显示"上次检查"）。 */
    public void markProbe(long id, boolean ok, String error) {
        jdbc.update(
                "update connector set status = ?, last_error = ?, last_checked_at = now(), updated_at = now()"
                        + " where id = ?",
                ok ? "OK" : "ERROR", error, id);
    }

    /** 保存入站游标（UIDVALIDITY 变化时由调用方决定是否重置）。 */
    public void saveInboundCursor(long id, Long uidValidity, long lastUid) {
        jdbc.update(
                "update connector set uid_validity = ?, last_uid = ?, updated_at = now() where id = ?",
                uidValidity, lastUid, id);
    }

    /**
     * 抢占入站轮询租约（多节点部署时同一连接器只会被一个节点处理）。
     *
     * @return true=抢到
     */
    public boolean claimLease(long id, String owner, int leaseMinutes) {
        int updated = jdbc.update(
                "update connector set lease_owner = ?, lease_until = now() + (? || ' minutes')::interval"
                        + " where id = ? and (lease_until is null or lease_until < now())",
                owner, leaseMinutes, id);
        return updated > 0;
    }

    public void releaseLease(long id, String owner) {
        jdbc.update("update connector set lease_owner = null, lease_until = null"
                + " where id = ? and lease_owner = ?", id, owner);
    }

    // ===================== 绑定 =====================

    public List<Long> agentIdsOf(long connectorId) {
        return jdbc.queryForList(
                "select agent_id from agent_connector where connector_id = ? order by agent_id",
                Long.class, connectorId);
    }

    public List<Long> connectorIdsOfAgent(long agentId) {
        return jdbc.queryForList(
                "select connector_id from agent_connector where agent_id = ? order by connector_id",
                Long.class, agentId);
    }

    /** 各连接器被绑定的 Agent 数（诊断用；列表页直接用 agentIds.size()）。 */
    public Map<Long, Integer> boundAgentCounts() {
        Map<Long, Integer> counts = new java.util.LinkedHashMap<>();
        jdbc.query("select connector_id, count(*) as n from agent_connector group by connector_id", rs -> {
            counts.put(rs.getLong("connector_id"), rs.getInt("n"));
        });
        return counts;
    }

    public void replaceAgents(long connectorId, List<Long> agentIds) {
        jdbc.update("delete from agent_connector where connector_id = ?", connectorId);
        for (Long agentId : agentIds) {
            jdbc.update(
                    "insert into agent_connector (agent_id, connector_id) values (?, ?)"
                            + " on conflict do nothing",
                    agentId, connectorId);
        }
    }

    // ===================== 写操作审计 / 限流 =====================

    public void logAction(
            long connectorId,
            Long userId,
            String action,
            String target,
            String subject,
            boolean success,
            String error) {
        jdbc.update(
                "insert into connector_action_log (connector_id, user_id, action, target, subject, success, error)"
                        + " values (?, ?, ?, ?, ?, ?, ?)",
                connectorId, userId, action, target, subject, success, error);
    }

    public int countActionsSince(long connectorId, String action, Instant since) {
        Integer count = jdbc.queryForObject(
                "select count(*) from connector_action_log"
                        + " where connector_id = ? and action = ? and success = true and created_at >= ?",
                Integer.class, connectorId, action, OffsetDateTime.ofInstant(since, java.time.ZoneOffset.UTC));
        return count == null ? 0 : count;
    }

    public List<Map<String, Object>> recentActions(long connectorId, int limit) {
        return jdbc.queryForList(
                "select id, action, target, subject, success, error, created_at"
                        + " from connector_action_log where connector_id = ? order by id desc limit ?",
                connectorId, Math.min(Math.max(limit, 1), 200));
    }

    // ===================== 入站事件 =====================

    /**
     * 记录一封新邮件。
     *
     * @return true=第一次见到（需要处理）；false=已处理过（Message-ID 去重）
     */
    public boolean recordInbound(
            long connectorId,
            String externalId,
            String threadKey,
            String fromAddr,
            String subject,
            OffsetDateTime receivedAt) {
        int inserted = jdbc.update(
                "insert into connector_inbound_event"
                        + " (connector_id, external_id, thread_key, from_addr, subject, received_at)"
                        + " values (?, ?, ?, ?, ?, ?) on conflict (connector_id, external_id) do nothing",
                connectorId, externalId, threadKey, fromAddr, subject, receivedAt);
        return inserted > 0;
    }

    public void finishInbound(long id, String status, String skipReason, String runId, String error) {
        jdbc.update(
                "update connector_inbound_event set status = ?, skip_reason = ?, run_id = ?, error = ?,"
                        + " handled_at = now() where id = ?",
                status, skipReason, runId, error, id);
    }

    public Optional<Map<String, Object>> findInbound(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from connector_inbound_event where id = ?", id);
        return rows.stream().findFirst();
    }

    public Optional<Long> inboundId(long connectorId, String externalId) {
        List<Long> rows = jdbc.queryForList(
                "select id from connector_inbound_event where connector_id = ? and external_id = ?",
                Long.class, connectorId, externalId);
        return rows.stream().findFirst();
    }

    /** 同一邮件线程里已经自动回复过几次（防"你回我、我回你"的邮件炸弹）。 */
    public int countAutoRepliesForThread(long connectorId, String threadKey) {
        if (threadKey == null || threadKey.isBlank()) {
            return 0;
        }
        Integer count = jdbc.queryForObject(
                "select count(*) from connector_inbound_event"
                        + " where connector_id = ? and thread_key = ? and run_id is not null",
                Integer.class, connectorId, threadKey);
        return count == null ? 0 : count;
    }
}
