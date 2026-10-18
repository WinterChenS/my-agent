package com.myagent.agentconfig.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/**
 * Agent 的可用用户白名单（按资源授权）。
 *
 * <p>语义：**没有记录 = 对所有人开放**；配了记录就只有这些用户（+ ADMIN）能用。
 */
@TableName("agent_user")
public class AgentUser {

    private Long agentId;

    private Long userId;

    private OffsetDateTime createdAt;

    public Long getAgentId() {
        return agentId;
    }

    public void setAgentId(Long agentId) {
        this.agentId = agentId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
