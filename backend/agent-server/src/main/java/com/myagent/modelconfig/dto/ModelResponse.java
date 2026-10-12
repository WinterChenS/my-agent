package com.myagent.modelconfig.dto;

import java.time.OffsetDateTime;
import java.util.Map;

/** 模型响应。 */
public record ModelResponse(
        Long id,
        Long providerId,
        String modelId,
        String displayName,
        Map<String, Object> params,
        java.math.BigDecimal temperature,
        java.math.BigDecimal topP,
        Integer contextWindow,
        Integer maxTokens,
        java.math.BigDecimal priceInput,
        java.math.BigDecimal priceOutput,
        String priceCurrency,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
