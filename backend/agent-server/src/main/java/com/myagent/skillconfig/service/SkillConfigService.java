package com.myagent.skillconfig.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.myagent.agentconfig.cache.AgentCacheInvalidator;
import com.myagent.agentconfig.mapper.AgentSkillMapper;
import com.myagent.common.BizException;
import com.myagent.common.PageResult;
import com.myagent.skillconfig.cache.SkillSyncTrigger;
import com.myagent.skillconfig.dto.SkillRequest;
import com.myagent.skillconfig.dto.SkillResourceDto;
import com.myagent.skillconfig.dto.SkillResponse;
import com.myagent.skillconfig.entity.Skill;
import com.myagent.skillconfig.entity.SkillResource;
import com.myagent.skillconfig.entity.SkillResourceCount;
import com.myagent.skillconfig.mapper.SkillMapper;
import com.myagent.skillconfig.mapper.SkillResourceMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final SkillResourceMapper skillResourceMapper;
    private final AgentSkillMapper agentSkillMapper;
    private final SkillSyncTrigger skillSyncTrigger;
    private final AgentCacheInvalidator agentCacheInvalidator;

    public SkillConfigService(
            SkillMapper skillMapper,
            SkillResourceMapper skillResourceMapper,
            AgentSkillMapper agentSkillMapper,
            SkillSyncTrigger skillSyncTrigger,
            AgentCacheInvalidator agentCacheInvalidator) {
        this.skillMapper = skillMapper;
        this.skillResourceMapper = skillResourceMapper;
        this.agentSkillMapper = agentSkillMapper;
        this.skillSyncTrigger = skillSyncTrigger;
        this.agentCacheInvalidator = agentCacheInvalidator;
    }

    public PageResult<SkillResponse> list(long current, long size, String status) {
        var query = Wrappers.<Skill>lambdaQuery()
                .eq(StringUtils.hasText(status), Skill::getStatus,
                        status == null ? null : status.trim().toUpperCase())
                .orderByAsc(Skill::getId);
        Page<Skill> page = skillMapper.selectPage(new Page<>(current, size), query);
        List<Long> ids = page.getRecords().stream().map(Skill::getId).toList();
        Map<Long, Integer> resourceCounts = resourceCounts(ids);
        Map<Long, Integer> boundCounts = boundAgentCounts(ids);
        return PageResult.of(
                page.getRecords().stream()
                        .map(skill -> toResponse(
                                skill,
                                resourceCounts.getOrDefault(skill.getId(), 0),
                                boundCounts.getOrDefault(skill.getId(), 0)))
                        .toList(),
                page.getTotal(),
                page.getCurrent(),
                page.getSize());
    }

    public SkillResponse get(Long id) {
        Skill skill = requireSkill(id);
        return toResponse(
                skill,
                skillResourceMapper.countBySkillId(id),
                (int) agentSkillMapper.countAgentsBySkillId(id));
    }

    /**
     * 某技能的资源文件清单（不含正文）。
     *
     * <p>为什么单独一个接口：渐进式披露的技能动辄几十上百个资源（实测 ui-ux-pro-max 有 72 个、
     * 合计 3.5MB），全塞进列表响应会让管理页明显变慢。这里只给路径 + 大小，点开才读正文。
     */
    public List<SkillResourceDto> listResources(Long id) {
        requireSkill(id);
        return skillResourceMapper.findBySkillId(id).stream()
                .map(resource -> SkillResourceDto.meta(
                        resource.getResourcePath(),
                        resource.getResourceContent() == null ? 0 : resource.getResourceContent().length(),
                        isBinary(resource)))
                .toList();
    }

    /** 读单个资源正文（路径必须属于该技能，防越权读别的技能）。 */
    public SkillResourceDto readResource(Long id, String path) {
        requireSkill(id);
        if (!StringUtils.hasText(path)) {
            throw BizException.badRequest("path 不能为空");
        }
        return skillResourceMapper.findBySkillId(id).stream()
                .filter(resource -> resource.getResourcePath().equals(path))
                .findFirst()
                .map(resource -> new SkillResourceDto(
                        resource.getResourcePath(),
                        resource.getResourceContent() == null ? 0 : resource.getResourceContent().length(),
                        isBinary(resource),
                        resource.getResourceContent()))
                .orElseThrow(() -> BizException.notFound("该技能没有这个资源文件: " + path));
    }

    private static boolean isBinary(SkillResource resource) {
        return resource.getResourceContent() != null && resource.getResourceContent().startsWith("base64:");
    }

    /**
     * 批量取「有多少 Agent 绑定了这个技能」。
     *
     * <p>为什么要在列表里露出：导入成功后如果没绑定任何 Agent，模型在对话里**根本看不到**这个技能
     * （SkillFilter 只放行绑定的），用户会以为"装了但用不了"甚至"没装"。0 必须显眼。
     */
    private Map<Long, Integer> boundAgentCounts(List<Long> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> counts = new HashMap<>();
        for (Map<String, Object> row : agentSkillMapper.countAgentsBySkillIds(skillIds)) {
            Object id = row.get("skill_id");
            Object cnt = row.get("cnt");
            if (id instanceof Number idNum && cnt instanceof Number cntNum) {
                counts.put(idNum.longValue(), cntNum.intValue());
            }
        }
        return counts;
    }

    /** 批量取资源计数（列表页一次查完，避免每个 Skill 各查一次）。 */
    private Map<Long, Integer> resourceCounts(List<Long> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> counts = new HashMap<>();
        for (SkillResourceCount row : skillResourceMapper.countBySkillIds(skillIds)) {
            counts.put(row.getSkillId(), row.getResourceCount() == null ? 0 : row.getResourceCount());
        }
        return counts;
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
        return toResponse(skill, 0, 0);
    }

    @Transactional
    public SkillResponse update(Long id, SkillRequest request) {
        Skill skill = requireSkill(id);
        validate(request);
        ensureNameUnique(request.name(), id);

        // 外部导入的技能**内容只读**：它是仓库的投影，在平台上改完下次重新导入就被覆盖，
        // 用户会以为改成功了。要改就改仓库再导入（管理页有「按来源更新」）。
        // 平台本地的状态（启用/停用）仍然可以改 —— 那是运行开关，不属于仓库内容。
        if (StringUtils.hasText(skill.getSourceUrl()) && contentDiffers(skill, request)) {
            throw BizException.conflict(
                    "该技能由仓库导入，内容只读：请改仓库后重新导入。来源 " + skill.getSourceUrl());
        }

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
        return toResponse(
                skill,
                skillResourceMapper.countBySkillId(id),
                (int) agentSkillMapper.countAgentsBySkillId(id));
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

    /** 导入的技能只允许改启用状态；名称/说明/正文只要有一个不同就算"改内容"。 */
    private static boolean contentDiffers(Skill skill, SkillRequest request) {
        return !Objects.equals(skill.getName(), request.name())
                || !Objects.equals(skill.getDescription(), request.description())
                || !Objects.equals(skill.getContent(), request.content());
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
        // 官方技能仓库（AgentScope AgentSkill）要求 name 与 description 都不能为空，
        // 否则同步时会抛 IllegalArgumentException，前端只看到一个「未预期异常」500。
        // 实测：只填名称不填说明 → 创建直接失败。所以在这里提前给出明确提示。
        if (!StringUtils.hasText(request.name())) {
            throw BizException.badRequest("名称不能为空");
        }
        if (!StringUtils.hasText(request.description())) {
            throw BizException.badRequest("说明不能为空：AgentScope 技能必须有 name 与 description（说明还决定模型何时命中该技能）");
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

    private static SkillResponse toResponse(Skill skill, int resourceCount, int boundAgentCount) {
        return new SkillResponse(
                skill.getId(),
                skill.getName(),
                skill.getDescription(),
                skill.getContent(),
                skill.getVersion(),
                skill.getStatus(),
                skill.getSourceUrl(),
                skill.getSourceRef(),
                resourceCount,
                boundAgentCount,
                skill.getCreatedAt(),
                skill.getUpdatedAt());
    }
}
