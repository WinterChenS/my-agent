package com.myagent.schedule.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/**
 * 定时任务定义。
 *
 * <p>为什么任务行只存 {@code agentId + prompt + 计划}：agent 由 {@code AgentFactory} 从库里建
 * （技能/MCP/记忆/工作区都在库中），跨节点能重建；把 AgentConfig 塞进调度器的 JobDataMap 反而做不到。
 */
@TableName("scheduled_task")
public class ScheduledTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private Boolean enabled;

    private Long agentId;

    private Long createdByUserId;

    /** CRON / EVERY_MINUTES / ONCE。 */
    private String scheduleType;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String cronExpr;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer intervalMinutes;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private OffsetDateTime onceAt;

    private String timezone;

    /** 定时执行时喂给 agent 的指令。 */
    private String prompt;

    /** SESSION / IM / NONE。 */
    private String targetType;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long chatSessionId;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long channelAppId;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String peerKey;

    /** ACTIVE / PAUSED / ERROR / DONE。 */
    private String status;

    /** COALESCE / SKIP。 */
    private String misfirePolicy;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private OffsetDateTime nextRunAt;

    private OffsetDateTime lastRunAt;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String lastError;

    private Integer runCount;

    private Integer consecutiveFailures;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private OffsetDateTime leaseUntil;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public Long getCreatedByUserId() {
        return createdByUserId;
    }

    public void setCreatedByUserId(Long createdByUserId) {
        this.createdByUserId = createdByUserId;
    }

    public String getScheduleType() {
        return scheduleType;
    }

    public void setScheduleType(String scheduleType) {
        this.scheduleType = scheduleType;
    }

    public String getCronExpr() {
        return cronExpr;
    }

    public void setCronExpr(String cronExpr) {
        this.cronExpr = cronExpr;
    }

    public Integer getIntervalMinutes() {
        return intervalMinutes;
    }

    public void setIntervalMinutes(Integer intervalMinutes) {
        this.intervalMinutes = intervalMinutes;
    }

    public OffsetDateTime getOnceAt() {
        return onceAt;
    }

    public void setOnceAt(OffsetDateTime onceAt) {
        this.onceAt = onceAt;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public Long getChatSessionId() {
        return chatSessionId;
    }

    public void setChatSessionId(Long chatSessionId) {
        this.chatSessionId = chatSessionId;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMisfirePolicy() {
        return misfirePolicy;
    }

    public void setMisfirePolicy(String misfirePolicy) {
        this.misfirePolicy = misfirePolicy;
    }

    public OffsetDateTime getNextRunAt() {
        return nextRunAt;
    }

    public void setNextRunAt(OffsetDateTime nextRunAt) {
        this.nextRunAt = nextRunAt;
    }

    public OffsetDateTime getLastRunAt() {
        return lastRunAt;
    }

    public void setLastRunAt(OffsetDateTime lastRunAt) {
        this.lastRunAt = lastRunAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Integer getRunCount() {
        return runCount;
    }

    public void setRunCount(Integer runCount) {
        this.runCount = runCount;
    }

    public Integer getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public void setConsecutiveFailures(Integer consecutiveFailures) {
        this.consecutiveFailures = consecutiveFailures;
    }

    public OffsetDateTime getLeaseUntil() {
        return leaseUntil;
    }

    public void setLeaseUntil(OffsetDateTime leaseUntil) {
        this.leaseUntil = leaseUntil;
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
