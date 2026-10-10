package com.myagent.agui;

import com.myagent.skillconfig.cache.SkillSyncTrigger;
import com.myagent.skillconfig.entity.Skill;
import com.myagent.skillconfig.mapper.SkillMapper;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.postgresql.PostgresSkillRepository;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 把平台 {@code skill} 表同步到 AgentScope 官方 Skill 仓库（{@code skill-postgresql-repository}）。
 *
 * <p>职责划分：
 * <ul>
 *   <li>平台 {@code skill} 表：CRUD、版本、启停、Agent 绑定（Flyway 管理）
 *   <li>官方仓库：Agent 运行时真正读取的 Skill 存储，由官方扩展自建并管理自己的 schema
 *       （默认 {@code agentscope.agentscope_skills} / {@code agentscope_skill_resources}）
 * </ul>
 *
 * <p>门禁 G8：Skill 的存储与读取全部走官方实现，不自研加载逻辑。
 * 门禁 G2：本类位于 {@code agui} 包，配置类包不引用 AgentScope。
 */
@Component
public class SkillRepositorySync implements SkillSyncTrigger {

    private static final Logger log = LoggerFactory.getLogger(SkillRepositorySync.class);
    private static final String SOURCE = "myagent";

    private final DataSource dataSource;
    private final SkillMapper skillMapper;

    public SkillRepositorySync(DataSource dataSource, SkillMapper skillMapper) {
        this.dataSource = dataSource;
        this.skillMapper = skillMapper;
    }

    @PostConstruct
    void init() {
        try {
            requestSync();
        } catch (Exception ex) {
            // 启动期同步失败不应阻止应用启动（例如仓库表尚未就绪）
            log.warn("启动时同步 Skill 到官方仓库失败: {}", ex.toString());
        }
    }

    /**
     * 新建一个官方仓库句柄。
     *
     * <p>每个 Agent 使用独立句柄：Agent 关闭时只会关掉自己的句柄，不会影响其它 Agent 或同步用的句柄。
     */
    public AgentSkillRepository newRepository() {
        return PostgresSkillRepository.builder(dataSource)
                .schemaName("agentscope")
                .skillsTableName("agentscope_skills")
                .resourcesTableName("agentscope_skill_resources")
                .createIfNotExist(true)
                .writeable(false)
                .build();
    }

    @Override
    public void requestSync() {
        List<Skill> enabled = skillMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<Skill>lambdaQuery()
                        .eq(Skill::getStatus, "ENABLED")
                        .orderByAsc(Skill::getName));
        if (enabled.isEmpty()) {
            log.debug("没有启用中的 Skill，跳过同步");
            return;
        }

        List<AgentSkill> agentSkills = enabled.stream()
                .map(s -> AgentSkill.builder()
                        .name(s.getName())
                        .description(s.getDescription())
                        .skillContent(StringUtils.hasText(s.getContent()) ? s.getContent() : "")
                        .source(SOURCE)
                        .build())
                .toList();

        try (AgentSkillRepository repository = PostgresSkillRepository.builder(dataSource)
                .schemaName("agentscope")
                .skillsTableName("agentscope_skills")
                .resourcesTableName("agentscope_skill_resources")
                .createIfNotExist(true)
                .writeable(true)
                .build()) {
            // overwrite=true：按名称覆盖已有 Skill，保证平台侧修改能生效
            boolean ok = repository.save(agentSkills, true);
            if (!ok) {
                log.warn("同步 Skill 到官方仓库返回 false");
            } else {
                log.info("已同步 {} 个启用的 Skill 到官方仓库", agentSkills.size());
            }
        } catch (Exception ex) {
            log.warn("同步 Skill 到官方仓库失败: {}", ex.toString());
        }
    }

    /** 当前启用中的 Skill 名称集合（用于诊断与测试）。 */
    public Set<String> enabledSkillNames() {
        return Set.copyOf(skillMapper.findEnabledNames());
    }
}
