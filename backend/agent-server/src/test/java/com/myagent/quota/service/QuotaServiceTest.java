package com.myagent.quota.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.myagent.agentconfig.entity.Agent;
import com.myagent.agentconfig.mapper.AgentMapper;
import com.myagent.audit.entity.TokenUsage;
import com.myagent.audit.mapper.TokenUsageMapper;
import com.myagent.auth.entity.SysUser;
import com.myagent.auth.mapper.SysUserMapper;
import com.myagent.modelconfig.entity.LlmModel;
import com.myagent.modelconfig.entity.LlmProvider;
import com.myagent.modelconfig.mapper.LlmModelMapper;
import com.myagent.modelconfig.mapper.LlmProviderMapper;
import com.myagent.quota.dto.QuotaStatus;
import com.myagent.quota.service.QuotaService;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 支出配额（tier1-upgrades ④）：纯函数 + 用户级/Agent 级两级判断。
 *
 * <p>补这段的原因：额度此前只有「接口实测」，没有自动化回归，
 * 尤其 **Agent 级额度**那条分支从未被测试覆盖过。
 *
 * <p>价格约定：模型价 1 元/百万 token ⇒ 1,000,000 prompt tokens 恰好花掉 1 元，
 * 于是「花掉 1 元」和「额度 1 元」构成 exceeds 的边界（>= 即超）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class QuotaServiceTest {

    private static final BigDecimal PRICE = new BigDecimal("1");

    @Autowired
    private QuotaService quotaService;

    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private AgentMapper agentMapper;

    @Autowired
    private LlmProviderMapper providerMapper;

    @Autowired
    private LlmModelMapper modelMapper;

    @Autowired
    private TokenUsageMapper usageMapper;


    // ---------- 纯函数 ----------

    @Test
    void 用满即超限_额度为空或零表示不限() {
        assertThat(QuotaService.exceeds(new BigDecimal("0.5"), new BigDecimal("0.5"))).isTrue();
        assertThat(QuotaService.exceeds(new BigDecimal("0.6"), new BigDecimal("0.5"))).isTrue();
        assertThat(QuotaService.exceeds(new BigDecimal("0.4"), new BigDecimal("0.5"))).isFalse();
        // 空/零额度 = 不限，绝不能把用户拦住（spec：未设置额度时行为不变）
        assertThat(QuotaService.exceeds(new BigDecimal("999"), null)).isFalse();
        assertThat(QuotaService.exceeds(new BigDecimal("999"), BigDecimal.ZERO)).isFalse();
        assertThat(QuotaService.exceeds(null, new BigDecimal("1"))).isFalse();
    }

    @Test
    void 极小额度不能显示成0() {
        // 实测踩过：金额按 4 位小数四舍五入，0.000001 会显示成 0，用户看不懂
        assertThat(QuotaService.plain(new BigDecimal("0.000001"))).isEqualTo("0.000001");
        assertThat(QuotaService.plain(new BigDecimal("12.500000"))).isEqualTo("12.5");
        assertThat(QuotaService.plain(null)).isEqualTo("-");
    }

    @Test
    void 超限提示必须包含已用与额度() {
        String userMessage = QuotaService.buildMessage(
                "USER",
                new BigDecimal("0.027602"),
                new BigDecimal("0.000001"),
                new BigDecimal("1.5"),
                new BigDecimal("10"),
                "CNY");
        assertThat(userMessage)
                .contains("已用")
                .contains("0.027602")
                .contains("日额度")
                .contains("0.000001");
        assertThat(userMessage).doesNotContain("月额度"); // 本月没超就不该提

        String agentMessage = QuotaService.buildMessage(
                "AGENT", BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("5"), new BigDecimal("5"), "USD");
        assertThat(agentMessage).contains("该 Agent").contains("月额度").contains("USD");
    }

    // ---------- 两级额度（连测试库，事务回滚） ----------

    @Test
    void 未设额度时一律不拦截() {
        SysUser user = newUser();
        Agent agent = newAgent();

        QuotaStatus status = quotaService.evaluate(user.getId(), agent.getId());
        assertThat(status.exceeded()).isFalse();
        assertThat(status.exceededScope()).isNull();
        assertThat(status.nearLimit()).isFalse();
        assertThat(status.mode()).isEqualTo("BLOCK");
    }

    @Test
    void 用户日额度用满则超限且归因到USER() {
        SysUser user = newUser();
        setUserQuota(user, PRICE, null); // 日额度 1 元
        Agent agent = newAgent();
        spend(user.getId(), agent, 1_000_000); // 正好花掉 1 元 → >= 额度

        QuotaStatus status = quotaService.evaluate(user.getId(), agent.getId());
        assertThat(status.exceeded()).isTrue();
        assertThat(status.exceededScope()).isEqualTo("USER");
        assertThat(status.message()).contains("你").contains("日额度");
    }

    @Test
    void Agent日额度用满则超限且归因到AGENT() {
        SysUser user = newUser(); // 用户不限
        Agent agent = newAgent();
        setAgentQuota(agent, PRICE);
        spend(user.getId(), agent, 1_000_000);

        QuotaStatus status = quotaService.evaluate(user.getId(), agent.getId());
        assertThat(status.exceeded()).isTrue();
        assertThat(status.exceededScope()).isEqualTo("AGENT");
        assertThat(status.message()).contains("该 Agent").contains("日额度");
    }

    @Test
    void 月额度同样生效() {
        SysUser user = newUser();
        setUserQuota(user, null, PRICE); // 只设月额度 1 元
        Agent agent = newAgent();
        spend(user.getId(), agent, 1_000_000);

        QuotaStatus status = quotaService.evaluate(user.getId(), agent.getId());
        assertThat(status.exceeded()).isTrue();
        assertThat(status.message()).contains("月额度");
    }

    @Test
    void 接近上限但未超时给出nearLimit() {
        SysUser user = newUser();
        setUserQuota(user, new BigDecimal("10"), null); // 日额度 10 元
        Agent agent = newAgent();
        spend(user.getId(), agent, 9_000_000); // 花 9 元 = 90%

        QuotaStatus status = quotaService.evaluate(user.getId(), agent.getId());
        assertThat(status.exceeded()).isFalse();
        assertThat(status.nearLimit()).isTrue();
    }

    @Test
    void 额度按货币配置返回() {
        SysUser user = newUser();
        setUserQuota(user, PRICE, null);
        userMapper.updateQuota(user.getId(), Map.of("quota_currency", "USD"));
        Agent agent = newAgent();

        assertThat(quotaService.evaluate(user.getId(), agent.getId()).currency()).isEqualTo("USD");
    }

    // ---------- fixtures ----------

    private SysUser newUser() {
        SysUser user = new SysUser();
        user.setUsername("quota-user-" + System.nanoTime());
        user.setPasswordHash("x");
        user.setRole("USER");
        user.setStatus("ENABLED");
        userMapper.insert(user);
        return userMapper.selectById(user.getId());
    }

    private void setUserQuota(SysUser user, BigDecimal daily, BigDecimal monthly) {
        // 注意：不能用 Map.of —— 它不允许 null 值（这里正是要写 NULL = 不限）
        Map<String, Object> sets = new java.util.HashMap<>();
        sets.put("daily_quota", daily);
        sets.put("monthly_quota", monthly);
        userMapper.updateQuota(user.getId(), sets);
    }

    private void setAgentQuota(Agent agent, BigDecimal daily) {
        agentMapper.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.<Agent>lambdaUpdate()
                .set(Agent::getDailyQuota, daily)
                .set(Agent::getQuotaCurrency, "CNY")
                .eq(Agent::getId, agent.getId()));
    }

    /** 造一个带模型/供应商的 Agent（agent.model_id 有外键，必须一起建）。 */
    private Agent newAgent() {
        LlmProvider provider = new LlmProvider();
        provider.setName("quota-prov-" + System.nanoTime());
        provider.setProviderType("OPENAI_COMPATIBLE");
        provider.setBaseUrl("http://localhost:9/v1");
        provider.setApiKeyCipher("x");
        provider.setStatus("ENABLED");
        providerMapper.insert(provider);

        LlmModel model = new LlmModel();
        model.setProviderId(provider.getId());
        model.setModelId("quota-model-" + System.nanoTime());
        model.setPriceInput(PRICE);
        model.setPriceOutput(PRICE);
        model.setPriceCurrency("CNY");
        model.setStatus("ENABLED");
        modelMapper.insert(model);

        Agent agent = new Agent();
        agent.setName("quota-agent-" + System.nanoTime());
        agent.setAgentId("quota-agent-" + System.nanoTime());
        agent.setModelId(model.getId());
        agent.setPermissionMode("ALWAYS_ALLOW");
        agent.setStatus("ENABLED");
        agentMapper.insert(agent);
        return agentMapper.selectById(agent.getId());
    }

    /** 造一条该用户 + 该 Agent 今天的用量（价格 1 元/百万，token 数即「花了几分钱」）。 */
    private void spend(Long userId, Agent agent, int promptTokens) {
        TokenUsage usage = new TokenUsage();
        usage.setUserId(userId);
        usage.setAgentId(agent.getId());
        usage.setModelId(agent.getModelId());
        usage.setProviderType("OPENAI");
        usage.setPromptTokens(promptTokens);
        usage.setCompletionTokens(0);
        usage.setTotalTokens(promptTokens);
        usageMapper.insert(usage);
    }
}
