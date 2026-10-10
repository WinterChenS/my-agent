package com.myagent.chat.dto;

import java.time.OffsetDateTime;
import java.util.Map;

/** 历史消息响应；{@code aguiMeta} 保留 AG-UI 侧的附加信息。 */
public record MessageResponse(
        Long id,
        String role,
        String content,
        Map<String, Object> aguiMeta,
        Integer seq,
        OffsetDateTime createdAt) {}
