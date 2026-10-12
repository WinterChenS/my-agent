package com.myagent.agentconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.myagent.agentconfig.dto.AgentRequest;
import com.myagent.agentconfig.service.AgentConfigService;
import com.myagent.common.BizException;
import com.myagent.modelconfig.dto.ModelRequest;
import com.myagent.modelconfig.dto.ProviderRequest;
import com.myagent.modelconfig.service.ModelConfigService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 绑定校验失败时 Agent 行必须真实回滚。
 *
 * <p>刻意**不加** {@code @Transactional}：只有让 {@code AgentConfigService} 自己开启事务并因异常回滚，
 * 才能验证回滚真的发生（若测试自带事务，插入与断言处在同一事务里，看不到回滚效果）。
 */
@SpringBootTest
@ActiveProfiles("test")
class AgentBindingRollbackTest {

    @Autowired
    private AgentConfigService agentConfigService;

    @Autowired
    private ModelConfigService modelConfigService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 绑定校验失败时Agent行与绑定行均不落库() {
        Long providerId = null;
        Long modelId = null;
        Long skillId = null;
        String agentId = "rollback-agent-" + System.nanoTime();

        try {
            providerId = modelConfigService
                    .createProvider(new ProviderRequest(
                            "rollback-provider-" + System.nanoTime(),
                            "OPENAI_COMPATIBLE",
                            "http://localhost:8099/v1",
                            "k",
                            "ENABLED"))
                    .id();
            modelId = modelConfigService
                    .createModel(new ModelRequest(providerId, "rollback-model", null, null, null, null, null, null, null, null, null, "ENABLED"))
                    .id();
            skillId = jdbcTemplate.queryForObject(
                    "INSERT INTO skill (name, description, content, status) "
                            + "VALUES (?, '回滚测试', 'c', 'ENABLED') RETURNING id",
                    Long.class,
                    "rollback-skill-" + System.nanoTime());

            Long finalModelId = modelId;
            Long finalSkillId = skillId;

            assertThatThrownBy(() -> agentConfigService.create(new AgentRequest(
                            "rollback-agent-name",
                            agentId,
                            null,
                            finalModelId,
                            null,
                            null,
                            "ALWAYS_ALLOW",
                            null,
                            "ENABLED",
                            List.of(finalSkillId, 999_999_999L),
                            null)))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("Skill 不存在");

            Long agents = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM agent WHERE agent_id = ?", Long.class, agentId);
            assertThat(agents).as("事务回滚后不应残留 Agent 行").isZero();

            Long links = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM agent_skill s JOIN agent a ON a.id = s.agent_id WHERE a.agent_id = ?",
                    Long.class,
                    agentId);
            assertThat(links).as("不应残留绑定行").isZero();
        } finally {
            jdbcTemplate.update("DELETE FROM agent WHERE agent_id = ?", agentId);
            if (skillId != null) {
                jdbcTemplate.update("DELETE FROM skill WHERE id = ?", skillId);
            }
            if (modelId != null) {
                jdbcTemplate.update("DELETE FROM llm_model WHERE id = ?", modelId);
            }
            if (providerId != null) {
                jdbcTemplate.update("DELETE FROM llm_provider WHERE id = ?", providerId);
            }
        }
    }
}
