package com.myagent.channelconfig.dto;

import java.time.OffsetDateTime;

/** 渠道会话回显（管理页展示「这个机器人和谁聊过」）。 */
public record ChannelPeerResponse(
        Long id,
        String peerKey,
        String peerKind,
        String peerName,
        Long chatSessionId,
        OffsetDateTime lastActiveAt) {}
