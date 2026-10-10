package com.myagent.skillconfig.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.myagent.agentconfig.cache.AgentCacheInvalidator;
import com.myagent.common.BizException;
import com.myagent.common.PageResult;
import com.myagent.skillconfig.cache.SkillSyncTrigger;
import com.myagent.skillconfig.dto.SkillRequest;
import com.myagent.skillconfig.dto.SkillResponse;
import com.myagent.skillconfig.entity.Skill;
import com.myagent.skillconfig.mapper.SkillMapper;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Skill 配置：CRUD 与版本管理。 */
@Service
public class SkillConfigService {

    private static final Set<String> STATUSES = Set.of("ENABLED", "DISABLED");

    private final SkillMapper skillMapper;
    private final SkillSyncTrigger skillSyncTrigger;
    private final AgentCacheInvalidator agentCacheInvalidator;

    public SkillConfigService(
            SkillMapper skillMapper,
            SkillSyncTrigger skillSyncTrigger,
            AgentCacheInvalidator agentCacheInvalidator) {
        this.skillMapper = skillMapper;
        this.skillSyncTrigger = skillSyncTrigger;
        this.agentCacheInvalidator = agentCacheInvalidator;
    }

    public PageResult<SkillResponse> list(long current, long size, String status) {
        var query = Wrappers.<Skill>lambdaQuery()
                .eq(StringUtils.hasText(status), Skill::getStatus,
                        status == null ? null : status.trim().toUpperCase())
                .orderByAsc(Skill::getId);
        Page<Skill> page = skillMapper.selectPage(new Page<>(current, size), query);
        return PageResult.of(
                page.getRecords().stream().map(SkillConfigService::toResponse).toList(),
                page.getTotal(),
                page.getCurrent(),
                page.getSize());
    }

    public SkillResponse get(Long id) {
        return toResponse(requireSkill(id));
    }

    @Transactional
    public SkillResponse create(SkillRequest request) {
        validate(request);
        ensureNameUnique(request.name(), null);
        Skill skill = new Skill();
        skill.setName(request.name());
        skill.setDescription(request.description());
        skill.setContent(request.content());
        skill.setVersion(1);
        skill.setStatus(normalizeStatus(request.status()));
        skillMapper.insert(skill);
        afterChange();
        return toResponse(skill);
    }

    @Transactional
    public SkillResponse update(Long id, SkillRequest request) {
        Skill skill = requireSkill(id);
        validate(request);
        ensureNameUnique(request.name(), id);

        // 内容变化才自增版本，避免无意义的版本膨胀
        if (!Objects.equals(skill.getContent(), request.content())) {
            skill.setVersion((skill.getVersion() == null ? 1 : skill.getVersion()) + 1);
        }
        skill.setName(request.name());
        skill.setDescription(request.description());
        skill.setContent(request.content());
        skill.setStatus(normalizeStatus(request.status()));
        skillMapper.updateById(skill);
        afterChange();
        return toResponse(skill);
    }

    @Transactional
    public void delete(Long id) {
        requireSkill(id);
        long bindings = skillMapper.countBindings(id);
        if (bindings > 0) {
            throw BizException.conflict("该 Skill 被 " + bindings + " 个 Agent 绑定，无法删除");
        }
        skillMapper.deleteById(id);
        afterChange();
    }

    /** Skill 变化会影响所有 Agent 的提示词，因此让 Agent 缓存与官方仓库一起刷新。 */
    private void afterChange() {
        skillSyncTrigger.requestSync();
        agentCacheInvalidator.invalidateAll();
    }

    private Skill requireSkill(Long id) {
        Skill skill = id == null ? null : skillMapper.selectById(id);
        if (skill == null) {
            throw BizException.notFound("Skill 不存在: " + id);
        }
        return skill;
    }

    private void validate(SkillRequest request) {
        if (request.status() != null && !STATUSES.contains(request.status().trim().toUpperCase())) {
            throw BizException.badRequest("status 非法: " + request.status());
        }
    }

    private void ensureNameUnique(String name, Long excludeId) {
        Long existing = skillMapper.selectCount(Wrappers.<Skill>lambdaQuery()
                .eq(Skill::getName, name)
                .ne(excludeId != null, Skill::getId, excludeId));
        if (existing > 0) {
            throw BizException.conflict("Skill 名称已存在: " + name);
        }
    }

    private static String normalizeStatus(String status) {
        return StringUtils.hasText(status) ? status.trim().toUpperCase() : "ENABLED";
    }

    private static SkillResponse toResponse(Skill skill) {
        return new SkillResponse(
                skill.getId(),
                skill.getName(),
                skill.getDescription(),
                skill.getContent(),
                skill.getVersion(),
                skill.getStatus(),
                skill.getCreatedAt(),
                skill.getUpdatedAt());
    }
}
