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
        String status) {}
