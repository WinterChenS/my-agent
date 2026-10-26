package com.myagent.channelconfig.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * 渠道应用新建/更新请求。
 *
 * <p>{@code config} 是渠道凭据（钉钉：appKey / appSecret / robotCode / mode / aesKey）。
 * 更新时**不传的键保持原值**，因此前端回显只需要展示掩码，不必回传明文密钥。
 */
public record ChannelAppRequest(
        @NotBlank(message = "channelType 不能为空") String channelType,
        @NotBlank(message = "name 不能为空") String name,
        Boolean enabled,
        @NotNull(message = "agentId 不能为空") Long agentId,
        Long ownerUserId,
        Map<String, String> config) {}
