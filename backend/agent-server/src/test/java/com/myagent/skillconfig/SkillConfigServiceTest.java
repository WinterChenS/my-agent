package com.myagent.skillconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.myagent.agentconfig.dto.AgentRequest;
import com.myagent.agentconfig.service.AgentConfigService;
import com.myagent.common.BizException;
import com.myagent.modelconfig.dto.ModelRequest;
import com.myagent.modelconfig.dto.ProviderRequest;
import com.myagent.modelconfig.service.ModelConfigService;
import com.myagent.skillconfig.dto.SkillRequest;
import com.myagent.skillconfig.dto.SkillResponse;
import com.myagent.skillconfig.service.SkillConfigService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** Skill CRUD 与版本管理（连接专用测试库，事务回滚）。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SkillConfigServiceTest {

    @Autowired
    private SkillConfigService skillConfigService;

    @Autowired
    private AgentConfigService agentConfigService;

    @Autowired
    private ModelConfigService modelConfigService;

    private SkillResponse newSkill(String name, String content) {
        return skillConfigService.create(new SkillRequest(name, "测试 Skill", content, "ENABLED"));
    }

    @Test
    void 新建Skill版本为1() {
        SkillResponse created = newSkill("skill-a-" + System.nanoTime(), "回复末尾加【OK】");
        assertThat(created.id()).isNotNull();
        assertThat(created.version()).isEqualTo(1);
        assertThat(created.status()).isEqualTo("ENABLED");
    }

    @Test
    void 内容变化时版本自增_内容不变时不自增() {
        SkillResponse created = newSkill("skill-b-" + System.nanoTime(), "第一版内容");

        SkillResponse sameContent = skillConfigService.update(
                created.id(), new SkillRequest(created.name(), "改描述不改内容", "第一版内容", "ENABLED"));
        assertThat(sameContent.version()).as("内容未变不应自增版本").isEqualTo(1);

        SkillResponse changed = skillConfigService.update(
                created.id(), new SkillRequest(created.name(), "改描述不改内容", "第二版内容", "ENABLED"));
        assertThat(changed.version()).isEqualTo(2);
        assertThat(changed.content()).isEqualTo("第二版内容");
    }

    @Test
    void 名称重复返回业务错误() {
        String name = "skill-dup-" + System.nanoTime();
        newSkill(name, "c");
        assertThatThrownBy(() -> newSkill(name, "c"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已存在");
    }

    @Test
    void 被Agent绑定时不允许删除() {
        SkillResponse skill = newSkill("skill-bound-" + System.nanoTime(), "c");

        Long providerId = modelConfigService
                .createProvider(new ProviderRequest(
                        "SkillTest-Provider-" + System.nanoTime(),
                        "OPENAI_COMPATIBLE",
                        "http://localhost:8099/v1",
                        "k",
                        "ENABLED"))
                .id();
        Long modelId = modelConfigService
                .createModel(new ModelRequest(providerId, "skill-test-model", null, null, null, null, null, null, null, null, null, null, "ENABLED", null))
                .id();

        agentConfigService.create(new AgentRequest(
                "SkillTest-Agent-" + System.nanoTime(),
                "skill-test-agent-" + System.nanoTime(),
                "p",
                modelId,
                null,
                null,
                "ALWAYS_ALLOW",
                null,null,null,null,
                "ENABLED",
                List.of(skill.id()),
                List.of()));

        assertThatThrownBy(() -> skillConfigService.delete(skill.id()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("绑定");
    }

    @Test
    void 解绑后可删除() {
        SkillResponse skill = newSkill("skill-free-" + System.nanoTime(), "c");
        skillConfigService.delete(skill.id());
        assertThatThrownBy(() -> skillConfigService.get(skill.id()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Skill 不存在");
    }

    @Test
    void 按状态过滤列表() {
        newSkill("skill-on-" + System.nanoTime(), "c");
        skillConfigService.create(new SkillRequest(
                "skill-off-" + System.nanoTime(), "d", "c", "DISABLED"));

        assertThat(skillConfigService.list(1, 50, "DISABLED").records())
                .allMatch(r -> "DISABLED".equals(r.status()));
    }

    @Test
    void 说明为空要报明确的业务错误_而不是同步到官方仓库时炸500() {
        // 实测：只填名称不填说明 → 官方 AgentSkill 校验抛
        // IllegalArgumentException("The skill must have `name` and `description` fields.")
        // 前端只看到「未预期异常」500。现在提前拦成 400 的明确提示。
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> skillConfigService.create(
                        new SkillRequest("skill-nodesc-" + System.nanoTime(), "   ", "正文", "ENABLED")))
                .hasMessageContaining("说明不能为空");
    }

    @Test
    void 名称为空要被拦住() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> skillConfigService.create(
                        new SkillRequest("  ", "说明", "正文", "ENABLED")))
                .hasMessageContaining("名称不能为空");
    }

    @Test
    void 按Agent过滤技能列表_只返回它绑定的() {
        // 两个技能：一个绑到 Agent，一个不绑
        SkillResponse bound = newSkill("bound-" + System.nanoTime(), "绑定的");
        SkillResponse loose = newSkill("loose-" + System.nanoTime(), "没绑的");

        Long agentDbId = createAgentWithSkill(bound.id());

        List<SkillResponse> records = skillConfigService
                .list(1, 100, "ENABLED", agentDbId)
                .records();
        assertThat(records).extracting(SkillResponse::name).contains(bound.name());
        assertThat(records).extracting(SkillResponse::name).doesNotContain(loose.name());

        // 不传 agentId 时行为不变（管理页要看全量）
        assertThat(skillConfigService.list(1, 100, null).records())
                .extracting(SkillResponse::name)
                .contains(bound.name(), loose.name());
    }

    /** 建一个只绑定给定技能的 Agent，返回它的主键（与其它用例同一套建 provider/model 的写法）。 */
    private Long createAgentWithSkill(Long skillId) {
        String suffix = String.valueOf(System.nanoTime());
        Long providerId = modelConfigService
                .createProvider(new ProviderRequest(
                        "bind-provider-" + suffix, "OPENAI_COMPATIBLE", "http://localhost:8099/v1", "k", "ENABLED"))
                .id();
        Long modelId = modelConfigService
                .createModel(new ModelRequest(
                        providerId, "bind-model-" + suffix, null, null, null, null, null, null, null, null, null, null,
                        "ENABLED", null))
                .id();
        return agentConfigService
                .create(new AgentRequest(
                        "bind-agent-" + suffix,
                        "bind-agent-" + suffix,
                        "p",
                        modelId,
                        null,
                        null,
                        "ALWAYS_ALLOW",
                        null, null, null, null,
                        "ENABLED",
                        List.of(skillId),
                        List.of()))
                .id();
    }
}
