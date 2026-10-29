package com.myagent.agui.connector;

import com.myagent.agentconfig.cache.AgentCacheInvalidator;
import com.myagent.connectorconfig.event.ConnectorConfigChangedEvent;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 连接器变更 → 让 Agent 缓存失效。
 *
 * <p>没有这一环会怎样（用户实测）：Agent 的工具集在**构建时**确定并按 Agent 缓存，
 * 所以把连接器从「停用」改成「启用」后，下一轮对话用的还是旧实例 ——
 * 模型坚持说「我没有读邮件的功能」，用户只能刷新/重启才生效。
 *
 * <p>放在 {@code agui} 包是因为要调用 AgentScope 侧的 {@link AgentCacheInvalidator}（门禁 G2）。
 */
@Component
public class ConnectorCacheInvalidator {

    private static final Logger log = LoggerFactory.getLogger(ConnectorCacheInvalidator.class);

    private final AgentCacheInvalidator agents;

    public ConnectorCacheInvalidator(AgentCacheInvalidator agents) {
        this.agents = agents;
    }

    @EventListener
    public void onConnectorChanged(ConnectorConfigChangedEvent event) {
        List<Long> agentIds = event.agentIds();
        if (agentIds == null || agentIds.isEmpty()) {
            // 拿不到受影响 Agent（例如删除时绑定关系已级联清掉）：宁可整体失效，
            // 也不要让某些会话继续用着"没有连接器工具"的旧实例。
            log.info("连接器 {} 变更：失效全部 Agent 缓存", event.connectorId());
            agents.invalidateAll();
            return;
        }
        for (Long agentId : agentIds) {
            if (agentId != null) {
                agents.invalidate(agentId);
            }
        }
        log.info("连接器 {} 变更：失效 Agent 缓存 {}", event.connectorId(), agentIds);
    }
}
