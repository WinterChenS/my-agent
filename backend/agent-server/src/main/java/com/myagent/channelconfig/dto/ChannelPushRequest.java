package com.myagent.channelconfig.dto;

import java.util.List;

/**
 * 主动推送请求。
 *
 * @param peerKey IM 侧会话标识（channel_peer.peer_key）
 * @param text 正文（只发正文，不发思考/工具调用）
 * @param artifactIds 要随消息发出的产物 id（图片发图片消息，其它发文件消息）
 */
public record ChannelPushRequest(String peerKey, String text, List<Long> artifactIds) {}
