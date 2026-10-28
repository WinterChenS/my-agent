package com.myagent.connectorconfig;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 一种**连接器类型**的元数据：显示名、字段（动态表单）、必填项、敏感键、能力集。
 *
 * <p>与 {@code ChannelTypeDescriptor} 同一套路：新增一种连接器（日历/云盘/工单…）**只改这里一处**，
 * 表单渲染、保存校验、回显掩码、能力判断全部从本清单读，不再各写一遍 if。
 *
 * <p>本类位于 {@code connectorconfig} 包，**不依赖 AgentScope**（门禁 G2）：
 * 真正的工具实现按 type 在 {@code com.myagent.agui.connector} 里查找。
 */
public record ConnectorTypeDescriptor(
        String type,
        String displayName,
        String description,
        List<Field> fields,
        List<String> requiredKeys,
        Set<String> secretKeys,
        Set<Capability> capabilities,
        boolean inboundSupported,
        boolean available) {

    /** 连接器能力：决定界面上显示哪些能力标签、以及工具是否注册。 */
    public enum Capability {
        /** 读取内容（列/读/附件）。 */
        READ,
        /** 条件搜索。 */
        SEARCH,
        /** 下载附件到工作区。 */
        ATTACHMENT,
        /** 代用户发信。 */
        SEND,
        /** 新内容入站触发（P1）。 */
        INBOUND
    }

    /** 表单控件类型：由类型描述决定，前端不再猜（枚举写成输入框让用户手打 true/false 是不可接受的）。 */
    public enum FieldType {
        /** 单行文本。 */
        TEXT,
        /** 密文（回显掩码、password 输入）。 */
        PASSWORD,
        /** 数字（端口等）：前端用 number 输入，后端校验必须是数字。 */
        NUMBER,
        /** 枚举：前端下拉，后端校验取值必须在 {@link Field#options()} 里。 */
        SELECT
    }

    /** 枚举项：{@code value} 入库、{@code label} 给人看。 */
    public record FieldOption(String value, String label) {}

    /**
     * 一个配置项。
     *
     * @param key 配置键（{@code config_enc} 解密后的 JSON 键；{@code inboundMode} 特殊：落 {@code connector.inbound_mode} 列）
     * @param label 界面标签
     * @param secret 是否密文（回显掩码、表单用 password 输入）
     * @param hint 填法提示
     * @param required 是否必填（与 {@link #requiredKeys} 保持一致，表单用）
     * @param placeholder 输入框占位符（SELECT 不用）
     * @param type 控件类型
     * @param options 仅 {@link FieldType#SELECT}：可选项（value + 中文标签）
     */
    public record Field(
            String key,
            String label,
            boolean secret,
            String hint,
            boolean required,
            String placeholder,
            FieldType type,
            List<FieldOption> options) {

        public boolean isSelect() {
            return type == FieldType.SELECT;
        }

        /** 该值是否被这个下拉项接受（非 SELECT 永远通过）。 */
        public boolean accepts(String value) {
            if (!isSelect()) {
                return true;
            }
            return options.stream().anyMatch(option -> option.value().equalsIgnoreCase(
                    value == null ? "" : value.trim()));
        }
    }

    public String typeUpper() {
        return type.toUpperCase(Locale.ROOT);
    }

    public boolean isSecret(String key) {
        return secretKeys.contains(key);
    }

    public boolean supports(Capability capability) {
        return capabilities.contains(capability);
    }

    /** 缺哪些必填项（返回键名，空表示齐全）。 */
    public List<String> missingKeys(java.util.Map<String, String> config) {
        return requiredKeys.stream()
                .filter(key -> config == null
                        || config.get(key) == null
                        || config.get(key).isBlank())
                .toList();
    }
}
