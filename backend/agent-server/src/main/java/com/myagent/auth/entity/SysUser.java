package com.myagent.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/** sys_user 表实体。 */
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String passwordHash;

    private String displayName;

    private String role;

    private String status;

    private OffsetDateTime createdAt;

    private java.math.BigDecimal dailyQuota;
    private java.math.BigDecimal monthlyQuota;
    private String quotaCurrency;
    private String quotaMode;

    private OffsetDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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

    public java.math.BigDecimal getDailyQuota() {
        return dailyQuota;
    }

    public void setDailyQuota(java.math.BigDecimal dailyQuota) {
        this.dailyQuota = dailyQuota;
    }

    public java.math.BigDecimal getMonthlyQuota() {
        return monthlyQuota;
    }

    public void setMonthlyQuota(java.math.BigDecimal monthlyQuota) {
        this.monthlyQuota = monthlyQuota;
    }

    public String getQuotaCurrency() {
        return quotaCurrency;
    }

    public void setQuotaCurrency(String quotaCurrency) {
        this.quotaCurrency = quotaCurrency;
    }

    public String getQuotaMode() {
        return quotaMode;
    }

    public void setQuotaMode(String quotaMode) {
        this.quotaMode = quotaMode;
    }
}
