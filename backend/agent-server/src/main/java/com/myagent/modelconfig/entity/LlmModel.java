package com.myagent.modelconfig.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import java.time.OffsetDateTime;
import java.util.Map;

/** llm_model 表实体；{@code params_json} 以 JSONB 存储。 */
@TableName(value = "llm_model", autoResultMap = true)
public class LlmModel {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long providerId;

    private String modelId;

    private String displayName;

    @TableField(value = "params_json", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> params;

    /** 采样温度 0–2（Phase 15 显式列）。 */
    private java.math.BigDecimal temperature;

    /** Top-P 核采样 0–1（Top-N）。 */
    private java.math.BigDecimal topP;

    /** 上下文窗口长度（token）。 */
    private Integer contextWindow;

    /** 单次输出长度上限（token）。 */
    private Integer maxTokens;

    /** 输入价格：每百万 token。 */
    private java.math.BigDecimal priceInput;

    /** 输出价格：每百万 token。 */
    private java.math.BigDecimal priceOutput;

    /** 价格货币：CNY / USD。 */
    private String priceCurrency;

    /** 是否支持图片输入（多模态）。 */
    private Boolean multimodal;

    private String status;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getProviderId() {
        return providerId;
    }

    public void setProviderId(Long providerId) {
        this.providerId = providerId;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public void setParams(Map<String, Object> params) {
        this.params = params;
    }


    public java.math.BigDecimal getTemperature() {
        return temperature;
    }

    public void setTemperature(java.math.BigDecimal temperature) {
        this.temperature = temperature;
    }

    public java.math.BigDecimal getTopP() {
        return topP;
    }

    public void setTopP(java.math.BigDecimal topP) {
        this.topP = topP;
    }

    public Integer getContextWindow() {
        return contextWindow;
    }

    public void setContextWindow(Integer contextWindow) {
        this.contextWindow = contextWindow;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public java.math.BigDecimal getPriceInput() {
        return priceInput;
    }

    public void setPriceInput(java.math.BigDecimal priceInput) {
        this.priceInput = priceInput;
    }

    public java.math.BigDecimal getPriceOutput() {
        return priceOutput;
    }

    public void setPriceOutput(java.math.BigDecimal priceOutput) {
        this.priceOutput = priceOutput;
    }

    public String getPriceCurrency() {
        return priceCurrency;
    }

    public void setPriceCurrency(String priceCurrency) {
        this.priceCurrency = priceCurrency;
    }

    public Boolean getMultimodal() {
        return multimodal;
    }

    public void setMultimodal(Boolean multimodal) {
        this.multimodal = multimodal;
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
}
