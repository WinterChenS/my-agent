package com.myagent.agui.connector.notion;

import com.fasterxml.jackson.databind.JsonNode;
import com.myagent.agui.connector.ConnectorProvider;
import com.myagent.connectorconfig.ConnectorService;
import com.myagent.connectorconfig.ConnectorTypeDescriptor;
import com.myagent.connectorconfig.dto.ConnectorTestResult;
import io.agentscope.core.tool.AgentTool;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Notion 连接器（NOTION）：7 个工具 + 连通性探测。
 *
 * <p>「测试连接」会真的调 {@code /v1/users/me} 与一次 {@code /v1/search}，
 * 顺便把工作区名和能看到的页面数报给用户 —— 这样"令牌对不对""页面有没有授权"一眼可见。
 */
public class NotionConnectorProvider implements ConnectorProvider {

    private final ConnectorService connectorService;

    public NotionConnectorProvider(ConnectorService connectorService) {
        this.connectorService = connectorService;
    }

    @Override
    public String type() {
        return "NOTION";
    }

    @Override
    public List<AgentTool> tools(long agentId, ConnectorTypeDescriptor descriptor) {
        return NotionTools.create(agentId, connectorService);
    }

    @Override
    public ConnectorTestResult probe(Map<String, String> config) {
        NotionSettings settings = NotionSettings.of(config);
        if (settings.token().isBlank()) {
            return new ConnectorTestResult(false, "还没有填令牌", List.of());
        }
        NotionClient client = new NotionClient(settings.token());
        List<String> steps = new ArrayList<>();
        try {
            JsonNode me = client.me();
            String type = me.path("type").asText("bot");
            String name = me.path("name").asText("");
            String owner = "person".equals(me.path("owner").path("type").asText())
                    ? "个人访问令牌（以本人权限工作，无需共享页面）"
                    : "集成令牌（机器人身份，需要把页面「添加连接」给它）";
            steps.add("令牌有效：" + (name.isBlank() ? type : name) + " · " + owner);

            JsonNode search = client.search(null, null, 10, null);
            int count = search.path("results").size();
            steps.add("可访问的页面/数据库（前 10 条）中拿到 " + count + " 项");
            if (count == 0) {
                steps.add("提示：一条都搜不到通常是没授权 —— 集成令牌需要在页面「…」→「添加连接」里选中；"
                        + "个人访问令牌则要确认你有页面权限");
            }
            return new ConnectorTestResult(true, "连接正常", steps);
        } catch (NotionClient.MessagingException ex) {
            return new ConnectorTestResult(false, ex.getMessage(), steps);
        } catch (RuntimeException ex) {
            return new ConnectorTestResult(false, "测试失败：" + ex.getMessage(), steps);
        }
    }
}
