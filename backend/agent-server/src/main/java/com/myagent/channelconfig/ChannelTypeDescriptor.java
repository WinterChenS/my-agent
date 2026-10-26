package com.myagent.channelconfig;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 一种 IM 渠道的「元数据」：显示名、必填配置键、敏感键、回调路径模板。
 *
 * <p>为什么收敛到一处：原来这套信息散在三处——Java 的 {@code SUPPORTED_TYPES}、
 * SQL 的 {@code CHECK} 约束、{@code ChannelAppService} 里的 if 判断。
 * 加一家 IM 要改三处，很容易漏改导致「配置能存但启动失败」。
 *
 * <p>本类位于 {@code channelconfig} 包，**不依赖 AgentScope**（门禁 G2）：
 * 真正的渠道实例构造在 {@code com.myagent.agui.channel} 里按 type 查构建器。
 *
 * @param type 与 channel_app.channel_type 一致（大写）
 * @param displayName 界面显示名
 * @param requiredKeys 必填配置键（保存时校验）
 * @param secretKeys 回显时掩码的键
 * @param callbackPathTemplate HTTP 回调模式的路径模板（{id} 会被替换成渠道应用 id）；
 *        Stream 模式或无回调的渠道用 null
 * @param fields 需要用户填写的配置项（顺序即表单顺序）
 */
public record ChannelTypeDescriptor(
        String type,
        String displayName,
        List<String> requiredKeys,
        Set<String> secretKeys,
        String callbackPathTemplate,
        List<Field> fields) {

    /**
     * 一个配置项。
     *
     * @param key 配置键
     * @param label 界面标签
     * @param secret 是否密文（回显掩码、表单用 password 输入）
     * @param hint 填法提示
     */
    public record Field(String key, String label, boolean secret, String hint) {}

    public String typeUpper() {
        return type.toUpperCase(Locale.ROOT);
    }

    /** 回调路径（替换 {id}）；没有回调则为 null。 */
    public String callbackPath(Long channelAppId) {
        if (callbackPathTemplate == null) {
            return null;
        }
        return callbackPathTemplate.replace("{id}", String.valueOf(channelAppId));
    }

    public boolean isSecret(String key) {
        return secretKeys.contains(key);
    }
}
