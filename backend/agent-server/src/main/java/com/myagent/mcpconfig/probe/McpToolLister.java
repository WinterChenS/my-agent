package com.myagent.mcpconfig.probe;

import java.util.List;

/**
 * MCP 工具发现契约。
 *
 * <p>接口放在 {@code mcpconfig} 包、实现放 {@code agui} 包（门禁 G2：配置类包不得引用 AgentScope）。
 * 实现必须使用官方 {@code McpClientBuilder} 连接，禁止自研 JSON-RPC（门禁 G8）。
 */
public interface McpToolLister {

    /**
     * 连接并列出该 Server 的工具。
     *
     * @throws com.myagent.common.BizException 连接失败或协议错误（由实现转换为业务错误）
     */
    List<McpToolInfo> listTools(McpServerSpec spec);
}
