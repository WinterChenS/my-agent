package com.myagent.agui.channel;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.myagent.channelconfig.entity.ChannelApp;
import com.myagent.channelconfig.entity.ChannelPeer;
import com.myagent.channelconfig.mapper.ChannelPeerMapper;
import com.myagent.chat.entity.ChatSession;
import com.myagent.chat.mapper.ChatSessionMapper;
import com.myagent.chat.service.ChatSessionService;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * IM 会话 ↔ 平台会话的桥。
 *
 * <p>三件事：
 *
 * <ol>
 *   <li>按 (渠道应用, peer) 找/建 {@code channel_peer}，并把 IM 的 peer 锚到一个
 *       {@code chat_session} —— 这样 Web 端能看到同一段历史（会话来源标 DINGTALK）；</li>
 *   <li>记住 {@link OutboundAddress}，主动推送时用它定位「发回哪个会话」；</li>
 *   <li>把用户/助手消息写进 {@code chat_message}，与 Web 链路同一套表。</li>
 * </ol>
 */
@Component
public class ChannelSessionBridge {

    private static final Logger log = LoggerFactory.getLogger(ChannelSessionBridge.class);

    private final ChannelPeerMapper peerMapper;
    private final ChatSessionMapper sessionMapper;
    private final ChatSessionService sessionService;

    public ChannelSessionBridge(
            ChannelPeerMapper peerMapper,
            ChatSessionMapper sessionMapper,
            ChatSessionService sessionService) {
        this.peerMapper = peerMapper;
        this.sessionMapper = sessionMapper;
        this.sessionService = sessionService;
    }

    /**
     * 找到（或新建）该 peer 对应的平台会话，并刷新推送地址。
     *
     * @param title 会话标题（首条消息时用「钉钉 · 张三」这种，便于 Web 列表识别）
     */
    public ChatSession ensureSession(
            ChannelApp app, InboundMessage inbound, OutboundAddress address, String title) {
        String peerKey = peerKey(inbound);
        String peerKind = inbound.peer() == null || inbound.peer().kind() == null
                ? "DIRECT"
                : inbound.peer().kind().name();
        ChannelPeer peer = findPeer(app.getId(), peerKey);
        if (peer == null) {
            peer = new ChannelPeer();
            peer.setChannelAppId(app.getId());
            peer.setPeerKey(peerKey);
            peer.setPeerKind(peerKind);
            peer.setPeerName(title);
            peerMapper.insert(peer);
        }
        ChatSession session = peer.getChatSessionId() == null ? null : sessionMapper.selectById(peer.getChatSessionId());
        if (session == null) {
            // threadId 用 UUID：它同时是 AgentScope 会话状态（PgAgentStateStore）的 key
            session = sessionService.createForThread(app.getOwnerUserId(), UUID.randomUUID().toString(), app.getAgentId());
            session.setOrigin(app.getChannelType());
            session.setOriginRef(app.getName() + " · " + peerKey);
            session.setTitle(title);
            sessionMapper.updateById(session);
        }
        peer.setChatSessionId(session.getId());
        peer.setOutboundAddress(writeAddress(address));
        peer.setLastActiveAt(OffsetDateTime.now());
        peer.setUpdatedAt(OffsetDateTime.now());
        peerMapper.updateById(peer);
        return session;
    }

    public ChannelPeer findPeer(Long appId, String peerKey) {
        if (peerKey == null || peerKey.isBlank()) {
            return null;
        }
        return peerMapper.selectOne(Wrappers.<ChannelPeer>lambdaQuery()
                .eq(ChannelPeer::getChannelAppId, appId)
                .eq(ChannelPeer::getPeerKey, peerKey));
    }

    /** 反查：这个平台会话是不是 IM 会话，属于哪个渠道/peer（send_im_message 用）。 */
    public ChannelPeer peerOfSession(Long chatSessionId) {
        if (chatSessionId == null) {
            return null;
        }
        return peerMapper.selectOne(Wrappers.<ChannelPeer>lambdaQuery()
                .eq(ChannelPeer::getChatSessionId, chatSessionId)
                .last("limit 1"));
    }

    /** 主动推送：取 peer 记住的地址。 */
    public OutboundAddress addressOf(ChannelApp app, String peerKey) {
        ChannelPeer peer = findPeer(app.getId(), peerKey);
        if (peer == null || peer.getOutboundAddress() == null) {
            return null;
        }
        return readAddress(peer.getOutboundAddress());
    }

    public void appendUserMessage(ChatSession session, String text, Map<String, Object> extra) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (extra != null) {
            meta.putAll(extra);
        }
        sessionService.appendMessage(session.getId(), "USER", text, meta);
        sessionService.touch(session.getId());
    }

    public void appendAssistantMessage(ChatSession session, String text, Map<String, Object> extra) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (extra != null) {
            meta.putAll(extra);
        }
        sessionService.appendMessage(session.getId(), "ASSISTANT", text, meta);
        sessionService.touch(session.getId());
    }

    private String writeAddress(OutboundAddress address) {
        if (address == null) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(address.toMap());
        } catch (Exception ex) {
            log.warn("序列化 OutboundAddress 失败: {}", ex.toString());
            return null;
        }
    }

    private OutboundAddress readAddress(String json) {
        try {
            Map<String, Object> map = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            return OutboundAddress.fromMap(map);
        } catch (Exception ex) {
            log.warn("解析 OutboundAddress 失败: {}", ex.toString());
            return null;
        }
    }

    /** peer 的稳定标识：钉钉单聊是 staffId，群聊是 conversationId。 */
    static String peerKey(InboundMessage inbound) {
        if (inbound == null || inbound.peer() == null) {
            return "unknown";
        }
        String id = inbound.peer().id();
        return id == null || id.isBlank() ? "unknown" : id;
    }
}
