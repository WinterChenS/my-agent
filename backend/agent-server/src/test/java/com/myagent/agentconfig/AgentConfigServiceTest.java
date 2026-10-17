package com.myagent.agentconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.myagent.agentconfig.dto.AgentRequest;
import com.myagent.agentconfig.dto.AgentResponse;
import com.myagent.agentconfig.service.AgentConfigService;
import com.myagent.common.BizException;
import com.myagent.modelconfig.dto.ModelRequest;
import com.myagent.modelconfig.dto.ProviderRequest;
import com.myagent.modelconfig.service.ModelConfigService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** Agent CRUD 与 Skill / MCP 绑定（连接专用测试库，事务回滚）。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AgentConfigServiceTest {

    @Autowired
    private AgentConfigService agentConfigService;

    @Autowired
    private ModelConfigService modelConfigService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long modelId;
    private Long skillId;
    private Long mcpServerId;

    @BeforeEach
    void setUp() {
        Long providerId = modelConfigService
                .createProvider(new ProviderRequest(
                        "AgentTest-Provider-" + System.nanoTime(), "OPENAI_COMPATIBLE", "http://localhost:8099/v1", "k", "ENABLED"))
                .id();
        modelId = modelConfigService
                .createModel(new ModelRequest(providerId, "agent-test-model", "测试模型", null, null, null, null, null, null, null, null, null, "ENABLED", null))
                .id();

        // skill / mcp_server 的完整实现分别在 Phase 5 / Phase 6，这里只造绑定所需的行。
        jdbcTemplate.update(
                "INSERT INTO skill (name, description, content, status) VALUES (?, ?, ?, 'ENABLED')",
                "agent-test-skill-" + System.nanoTime(), "测试用 Skill", "回复末尾加【OK】");
        skillId = jdbcTemplate.queryForObject(
                "SELECT id FROM skill WHERE name LIKE 'agent-test-skill-%' ORDER BY id DESC LIMIT 1", Long.class);

        jdbcTemplate.update(
                "INSERT INTO mcp_server (name, transport, command, status) VALUES (?, 'STDIO', 'echo', 'ENABLED')",
                "agent-test-mcp-" + System.nanoTime());
        mcpServerId = jdbcTemplate.queryForObject(
                "SELECT id FROM mcp_server WHERE name LIKE 'agent-test-mcp-%' ORDER BY id DESC LIMIT 1", Long.class);
    }

    private AgentRequest request(String agentId, List<Long> skillIds, List<Long> mcpIds) {
        return new AgentRequest(
                "Agent-" + agentId,
                agentId,
                "你是测试助手",
                modelId,
                new BigDecimal("0.50"),
                1024,
                "REQUIRE_APPROVAL",
                15,null,null,null,
                "ENABLED",
                skillIds,
                mcpIds);
    }

    @Test
    void 创建Agent并回读绑定集合() {
        AgentResponse created = agentConfigService.create(
                request("agent-bind-" + System.nanoTime(), List.of(skillId), List.of(mcpServerId)));

        assertThat(created.id()).isNotNull();
        assertThat(created.skillIds()).containsExactly(skillId);
        assertThat(created.mcpServerIds()).containsExactly(mcpServerId);
        assertThat(created.permissionMode()).isEqualTo("REQUIRE_APPROVAL");
        assertThat(created.temperature()).isEqualByComparingTo("0.50");
        assertThat(created.maxIterations()).isEqualTo(15);

        // 通过 GET 路径再读一次，确认绑定确实落库
        AgentResponse loaded = agentConfigService.get(created.id());
        assertThat(loaded.skillIds()).containsExactly(skillId);
        assertThat(loaded.mcpServerIds()).containsExactly(mcpServerId);
    }

    @Test
    void 绑定集合在更新时整体替换() {
        AgentResponse created = agentConfigService.create(
                request("agent-replace-" + System.nanoTime(), List.of(skillId), List.of()));

        // 去掉 skill、加上 mcp
        AgentResponse updated = agentConfigService.update(
                created.id(),
                new AgentRequest(
                        created.name(), created.agentId(), "新提示词", modelId, null, null,
                        "ALWAYS_ALLOW", null,null,null,null, "ENABLED", List.of(), List.of(mcpServerId)));

        assertThat(updated.skillIds()).isEmpty();
        assertThat(updated.mcpServerIds()).containsExactly(mcpServerId);
        assertThat(updated.maxIterations()).isEqualTo(20); // 默认值
    }

    @Test
    void 重复agent_id返回业务错误() {
        String agentId = "agent-dup-" + System.nanoTime();
        agentConfigService.create(request(agentId, null, null));

        assertThatThrownBy(() -> agentConfigService.create(
                        new AgentRequest("另一个名字", agentId, null, modelId, null, null,
                                "ALWAYS_ALLOW", null,null,null,null, "ENABLED", null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("agent_id 已存在");
    }

    @Test
    void 非法permissionMode返回业务错误() {
        assertThatThrownBy(() -> agentConfigService.create(
                        new AgentRequest("名字", "agent-bad-" + System.nanoTime(), null, modelId, null, null,
                                "NOT_A_MODE", null,null,null,null, "ENABLED", null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("permissionMode");
    }

    @Test
    void 引用不存在的模型返回业务错误() {
        assertThatThrownBy(() -> agentConfigService.create(
                        new AgentRequest("名字", "agent-nomodel-" + System.nanoTime(), null, 999_999_999L, null, null,
                                "ALWAYS_ALLOW", null,null,null,null, "ENABLED", null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("模型不存在");
    }

    @Test
    void 绑定不存在的Skill时整体失败且不写入任何绑定() {
        String agentId = "agent-partial-" + System.nanoTime();
        assertThatThrownBy(() -> agentConfigService.create(
                        request(agentId, List.of(skillId, 999_999_999L), List.of(mcpServerId))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Skill 不存在");

        // 半更新检查：校验在任何写入之前完成，因此不应产生任何绑定行。
        Long links = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM agent_skill s JOIN agent a ON a.id = s.agent_id WHERE a.agent_id = ?",
                Long.class,
                agentId);
        assertThat(links).isZero();
        // 注：Agent 行本身的事务回滚由 AgentBindingRollbackTest（非事务型测试）覆盖。
    }

    @Test
    void 删除Agent会清理绑定() {
        AgentResponse created = agentConfigService.create(
                request("agent-del-" + System.nanoTime(), List.of(skillId), List.of(mcpServerId)));
        agentConfigService.delete(created.id());

        Long links = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM agent_skill WHERE agent_id = ?", Long.class, created.id());
        assertThat(links).isZero();
        assertThatThrownBy(() -> agentConfigService.get(created.id()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Agent 不存在");
    }
}
