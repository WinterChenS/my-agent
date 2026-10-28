package com.myagent.connectorconfig.dto;

import java.util.List;
import java.util.Map;

/** 新建/修改连接器的请求。 */
public record ConnectorRequest(
        String type,
        String name,
        /** 工具/提示里用的短别名（小写字母数字下划线），可空 */
        String alias,
        Boolean enabled,
        /** 明文配置；更新时**留空或传*** 表示不修改已有密文 */
        Map<String, String> config,
        /** OFF / NOTIFY / AUTOREPLY */
        String inboundMode,
        /** 绑定的 Agent id（可空：更新时不传=不改绑定） */
        List<Long> agentIds) {

    /** 掩码占位符（与渠道配置一致）。 */
    public static final String MASK = "***";
}
