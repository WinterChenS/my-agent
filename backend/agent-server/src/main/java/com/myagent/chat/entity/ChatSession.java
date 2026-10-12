package com.myagent.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/** chat_session 表实体。 */
@TableName("chat_session")
public class ChatSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long agentId;

    private String title;

    /** Phase 17：标题是否可被自动生成覆盖（false = 人工指定）。 */
    private Boolean titleAuto;

    /** AG-UI 的 threadId，与 user_id 组合唯一。 */
    private String threadId;

    private OffsetDateTime lastActiveAt;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getAgentId() {
        return agentId;
    }

    public void setAgentId(Long agentId) {
        this.agentId = agentId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Boolean getTitleAuto() {
        return titleAuto;
    }

    public void setTitleAuto(Boolean titleAuto) {
        this.titleAuto = titleAuto;
    }

    public String getThreadId() {
        return threadId;
    }

    public void setThreadId(String threadId) {
        this.threadId = threadId;
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
