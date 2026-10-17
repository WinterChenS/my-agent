package com.myagent.modelconfig.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/** 模型新建/更新请求。 */
public record ModelRequest(
        @NotNull(message = "providerId 不能为空") Long providerId,
        @NotBlank(message = "model_id 不能为空") String modelId,
        String displayName,
        Map<String, Object> params,
        java.math.BigDecimal temperature,
        java.math.BigDecimal topP,
        Integer contextWindow,
        Integer maxTokens,
        java.math.BigDecimal priceInput,
        java.math.BigDecimal priceOutput,
        String priceCurrency,
        Boolean multimodal,
        String status,
        // 缓存命中价放在**末尾**：这样既有调用方（含测试，按位置传参）只需在最后补一个 null
        java.math.BigDecimal priceCache) {}
