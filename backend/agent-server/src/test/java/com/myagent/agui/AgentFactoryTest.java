package com.myagent.agui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.myagent.agentconfig.dto.AgentRequest;
import com.myagent.agentconfig.dto.AgentResponse;
import com.myagent.agentconfig.service.AgentConfigService;
import com.myagent.common.BizException;
import com.myagent.modelconfig.dto.ModelRequest;
import com.myagent.modelconfig.dto.ProviderRequest;
import com.myagent.modelconfig.service.ModelConfigService;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** AgentFactory：把数据库配置翻译为 AgentScope HarnessAgent（Task 4.1）。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AgentFactoryTest {

    @Autowired
    private AgentFactory agentFactory;

    @Autowired
    private AgentConfigService agentConfigService;

    @Autowired
    private ModelConfigService modelConfigService;

    @AfterEach
    void clearCache() {
        // 测试事务会回滚，缓存必须清掉，避免 id 复用导致读到上一轮的实例。
        agentFactory.invalidateAll();
    }

    private Long createModel() {
        Long providerId = modelConfigService
                .createProvider(new ProviderRequest(
                        "FactoryTest-Provider-" + System.nanoTime(),
                        "OPENAI_COMPATIBLE",
                        "http://localhost:8099/v1",
                        "sk-factory",
                        "ENABLED"))
                .id();
        return modelConfigService
                .createModel(new ModelRequest(providerId, "factory-test-model", "工厂测试模型", null, "ENABLED"))
                .id();
    }

    private AgentResponse createAgent(String agentId, String sysPrompt, String permissionMode) {
        Long modelId = createModel();
        return agentConfigService.create(new AgentRequest(
                "Factory-" + agentId,
                agentId,
                sysPrompt,
                modelId,
                null,
                null,
                permissionMode,
                7,
                "ENABLED",
                List.of(),
                List.of()));
    }

    @Test
    void 从数据库配置构建出HarnessAgent() {
        AgentResponse config = createAgent("factory-agent-" + System.nanoTime(), "你是工厂测试助手", "REQUIRE_APPROVAL");

        HarnessAgent agent = agentFactory.build(config.id());

        assertThat(agent).isNotNull();
        assertThat(agent.getName()).isEqualTo(config.name());
        // 注：HarnessAgent 的 getAgentId() 返回框架内部生成的身份，不等于平台配置的 agent_id，
        // 因此这里只校验实例可用与名称，平台侧标识以 AgentFactory 入参为准。
        assertThat(agent.getAgentId()).isNotBlank();

        // 第二次构建应命中缓存，返回同一实例
        assertThat(agentFactory.build(config.id())).isSameAs(agent);
    }

    @Test
    void 修改配置后invalidate生效并重建实例() {
        AgentResponse config = createAgent("factory-inv-" + System.nanoTime(), "第一版提示词", "ALWAYS_ALLOW");
        HarnessAgent first = agentFactory.build(config.id());

        agentConfigService.update(
                config.id(),
                new AgentRequest(
                        config.name(), config.agentId(), "第二版提示词", config.modelId(), null, null,
                        "ALWAYS_ALLOW", 7, "ENABLED", List.of(), List.of()));

        HarnessAgent second = agentFactory.build(config.id());
        assertThat(second).isNotSameAs(first);
    }

    @Test
    void 禁用Agent构建时报业务错误() {
        AgentResponse config = createAgent("factory-dis-" + System.nanoTime(), "提示词", "ALWAYS_ALLOW");
        agentConfigService.update(
                config.id(),
                new AgentRequest(
                        config.name(), config.agentId(), "提示词", config.modelId(), null, null,
                        "ALWAYS_ALLOW", 7, "DISABLED", List.of(), List.of()));

        assertThatThrownBy(() -> agentFactory.build(config.id()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已禁用");
    }

    @Test
    void 不存在的Agent构建时报404业务错误() {
        assertThatThrownBy(() -> agentFactory.build(999_999_999L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Agent 不存在");
    }

    @Test
    void 平台权限模式映射到AgentScope权限模式() {
        assertThat(AgentFactory.toPermissionMode("ALWAYS_ALLOW")).isEqualTo(PermissionMode.BYPASS);
        assertThat(AgentFactory.toPermissionMode("ALWAYS_DENY")).isEqualTo(PermissionMode.DONT_ASK);
        assertThat(AgentFactory.toPermissionMode("REQUIRE_APPROVAL")).isEqualTo(PermissionMode.DEFAULT);
        assertThat(AgentFactory.toPermissionMode(null)).isEqualTo(PermissionMode.DEFAULT);
    }

    @Test
    void 仅REQUIRE_APPROVAL会为工具加ASK规则() {
        Toolkit toolkit = new Toolkit();

        // 其余模式不加 ask 规则（实测：仅设 DEFAULT 模式拦不住工具）
        assertThat(AgentFactory.buildPermissionContext("ALWAYS_ALLOW", toolkit).getAskRules()).isEmpty();
        assertThat(AgentFactory.buildPermissionContext("ALWAYS_DENY", toolkit).getAskRules()).isEmpty();

        // REQUIRE_APPROVAL 必须加，且至少包含通配（用于覆盖 Agent 实际拥有的工具）
        var context = AgentFactory.buildPermissionContext("REQUIRE_APPROVAL", toolkit);
        assertThat(context.getAskRules()).isNotEmpty();
        assertThat(context.getAskRules().keySet()).contains("*");
        assertThat(context.getMode()).isEqualTo(PermissionMode.DEFAULT);
    }
}
