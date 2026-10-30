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
 *
 * <p>字段的**控件类型**也在这里声明（{@code TEXT / PASSWORD / NUMBER / SELECT}）：
 * 枚举项直接给下拉候选，用户不用手打 {@code true} / {@code OFF} 这种值；
 * 前端只按类型渲染，不写死任何字段。
 */
@Component
public class ConnectorTypeRegistry {

    private static final List<ConnectorTypeDescriptor.FieldOption> BOOLEAN_OPTIONS =
            List.of(option("true", "是"), option("false", "否"));
    private static final List<ConnectorTypeDescriptor.FieldOption> INBOUND_OPTIONS = List.of(
            option("OFF", "只记录（不打扰）"),
            option("NOTIFY", "推 IM 通知"),
            option("AUTOREPLY", "Agent 自动回复"));

    private static final List<ConnectorTypeDescriptor.FieldOption> WRITE_OPTIONS =
            List.of(option("true", "允许（新建/修改需要审批）"), option("false", "禁止（只能读）"));

    private final Map<String, ConnectorTypeDescriptor> byType = new LinkedHashMap<>();

    public ConnectorTypeRegistry() {
        register(new ConnectorTypeDescriptor(
                "MAIL",
                "邮件",
                "让 Agent 读取、搜索你的邮箱并代你发信（IMAP 收信 + SMTP 发信）",
                List.of(
                        text("email", "邮箱地址", true, "要连接的邮箱账号，例如 me@company.com", "me@company.com"),
                        text("displayName", "发件人显示名", false, "留空则用邮箱地址", ""),
                        text("imapHost", "IMAP 服务器", true, "收信服务器，例如 imap.qq.com", "imap.qq.com"),
                        number("imapPort", "IMAP 端口", true, "SSL 一般是 993", "993"),
                        select("imapSsl", "IMAP 使用 SSL", "993 端口选「是」；143 + STARTTLS 选「否」",
                                BOOLEAN_OPTIONS),
                        text("smtpHost", "SMTP 服务器", true, "发信服务器，例如 smtp.qq.com", "smtp.qq.com"),
                        number("smtpPort", "SMTP 端口", true, "SSL 一般 465，STARTTLS 一般 587", "465"),
                        select("smtpSsl", "SMTP 使用 SSL", "465 端口选「是」；587 选「否」（自动 STARTTLS）",
                                BOOLEAN_OPTIONS),
                        text("username", "登录账号", true, "通常就是邮箱地址（企业邮箱可能不同）", "me@company.com"),
                        new ConnectorTypeDescriptor.Field(
                                "password", "密码 / 授权码", true,
                                "QQ/163/Gmail 需要「授权码」而不是登录密码；修改时留空表示不改",
                                true, "",
                                ConnectorTypeDescriptor.FieldType.PASSWORD, List.of()),
                        select("allowSend", "允许发信", "选「否」后 Agent 只能读、不能发",
                                List.of(option("true", "允许"), option("false", "禁止（只能读）"))),
                        tags("allowedRecipients", "发信白名单（可选）",
                                "留空=不限制收件人；可填邮箱地址，或用 @域名.com 允许整个域"),
                        select("inboundMode", "新邮件处理", "收到新邮件时做什么（自动回复只在绑定 Agent 后生效）",
                                INBOUND_OPTIONS),
                        tags("allowedAutoReplySenders", "自动回复白名单（可选）",
                                "留空=对所有人自动回复；填了就只回这些发件人，可用 @域名.com 允许整个域")),
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

        register(new ConnectorTypeDescriptor(
                "NOTION",
                "Notion",
                "让 Agent 搜索、读取、新建与修改你的 Notion 页面（按你的权限访问，v2026-03-11 API）",
                List.of(
                        new ConnectorTypeDescriptor.Field(
                                "token", "集成令牌 / 个人访问令牌", true,
                                "Notion → Developer portal 创建个人访问令牌（勾选 Notion API 能力）或内部集成令牌；修改时留空表示不改",
                                true, "",
                                ConnectorTypeDescriptor.FieldType.PASSWORD, List.of()),
                        text("workspaceName", "工作区名称（备注）", false, "仅用于界面显示，可不填", ""),
                        text("defaultPageId", "默认写入页面（可选）", false,
                                "新建页面时的默认父页面；可粘贴完整链接或页面 id", ""),
                        number("maxBlocksPerPage", "单页最多读取多少块", false,
                                "默认 200；超大页面会截断，防止一次读爆上下文", "200"),
                        select("allowWrite", "允许写入", "关闭后 Agent 只能读，不能新建/修改页面",
                                WRITE_OPTIONS),
                        tags("allowedPageIds", "页面白名单（可选）",
                                "留空=不限；填了就只允许读写这些页面（可粘贴链接，会自动提取 id）"),
                        tags("allowedDataSources", "数据库白名单（可选）",
                                "留空=不限；填了就只允许查询/写入这些数据库（data source id）")),
                List.of("token"),
                Set.of("token"),
                Set.of(
                        ConnectorTypeDescriptor.Capability.READ,
                        ConnectorTypeDescriptor.Capability.SEARCH,
                        ConnectorTypeDescriptor.Capability.WRITE),
                false,
                true));

        register(new ConnectorTypeDescriptor(
                "DINGTALK_DOC",
                "钉钉文档",
                "让 Agent 搜索、读取、新建与修改你的钉钉文档/知识库（以你的钉钉身份操作）",
                List.of(
                        text("operatorId", "操作人（钉钉 userId）", false,
                                "以谁的钉钉身份操作，决定能看哪些文档；**留空=自动**用你在钉钉机器人里的身份", ""),
                        text("defaultWorkspaceId", "默认知识库/空间（可选）", false,
                                "新建文档时的默认落点；可粘贴知识库链接", ""),
                        tags("allowedWorkspaceIds", "知识库/空间白名单（可选）",
                                "留空=不限；填了就只允许操作这些知识库（可粘链接，自动提取 id）"),
                        tags("allowedDocKeys", "文档白名单（可选）",
                                "留空=不限；填了就只允许读写这些文档（可粘文档链接，自动提取 docKey）"),
                        number("maxBlocks", "单篇最多读取多少块", false,
                                "默认 300；超大文档会截断，防止一次读爆上下文", "300"),
                        select("allowWrite", "允许写入", "关闭后只能读；写入需要 Agent 的审批通过",
                                WRITE_OPTIONS)),
                List.of(),
                Set.of(),
                Set.of(
                        ConnectorTypeDescriptor.Capability.READ,
                        ConnectorTypeDescriptor.Capability.SEARCH,
                        ConnectorTypeDescriptor.Capability.WRITE),
                false,
                true));
    }

    private static ConnectorTypeDescriptor.FieldOption option(String value, String label) {
        return new ConnectorTypeDescriptor.FieldOption(value, label);
    }

    private static ConnectorTypeDescriptor.Field text(
            String key, String label, boolean required, String hint, String placeholder) {
        return new ConnectorTypeDescriptor.Field(key, label, false, hint, required, placeholder,
                ConnectorTypeDescriptor.FieldType.TEXT, List.of());
    }

    private static ConnectorTypeDescriptor.Field number(
            String key, String label, boolean required, String hint, String placeholder) {
        return new ConnectorTypeDescriptor.Field(key, label, false, hint, required, placeholder,
                ConnectorTypeDescriptor.FieldType.NUMBER, List.of());
    }

    private static ConnectorTypeDescriptor.Field tags(String key, String label, String hint) {
        return new ConnectorTypeDescriptor.Field(key, label, false, hint, false, "",
                ConnectorTypeDescriptor.FieldType.TAGS, List.of());
    }

    private static ConnectorTypeDescriptor.Field select(
            String key,
            String label,
            String hint,
            List<ConnectorTypeDescriptor.FieldOption> options) {
        return new ConnectorTypeDescriptor.Field(key, label, false, hint, false, "",
                ConnectorTypeDescriptor.FieldType.SELECT, options);
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
