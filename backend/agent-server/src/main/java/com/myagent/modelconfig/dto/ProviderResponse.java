package com.myagent.modelconfig.dto;

import java.time.OffsetDateTime;

/** 供应商响应：{@code apiKey} 恒为脱敏值（{@code ****}）或 null，绝不返回明文。 */
public record ProviderResponse(
        Long id,
        String name,
        String providerType,
        String baseUrl,
        String apiKey,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
