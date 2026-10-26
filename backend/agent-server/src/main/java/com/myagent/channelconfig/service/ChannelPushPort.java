package com.myagent.channelconfig.service;

import java.util.List;

/**
 * 主动推送到 IM 的能力出口（实现位于 {@code com.myagent.agui.channel}）。
 *
 * <p>之所以抽成接口：{@code channelconfig} 包不允许 import AgentScope（门禁 G2），
 * 而真正发消息要用官方渠道实例。
 */
public interface ChannelPushPort {

    /**
     * 向某个 IM 会话主动推送「正文 + 产物文件」。
     *
     * @return 实际发出的消息条数
     */
    int push(Long channelAppId, String peerKey, String text, List<Long> artifactIds);
}
