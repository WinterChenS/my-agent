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
                .createModel(new ModelRequest(providerId, "skill-test-model", null, null, null, null, null, null, null, null, null, null, "ENABLED"))
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
}
