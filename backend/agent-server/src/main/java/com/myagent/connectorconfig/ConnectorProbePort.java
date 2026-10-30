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

    /**
     * 带调用者身份的探测。
     *
     * <p>为什么需要：钉钉文档这类连接器的"身份"是 operatorId（以谁的名义），
     * 而它是按**平台用户**自动映射出来的 —— 探测时拿不到用户就只能用占位身份，
     * 用户点「测试连接」会看到"没权限"，白折腾一场。
     */
    default ConnectorTestResult probe(Map<String, String> config, Long userId) {
        return probe(config);
    }
}
