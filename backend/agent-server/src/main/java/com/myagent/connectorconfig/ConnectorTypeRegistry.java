package com.myagent.connectorconfig;

import com.myagent.common.BizException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 已支持的连接器类型清单。
 *
 * <p>加一种连接器：**只在这里加一条** {@link ConnectorTypeDescriptor}，再到
 * {@code agui.connector} 里加一个 {@code ConnectorProvider} 实现即可。
 */
@Component
public class ConnectorTypeRegistry {

    private final Map<String, ConnectorTypeDescriptor> byType = new LinkedHashMap<>();

    public ConnectorTypeRegistry() {
        register(new ConnectorTypeDescriptor(
                "MAIL",
                "邮件",
                "让 Agent 读取、搜索你的邮箱并代你发信（IMAP 收信 + SMTP 发信）",
                List.of(
                        field("email", "邮箱地址", false, "要连接的邮箱账号，例如 me@company.com", true,
                                "me@company.com"),
                        field("displayName", "发件人显示名", false, "留空则用邮箱地址", false, ""),
                        field("imapHost", "IMAP 服务器", false, "收信服务器，例如 imap.qq.com", true,
                                "imap.qq.com"),
                        field("imapPort", "IMAP 端口", false, "SSL 一般是 993", true, "993"),
                        field("imapSsl", "IMAP 使用 SSL", false, "993 端口选 true；143 + STARTTLS 选 false", false,
                                "true"),
                        field("smtpHost", "SMTP 服务器", false, "发信服务器，例如 smtp.qq.com", true,
                                "smtp.qq.com"),
                        field("smtpPort", "SMTP 端口", false, "SSL 一般 465，STARTTLS 一般 587", true, "465"),
                        field("smtpSsl", "SMTP 使用 SSL", false, "465 端口选 true；587 选 false（自动 STARTTLS）",
                                false, "true"),
                        field("username", "登录账号", false, "通常就是邮箱地址（企业邮箱可能不同）", true,
                                "me@company.com"),
                        field("password", "密码 / 授权码", true,
                                "QQ/163/Gmail 需要「授权码」而不是登录密码；修改时留空表示不改", true, ""),
                        field("allowSend", "允许发信", false, "关掉后 Agent 只能读、不能发", false, "true"),
                        field("allowedRecipients", "发信白名单", false,
                                "留空=不限制；填多个用英文逗号分隔（支持 @域名.com 只允许整个域）", false, ""),
                        field("inboundMode", "新邮件处理", false,
                                "OFF=只记录；NOTIFY=推送 IM 通知；AUTOREPLY=交给 Agent 自动回复", false, "OFF")),
                List.of("email", "imapHost", "imapPort", "smtpHost", "smtpPort", "username", "password"),
                Set.of("password"),
                Set.of(
                        ConnectorTypeDescriptor.Capability.READ,
                        ConnectorTypeDescriptor.Capability.SEARCH,
                        ConnectorTypeDescriptor.Capability.ATTACHMENT,
                        ConnectorTypeDescriptor.Capability.SEND,
                        ConnectorTypeDescriptor.Capability.INBOUND),
                true,
                true));
    }

    private static ConnectorTypeDescriptor.Field field(
            String key,
            String label,
            boolean secret,
            String hint,
            boolean required,
            String placeholder) {
        return new ConnectorTypeDescriptor.Field(key, label, secret, hint, required, placeholder);
    }

    private void register(ConnectorTypeDescriptor descriptor) {
        byType.put(descriptor.typeUpper(), descriptor);
    }

    public List<ConnectorTypeDescriptor> all() {
        return List.copyOf(byType.values());
    }

    /** 按 type 取描述；不支持的 type 抛业务错误（400）。 */
    public ConnectorTypeDescriptor require(String type) {
        if (type == null || type.isBlank()) {
            throw BizException.badRequest("连接器类型不能为空");
        }
        ConnectorTypeDescriptor descriptor = byType.get(type.trim().toUpperCase(Locale.ROOT));
        if (descriptor == null) {
            throw BizException.badRequest("不支持的连接器类型: " + type
                    + "（当前支持：" + String.join("/", byType.keySet()) + "）");
        }
        return descriptor;
    }

    public Set<String> secretKeys(String type) {
        ConnectorTypeDescriptor descriptor = byType.get(type == null ? "" : type.trim().toUpperCase(Locale.ROOT));
        return descriptor == null ? Set.of() : descriptor.secretKeys();
    }
}
