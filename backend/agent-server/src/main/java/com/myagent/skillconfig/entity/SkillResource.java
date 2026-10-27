package com.myagent.skillconfig.entity;

/**
 * Skill 的资源文件（{@code skill_resource} 表的一行）。
 *
 * <p>结构对齐 AgentScope 官方仓库的 {@code agentscope_skill_resources}：{@code resource_path} 是相对技能根目录的
 * 路径（如 {@code scripts/run.py}），{@code resource_content} 是文件正文；二进制文件按官方约定以
 * {@code base64:} 前缀存文本（写入工作区时由官方 {@code MarketplaceStager} 解码）。
 *
 * <p>复合主键 {@code (skill_id, resource_path)}，因此不使用 MyBatis-Plus 的 {@code BaseMapper}，
 * 改由 {@link com.myagent.skillconfig.mapper.SkillResourceMapper} 用显式 SQL 访问。
 */
public class SkillResource {

    private Long skillId;

    private String resourcePath;

    private String resourceContent;

    public SkillResource() {}

    public SkillResource(Long skillId, String resourcePath, String resourceContent) {
        this.skillId = skillId;
        this.resourcePath = resourcePath;
        this.resourceContent = resourceContent;
    }

    public Long getSkillId() {
        return skillId;
    }

    public void setSkillId(Long skillId) {
        this.skillId = skillId;
    }

    public String getResourcePath() {
        return resourcePath;
    }

    public void setResourcePath(String resourcePath) {
        this.resourcePath = resourcePath;
    }

    public String getResourceContent() {
        return resourceContent;
    }

    public void setResourceContent(String resourceContent) {
        this.resourceContent = resourceContent;
    }
}
