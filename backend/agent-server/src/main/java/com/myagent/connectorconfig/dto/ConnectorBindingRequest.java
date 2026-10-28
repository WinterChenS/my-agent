package com.myagent.connectorconfig.dto;

import java.util.List;

/** 连接器 → Agent 的绑定（整体替换）。 */
public record ConnectorBindingRequest(List<Long> agentIds) {}
