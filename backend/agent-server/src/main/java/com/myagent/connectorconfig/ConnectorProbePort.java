package com.myagent.connectorconfig;

import com.myagent.connectorconfig.dto.ConnectorTestResult;
import java.util.Map;

/**
 * 「测试连接」的能力出口（实现位于 {@code com.myagent.agui.connector}）。
 *
 * <p>抽成接口的原因与 {@code ChannelPushPort} 相同：{@code connectorconfig} 包不允许 import
 * AgentScope（门禁 G2），而真正连邮箱要用 Jakarta Mail 并可能产出工具。
 */
public interface ConnectorProbePort {

    /** 支持的连接器类型（MAIL）。 */
    String type();

    /** 用给定配置做一次真实的连通性探测（不落库）。 */
    ConnectorTestResult probe(Map<String, String> config);
}
