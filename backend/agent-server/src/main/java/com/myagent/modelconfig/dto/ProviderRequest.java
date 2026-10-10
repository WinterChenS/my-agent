package com.myagent.modelconfig.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 供应商新建/更新请求。
 *
 * <p>{@code apiKey} 为可选：更新时留空表示保留原密钥（只写不回读）。
 */
public record ProviderRequest(
        @NotBlank(message = "名称不能为空") String name,
        @NotBlank(message = "provider_type 不能为空") String providerType,
        String baseUrl,
        String apiKey,
        String status) {}
