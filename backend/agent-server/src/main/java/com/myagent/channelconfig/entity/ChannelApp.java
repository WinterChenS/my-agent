package com.myagent.channelconfig.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/**
 * 渠道应用：一个 IM 机器人 = 一个平台 Agent + 一套凭据。
 *
 * <p>凭据（appKey/appSecret/robotCode…）以 AES-GCM 加密后存在 {@code config_enc}，
 * 与模型 API Key 同一套 {@link com.myagent.common.CryptoService}。
 */
@TableName("channel_app")
public class ChannelApp {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** DINGTALK / FEISHU。 */
    private String channelType;

    private String name;

    private Boolean enabled;

    /** 绑定到哪个平台 Agent。 */
    private Long agentId;

    /** 渠道会话属主（工作区/记忆/产物按属主隔离）。 */
    private Long ownerUserId;

    /** 加密后的凭据 JSON。 */
    private String configEnc;

    /** STOPPED / RUNNING / ERROR。 */
    private String status;

    private String lastError;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getChannelType() {
        return channelType;
    }

    public void setChannelType(String channelType) {
        this.channelType = channelType;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Long getAgentId() {
        return agentId;
    }

    public void setAgentId(Long agentId) {
        this.agentId = agentId;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(Long ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public String getConfigEnc() {
        return configEnc;
    }

    public void setConfigEnc(String configEnc) {
        this.configEnc = configEnc;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
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
