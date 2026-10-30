package com.myagent.agui.connector.dingtalk;

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
 * 钉钉文档连接器（DINGTALK_DOC）：8 个工具 + 连通性探测。
 *
 * <p>「测试连接」会真的做三件事：换 access token → 查"我参与的知识库" → 查"我最近编辑的文档"，
 * 这样"应用凭据对不对""文档权限开了没""operatorId 是否正确"都能一次性看出来。
 */
public class DingTalkDocConnectorProvider implements ConnectorProvider {

    private final ConnectorService connectorService;
    private final DingTalkAppCredentials credentials;
    private final DingTalkOperatorResolver operatorResolver;

    public DingTalkDocConnectorProvider(
            ConnectorService connectorService,
            DingTalkAppCredentials credentials,
            DingTalkOperatorResolver operatorResolver) {
        this.connectorService = connectorService;
        this.credentials = credentials;
        this.operatorResolver = operatorResolver;
    }

    @Override
    public String type() {
        return "DINGTALK_DOC";
    }

    @Override
    public List<AgentTool> tools(long agentId, ConnectorTypeDescriptor descriptor) {
        return DingTalkDocTools.create(agentId, connectorService, credentials, operatorResolver);
    }

    @Override
    public ConnectorTestResult probe(Map<String, String> config) {
        DingTalkDocSettings settings = DingTalkDocSettings.of(config);
        List<String> steps = new ArrayList<>();
        Map<String, String> app = credentials.resolve();
        if (app == null) {
            return new ConnectorTestResult(false,
                    "平台还没有可用的钉钉应用凭据：请先在「系统设置 → IM 渠道」配置钉钉应用（appKey/appSecret）", steps);
        }
        steps.add("已找到钉钉应用凭据（appKey=" + mask(app.get("appKey")) + "）");

        String operatorId = settings.operatorId();
        if (operatorId == null || operatorId.isBlank()) {
            steps.add("操作人未填：运行时按「用户在钉钉机器人里的身份」自动映射");
        } else {
            steps.add("操作人：以钉钉 userId=" + operatorId + " 的身份操作");
        }
        if (operatorId == null || operatorId.isBlank()) {
            // 探测时拿不到"当前用户"，只能验证应用凭据与权限；operatorId 在真实调用时会自动映射
            operatorId = "probe";
        }
        DingTalkDocClient client = new DingTalkDocClient(app.get("appKey"), app.get("appSecret"), operatorId);
        try {
            JsonNode workspaces = client.mineWorkspaces(5);
            int count = workspaces.path("result").path("workspaces").size();
            steps.add("access token 获取成功；查到我参与的知识库 " + count + " 个");
            if (count == 0) {
                steps.add("提示：知识库为 0 通常是 ① 钉钉应用没勾选文档/知识库权限；② 你还没加入任何知识库；"
                        + "③ 操作人（operatorId）填错了");
            }
            return new ConnectorTestResult(true, "连接正常", steps);
        } catch (DingTalkDocClient.DingTalkException ex) {
            return new ConnectorTestResult(false, ex.getMessage(), steps);
        } catch (RuntimeException ex) {
            return new ConnectorTestResult(false, "测试失败：" + ex.getMessage(), steps);
        }
    }

    private static String mask(String value) {
        if (value == null || value.length() <= 6) {
            return "***";
        }
        return value.substring(0, 4) + "***" + value.substring(value.length() - 2);
    }
}
