package com.myagent.skillconfig.cache;

/**
 * Skill 变更后触发同步到 AgentScope 官方 Skill 仓库。
 *
 * <p>接口在 {@code skillconfig} 包、实现放 {@code agui} 包（门禁 G2：配置类包不得引用 AgentScope）。
 */
public interface SkillSyncTrigger {

    /** 请求把当前启用中的 Skill 同步到官方仓库。 */
    void requestSync();
}
