package com.myagent.skillconfig.entity;

/** {@code skill_resource} 的按技能聚合计数（列表页展示「N 个文件」）。 */
public class SkillResourceCount {

    private Long skillId;

    private Integer resourceCount;

    public Long getSkillId() {
        return skillId;
    }

    public void setSkillId(Long skillId) {
        this.skillId = skillId;
    }

    public Integer getResourceCount() {
        return resourceCount;
    }

    public void setResourceCount(Integer resourceCount) {
        this.resourceCount = resourceCount;
    }
}
