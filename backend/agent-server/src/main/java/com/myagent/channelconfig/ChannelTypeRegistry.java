package com.myagent.channelconfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 已支持的渠道类型清单。
 *
 * <p>加一家 IM：**只在这里加一条** {@link ChannelTypeDescriptor}，
 * 再在 {@code agui.channel} 里加一个媒体客户端 provider 和一个渠道构建器即可。
 * 校验、掩码、回调地址、前端表单都从本清单读，不再各写一遍 if。
 */
@Component
public class ChannelTypeRegistry {

    private final Map<String, ChannelTypeDescriptor> byType = new LinkedHashMap<>();

    public ChannelTypeRegistry() {
        // 钉钉：企业内部应用机器人，Stream 模式不需要公网；HTTP 回调模式需要 aesKey
        register(new ChannelTypeDescriptor(
                "DINGTALK",
                "钉钉",
                List.of("appKey", "appSecret", "robotCode"),
                Set.of("appSecret", "encryptKey", "aesKey", "verificationToken"),
                "/api/channels/dingtalk/{id}/callback",
                List.of(
                        new ChannelTypeDescriptor.Field(
                                "appKey", "AppKey", false, "钉钉开放平台 → 应用 → 凭证与基础信息"),
                        new ChannelTypeDescriptor.Field(
                                "appSecret", "AppSecret", true, "同上；修改时留空表示不改"),
                        new ChannelTypeDescriptor.Field(
                                "robotCode", "RobotCode", false, "机器人编码，通常与 AppKey 相同"),
                        new ChannelTypeDescriptor.Field(
                                "mode", "接入方式", false, "stream=长连接（推荐，无需公网）；http=回调"),
                        new ChannelTypeDescriptor.Field(
                                "aesKey", "AES Key", true, "仅 http 回调模式需要：钉钉后台「加解密」aes_key"))));
        // 飞书：表结构、配置位、校验规则都已就位；接入时只需补 agui.channel 里的实现
        register(new ChannelTypeDescriptor(
                "FEISHU",
                "飞书",
                List.of("appId", "appSecret"),
                Set.of("appSecret", "encryptKey", "verificationToken"),
                "/api/channels/feishu/{id}/callback",
                List.of(
                        new ChannelTypeDescriptor.Field(
                                "appId", "AppID", false, "飞书开放平台 → 应用详情"),
                        new ChannelTypeDescriptor.Field(
                                "appSecret", "AppSecret", true, "同上"),
                        new ChannelTypeDescriptor.Field(
                                "encryptKey", "Encrypt Key", true, "事件订阅 → 加密策略"),
                        new ChannelTypeDescriptor.Field(
                                "verificationToken", "Verification Token", true,
                                "事件订阅 → 加密策略"))));
    }

    private void register(ChannelTypeDescriptor descriptor) {
        byType.put(descriptor.typeUpper(), descriptor);
    }

    /** 全部已支持类型（大写）。 */
    public Set<String> supportedTypes() {
        return byType.keySet();
    }

    public List<ChannelTypeDescriptor> all() {
        return List.copyOf(byType.values());
    }

    /** 找不到返回 null（调用方据此报"不支持的渠道类型"）。 */
    public ChannelTypeDescriptor find(String channelType) {
        return channelType == null
                ? null
                : byType.get(channelType.trim().toUpperCase(Locale.ROOT));
    }

    public boolean supports(String channelType) {
        return find(channelType) != null;
    }

    /** 敏感键并集（回显掩码用；遇到未知类型也能兜底）。 */
    public Set<String> secretKeys(String channelType) {
        ChannelTypeDescriptor descriptor = find(channelType);
        return descriptor == null ? Set.of() : descriptor.secretKeys();
    }
}
