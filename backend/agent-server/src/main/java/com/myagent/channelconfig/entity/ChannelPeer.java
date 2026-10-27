package com.myagent.channelconfig.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/**
 * IM 会话 ↔ 平台 chat_session 的映射，同时保存主动推送要用的 OutboundAddress。
 *
 * <p>peer_key 语义由渠道决定：钉钉单聊是 {@code senderStaffId}，群聊是 {@code conversationId}。
 */
@TableName("channel_peer")
public class ChannelPeer {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long channelAppId;

    private String peerKey;

    /** DIRECT / GROUP。 */
    private String peerKind;

    /** 昵称/群名，仅用于展示。 */
    private String peerName;

    /** OutboundAddress 的 JSON 快照（主动推送用）。 */
    private String outboundAddress;

    private Long chatSessionId;

    private OffsetDateTime lastActiveAt;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getChannelAppId() {
        return channelAppId;
    }

    public void setChannelAppId(Long channelAppId) {
        this.channelAppId = channelAppId;
    }

    public String getPeerKey() {
        return peerKey;
    }

    public void setPeerKey(String peerKey) {
        this.peerKey = peerKey;
    }

    public String getPeerKind() {
        return peerKind;
    }

    public void setPeerKind(String peerKind) {
        this.peerKind = peerKind;
    }

    public String getPeerName() {
        return peerName;
    }

    public void setPeerName(String peerName) {
        this.peerName = peerName;
    }

    public String getOutboundAddress() {
        return outboundAddress;
    }

    public void setOutboundAddress(String outboundAddress) {
        this.outboundAddress = outboundAddress;
    }

    public Long getChatSessionId() {
        return chatSessionId;
    }

    public void setChatSessionId(Long chatSessionId) {
        this.chatSessionId = chatSessionId;
    }

    public OffsetDateTime getLastActiveAt() {
        return lastActiveAt;
    }

    public void setLastActiveAt(OffsetDateTime lastActiveAt) {
        this.lastActiveAt = lastActiveAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
