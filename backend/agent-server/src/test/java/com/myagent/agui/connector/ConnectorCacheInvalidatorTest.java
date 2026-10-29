package com.myagent.agui.connector;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.myagent.agentconfig.cache.AgentCacheInvalidator;
import com.myagent.connectorconfig.event.ConnectorConfigChangedEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 连接器变更要让 Agent 缓存失效。
 *
 * <p>用户实测：把连接器从停用改成启用后，模型还是说「我没有这个功能」——
 * 因为 Agent 实例（含工具集）是构建时缓存住的老对象。
 */
class ConnectorCacheInvalidatorTest {

    private final AgentCacheInvalidator agents = mock(AgentCacheInvalidator.class);
    private final ConnectorCacheInvalidator invalidator = new ConnectorCacheInvalidator(agents);

    @Test
    void 只失效受影响的Agent() {
        invalidator.onConnectorChanged(new ConnectorConfigChangedEvent(1L, List.of(37L, 38L)));

        verify(agents).invalidate(37L);
        verify(agents).invalidate(38L);
    }

    @Test
    void 拿不到受影响Agent时整体失效() {
        invalidator.onConnectorChanged(new ConnectorConfigChangedEvent(1L, List.of()));

        verify(agents).invalidateAll();
        verify(agents, never()).invalidate(anyLong());
    }
}
