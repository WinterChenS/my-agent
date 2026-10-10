package com.myagent.agentconfig.cache;

/**
 * Agent 运行时缓存失效契约。
 *
 * <p>接口放在 {@code agentconfig} 包、实现放在 {@code agui} 包（门禁 G2：配置类包不得引用 AgentScope）。
 * 配置变更后调用，使运行中的 Agent 实例不再复用旧配置。
 */
public interface AgentCacheInvalidator {

    /** 单个 Agent 配置变更后失效。 */
    void invalidate(Long agentDbId);

    /** 全部失效（例如 provider / model 变更）。 */
    void invalidateAll();
}
