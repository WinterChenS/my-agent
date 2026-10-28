package com.myagent.agui.connector;

import com.myagent.connectorconfig.ConnectorRecord;
import com.myagent.connectorconfig.ConnectorService;
import com.myagent.connectorconfig.ConnectorTypeDescriptor;
import com.myagent.connectorconfig.ConnectorTypeRegistry;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 把「Agent 绑定的连接器」变成官方 Toolkit 里的工具。
 *
 * <p>注册规则（很关键）：
 * <ul>
 *   <li>只有 Agent **绑定过某类型**的连接器，才会注册该类型的工具；</li>
 *   <li>具体用哪个账号在**调用时**按当前用户解析，所以多人共用一个 Agent 也不会串号；</li>
 *   <li>用户自己没有该类型的连接器时，工具会返回一句可操作的提示（去"能力 → 连接器"添加并绑定）。</li>
 * </ul>
 */
@Component
public class ConnectorToolkit {

    private static final Logger log = LoggerFactory.getLogger(ConnectorToolkit.class);

    private final ConnectorService connectorService;
    private final List<ConnectorProvider> providers;

    public ConnectorToolkit(ConnectorService connectorService, List<ConnectorProvider> providers) {
        this.connectorService = connectorService;
        this.providers = providers;
    }

    /** 该 Agent 绑定了哪些连接器类型（去重）。 */
    public List<String> boundTypes(long agentId) {
        return connectorService.typesBoundToAgent(agentId);
    }

    /**
     * 注册工具，返回注册的工具名（日志/测试用）。
     *
     * @param agentId 数据库里的 Agent id
     */
    public List<String> register(Toolkit toolkit, long agentId, ConnectorTypeRegistry registry) {
        List<String> registered = new ArrayList<>();
        for (String type : boundTypes(agentId)) {
            ConnectorTypeDescriptor descriptor;
            try {
                descriptor = registry.require(type);
            } catch (RuntimeException ex) {
                log.warn("连接器类型无描述，跳过 type={}: {}", type, ex.getMessage());
                continue;
            }
            ConnectorProvider provider = providers.stream()
                    .filter(candidate -> candidate.type().equalsIgnoreCase(type))
                    .findFirst()
                    .orElse(null);
            if (provider == null) {
                log.warn("连接器类型没有 Provider 实现，跳过 type={}", type);
                continue;
            }
            for (AgentTool tool : provider.tools(agentId, descriptor)) {
                toolkit.registerAgentTool(tool);
                registered.add(tool.getName());
            }
        }
        if (!registered.isEmpty()) {
            log.info("Agent {} 注册连接器工具: {}", agentId, registered);
        }
        return registered;
    }

    /** 「测试连接」的实现出口（{@code connectorconfig.ConnectorProbePort} 由此提供）。 */
    public com.myagent.connectorconfig.dto.ConnectorTestResult probe(String type, java.util.Map<String, String> config) {
        ConnectorProvider provider = providers.stream()
                .filter(candidate -> candidate.type().equalsIgnoreCase(type))
                .findFirst()
                .orElseThrow(() -> com.myagent.common.BizException.badRequest("暂不支持测试该类型: " + type));
        return provider.probe(config);
    }

    /** 入站轮询用：按类型找 provider（找不到返回 null）。 */
    public ConnectorProvider providerFor(String type) {
        return providers.stream()
                .filter(candidate -> candidate.type().equalsIgnoreCase(type))
                .findFirst()
                .orElse(null);
    }
}
