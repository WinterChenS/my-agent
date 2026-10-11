package com.myagent.agui.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Task 11.1：PostgreSQL 版官方存储的单元/集成测试（跑在真实测试库上）。
 *
 * <p>覆盖两条链路：
 * <ul>
 *   <li>{@link PgAgentStateStore} —— 官方 {@code AgentStateStore} 的 PG 实现（会话状态）
 *   <li>{@link PgBaseStore} —— 官方 {@code BaseStore} 的 PG 实现（长期记忆，经官方 RemoteFilesystem 使用）
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PgMemoryStoresTest {

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private PgAgentStateStore stateStore;
    private PgBaseStore baseStore;

    private final String agentId = "test-agent-" + System.nanoTime();
    private final String sessionId = "test-session-" + System.nanoTime();

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        stateStore = new PgAgentStateStore(jdbc);
        baseStore = new PgBaseStore(jdbc);
    }

    // ---------- AgentStateStore ----------

    @Test
    void 会话状态可写入并读回() {
        AgentState state = AgentState.builder().sessionId(sessionId).userId(agentId).build();
        state.setSummary("这是摘要");

        stateStore.save(agentId, sessionId, "agent_state", state);

        assertThat(stateStore.exists(agentId, sessionId)).isTrue();
        AgentState loaded = stateStore.get(agentId, sessionId, "agent_state", AgentState.class).orElseThrow();
        assertThat(loaded.getSummary()).isEqualTo("这是摘要");
    }

    @Test
    void 同一状态重复保存是覆盖而不是新增行() {
        AgentState first = AgentState.builder().sessionId(sessionId).userId(agentId).build();
        first.setSummary("v1");
        stateStore.save(agentId, sessionId, "agent_state", first);

        AgentState second = AgentState.builder().sessionId(sessionId).userId(agentId).build();
        second.setSummary("v2");
        stateStore.save(agentId, sessionId, "agent_state", second);

        Integer rows = jdbc.queryForObject(
                "select count(*) from agent_state where agent_id = ? and session_id = ? and kind = 'SINGLE'",
                Integer.class, agentId, sessionId);
        assertThat(rows).isEqualTo(1);
        assertThat(stateStore.get(agentId, sessionId, "agent_state", AgentState.class)
                        .orElseThrow().getSummary())
                .isEqualTo("v2");
    }

    @Test
    void 列表状态按序写入并整体替换() {
        AgentState a = AgentState.builder().sessionId(sessionId).userId(agentId).build();
        a.setSummary("a");
        AgentState b = AgentState.builder().sessionId(sessionId).userId(agentId).build();
        b.setSummary("b");
        stateStore.save(agentId, sessionId, "msgs", List.of(a, b));
        assertThat(stateStore.getList(agentId, sessionId, "msgs", AgentState.class))
                .extracting(AgentState::getSummary)
                .containsExactly("a", "b");

        AgentState c = AgentState.builder().sessionId(sessionId).userId(agentId).build();
        c.setSummary("c");
        stateStore.save(agentId, sessionId, "msgs", List.of(c));
        assertThat(stateStore.getList(agentId, sessionId, "msgs", AgentState.class))
                .extracting(AgentState::getSummary)
                .containsExactly("c");
    }

    @Test
    void 会话状态可枚举与删除() {
        AgentState state = AgentState.builder().sessionId(sessionId).userId(agentId).build();
        state.setSummary("x");
        stateStore.save(agentId, sessionId, "agent_state", state);

        assertThat(stateStore.listSessionIds(agentId)).contains(sessionId);

        stateStore.delete(agentId, sessionId);
        assertThat(stateStore.exists(agentId, sessionId)).isFalse();
    }

    // ---------- BaseStore（长期记忆） ----------

    @Test
    void 记忆可写入并读回且版本递增() {
        List<String> ns = List.of("user", "1");
        baseStore.put(ns, "/2026-10-11.md", Map.of("content", "记住了 A"));

        StoreItem first = baseStore.get(ns, "/2026-10-11.md");
        assertThat(first).isNotNull();
        assertThat(first.value()).containsEntry("content", "记住了 A");
        long version = first.version();
        assertThat(version).isPositive();

        baseStore.put(ns, "/2026-10-11.md", Map.of("content", "记住了 A 和 B"));
        StoreItem second = baseStore.get(ns, "/2026-10-11.md");
        assertThat(second.value()).containsEntry("content", "记住了 A 和 B");
        assertThat(second.version()).isGreaterThan(version);
    }

    @Test
    void 乐观锁putIfVersion按版本生效() {
        List<String> ns = List.of("user", "2");
        baseStore.put(ns, "k", Map.of("content", "v1"));
        long version = baseStore.get(ns, "k").version();

        // 版本对得上 → 成功
        assertThat(baseStore.putIfVersion(ns, "k", Map.of("content", "v2"), version)).isTrue();
        // 版本已过期 → 失败
        assertThat(baseStore.putIfVersion(ns, "k", Map.of("content", "v3"), version)).isFalse();
        assertThat(baseStore.get(ns, "k").value()).containsEntry("content", "v2");
    }

    @Test
    void 记忆按命名空间隔离并可搜索与删除() {
        List<String> userA = List.of("user", "1001");
        List<String> userB = List.of("user", "1002");
        baseStore.put(userA, "/a.md", Map.of("content", "A 的记忆"));
        baseStore.put(userB, "/b.md", Map.of("content", "B 的记忆"));

        assertThat(baseStore.search(userA, 0, 10)).extracting(StoreItem::key).containsExactly("/a.md");
        assertThat(baseStore.get(userA, "/b.md")).isNull(); // 跨用户不可见

        baseStore.delete(userA, "/a.md");
        assertThat(baseStore.get(userA, "/a.md")).isNull();
    }

    @Test
    void 命名空间来自RuntimeContext的用户标识() {
        PgNamespaceFactory factory = new PgNamespaceFactory();
        RuntimeContext ctx = RuntimeContext.builder(RuntimeContext.empty())
                .userId("42")
                .sessionId("s-1")
                .build();
        assertThat(factory.getNamespace(ctx)).containsExactly("user", "42");

        // 拿不到用户标识时退化为 anonymous（不与他人记忆混在一起）
        assertThat(factory.getNamespace(RuntimeContext.empty()))
                .containsExactly("user", PgNamespaceFactory.ANONYMOUS);
    }
}
