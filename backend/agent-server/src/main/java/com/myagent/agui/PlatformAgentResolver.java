package com.myagent.agui;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.processor.AgentResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 把 AG-UI 的 agent 标识解析为平台的 HarnessAgent。
 *
 * <p>AgentScope 的 {@code AguiRequestProcessor} 通过本接口取 Agent，因此 HITL resume 等
 * 官方能力才能生效（直接调 adapter.run 会绕过这些处理）。
 */
@Component
public class PlatformAgentResolver implements AgentResolver {

    private static final Logger log = LoggerFactory.getLogger(PlatformAgentResolver.class);

    private final AgentFactory agentFactory;

    public PlatformAgentResolver(AgentFactory agentFactory) {
        this.agentFactory = agentFactory;
    }

    @Override
    public Agent resolveAgent(String agentId, String sessionId) {
        Long dbId = agentFactory.resolveAgentDbId(agentId);
        if (dbId == null) {
            throw com.myagent.common.BizException.badRequest("Agent 不存在: " + agentId);
        }
        log.debug("解析 agentId={} sessionId={} -> dbId={}", agentId, sessionId, dbId);
        return agentFactory.build(dbId);
    }

    @Override
    public boolean hasMemory(RuntimeContext runtimeContext) {
        // HarnessAgent 自带按线程的工作区记忆
        return runtimeContext != null && runtimeContext.getSessionId() != null;
    }
}
