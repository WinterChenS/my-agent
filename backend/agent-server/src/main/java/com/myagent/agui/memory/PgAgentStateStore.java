package com.myagent.agui.memory;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonCodec;
import io.agentscope.core.util.JsonUtils;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 官方 {@link AgentStateStore} 的 PostgreSQL 实现（D23 / Task 11.1）。
 *
 * <p>替换框架默认的 {@code JsonFileAgentStateStore}，经 {@code HarnessAgent.Builder.stateStore(...)}
 * 注入后，框架的会话状态（上下文、summary、权限上下文等）就落在数据库而不是
 * {@code .myagent-workspace/<userId>/agents/<agentId>/sessions/*.jsonl}。
 *
 * <p>门禁 G8：只实现官方 SPI，不改框架状态管理逻辑；序列化复用官方 {@link JsonUtils#getJsonCodec()}，
 * 与官方文件版实现保持同一套 JSON 形状。
 *
 * <p>本类位于 {@code agui/} 包下（门禁 G2 规定 AgentScope API 只允许出现在 agui/、audit/、hitl/）。
 */
public class PgAgentStateStore implements AgentStateStore {

    /** 单值状态。 */
    static final String KIND_SINGLE = "SINGLE";
    /** 列表状态（同一 key 多条，按 ordinal 排序）。 */
    static final String KIND_LIST = "LIST";

    private final JdbcTemplate jdbc;
    private final JsonCodec codec = JsonUtils.getJsonCodec();

    public PgAgentStateStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(String agentId, String sessionId, String stateKey, State state) {
        upsert(agentId, sessionId, stateKey, KIND_SINGLE, 0, codec.toJson(state));
    }

    @Override
    public void save(String agentId, String sessionId, String stateKey, List<? extends State> states) {
        // 列表语义是「整段替换」，与官方文件版 rewriteEntireList 一致
        jdbc.update(
                "delete from agent_state where agent_id = ? and session_id = ? and state_key = ? and kind = ?",
                agentId, sessionId, stateKey, KIND_LIST);
        if (states == null) {
            return;
        }
        int ordinal = 0;
        for (State state : states) {
            upsert(agentId, sessionId, stateKey, KIND_LIST, ordinal++, codec.toJson(state));
        }
    }

    @Override
    public <T extends State> Optional<T> get(String agentId, String sessionId, String stateKey, Class<T> type) {
        List<String> payloads = jdbc.queryForList(
                "select payload::text from agent_state where agent_id = ? and session_id = ? "
                        + "and state_key = ? and kind = ? and ordinal = 0",
                String.class, agentId, sessionId, stateKey, KIND_SINGLE);
        return payloads.isEmpty() ? Optional.empty() : Optional.of(codec.fromJson(payloads.get(0), type));
    }

    @Override
    public <T extends State> List<T> getList(String agentId, String sessionId, String stateKey, Class<T> type) {
        List<String> payloads = jdbc.queryForList(
                "select payload::text from agent_state where agent_id = ? and session_id = ? "
                        + "and state_key = ? and kind = ? order by ordinal asc",
                String.class, agentId, sessionId, stateKey, KIND_LIST);
        return payloads.stream().map(json -> codec.fromJson(json, type)).toList();
    }

    @Override
    public boolean exists(String agentId, String sessionId) {
        Boolean exists = jdbc.queryForObject(
                "select exists(select 1 from agent_state where agent_id = ? and session_id = ?)",
                Boolean.class, agentId, sessionId);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public void delete(String agentId, String sessionId) {
        jdbc.update("delete from agent_state where agent_id = ? and session_id = ?", agentId, sessionId);
    }

    @Override
    public void delete(String agentId, String sessionId, String stateKey) {
        jdbc.update(
                "delete from agent_state where agent_id = ? and session_id = ? and state_key = ?",
                agentId, sessionId, stateKey);
    }

    @Override
    public Set<String> listSessionIds(String agentId) {
        return Set.copyOf(jdbc.queryForList(
                "select distinct session_id from agent_state where agent_id = ?", String.class, agentId));
    }

    private void upsert(String agentId, String sessionId, String stateKey, String kind, int ordinal, String json) {
        jdbc.update(
                """
                insert into agent_state
                    (agent_id, session_id, state_key, kind, ordinal, payload, version, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?::jsonb, 1, now(), now())
                on conflict (agent_id, session_id, kind, state_key, ordinal)
                do update set payload = excluded.payload,
                              version = agent_state.version + 1,
                              updated_at = now()
                """,
                agentId, sessionId, stateKey, kind, ordinal, json);
    }
}
