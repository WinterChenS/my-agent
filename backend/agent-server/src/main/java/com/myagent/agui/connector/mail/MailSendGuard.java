package com.myagent.agui.connector.mail;

import com.myagent.connectorconfig.ConnectorRecord;
import com.myagent.connectorconfig.ConnectorService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 发信前的护栏：开关、白名单、禁止发给自己、每小时上限、审计。
 *
 * <p>为什么单独成类：这些规则**必须能单测**（发信是不可撤销的副作用），
 * 混在工具里就只能靠真邮箱验证。模型看到的拒绝理由要是可操作的（"该收件人不在白名单里"）。
 */
class MailSendGuard {

    private final MailSettings settings;
    private final ConnectorService connectorService;
    private final ConnectorRecord record;
    private final long userId;
    private final MailTools.PathResolver paths;

    MailSendGuard(
            MailSettings settings,
            ConnectorService connectorService,
            ConnectorRecord record,
            long userId,
            MailTools.PathResolver paths) {
        this.settings = settings;
        this.connectorService = connectorService;
        this.record = record;
        this.userId = userId;
        this.paths = paths;
    }

    /**
     * 发信前检查；返回拒绝理由（null 表示通过）。
     *
     * <p>顺序很重要：先看开关（用户明确关掉了发信），再看收件人，最后看频率上限。
     */
    String check(List<String> to, List<String> cc, List<String> bcc) {
        if (!settings.allowSend()) {
            return "这个邮箱连接器关闭了「允许发信」，只能读不能发。"
                    + "请让用户到「能力 → 连接器」里把「允许发信」打开后再试。";
        }
        List<String> recipients = new ArrayList<>();
        recipients.addAll(to);
        if (cc != null) {
            recipients.addAll(cc);
        }
        if (bcc != null) {
            recipients.addAll(bcc);
        }
        if (recipients.isEmpty()) {
            return "没有收件人，无法发送。";
        }
        String self = settings.email() == null ? "" : settings.email().toLowerCase(Locale.ROOT);
        for (String recipient : recipients) {
            String address = normalize(recipient);
            if (!address.contains("@")) {
                return "收件人「" + recipient + "」不是有效邮箱地址。";
            }
            // 防自环：发给自己的连接器邮箱会导致"自己回自己"的无限循环
            if (address.equals(self)) {
                return "不能发给自己（" + address + "），这会造成自动回复的死循环。";
            }
            if (!settings.allowsRecipient(address)) {
                return "收件人「" + address + "」不在这个邮箱的发信白名单里。"
                        + "请让用户确认，或到「能力 → 连接器」里把这个地址（或整个域，如 @company.com）加进白名单。";
            }
        }
        // 每小时上限：直接拒绝，避免模型在循环里把用户邮箱发爆
        connectorService.assertSendQuota(record.id());
        return null;
    }

    /** 回复前取出原邮件的发件人（也走一遍白名单/自环校验）。 */
    List<String> originalSender(MailClient client, long uid, String folder) {
        try {
            MailClient.MailDetail detail = client.read(uid, folder);
            return detail.from() == null || detail.from().isBlank()
                    ? List.of()
                    : List.of(detail.from());
        } catch (RuntimeException ex) {
            // 读不到原邮件就不拦：真正发送时会报错，这里的目的是尽量早发现白名单问题
            return List.of();
        }
    }

    /** 附件路径解析：必须是当前用户工作区内的真实文件。 */
    List<Path> resolveAttachments(List<String> relativePaths, long userId) {
        List<Path> result = new ArrayList<>();
        if (relativePaths == null || relativePaths.isEmpty()) {
            return result;
        }
        for (String relative : relativePaths) {
            if (relative == null || relative.isBlank()) {
                continue;
            }
            Path path = paths.resolve(relative.trim(), userId);
            if (!Files.isRegularFile(path)) {
                throw new MailClient.MailException("附件不存在： " + relative
                        + "（请先用 run_command / write_file 把它写到工作区）");
            }
            result.add(path);
        }
        return result;
    }

    /** 写审计（发信/回复/标记统一走这里）。 */
    void record(String action, List<String> targets, String subject, boolean success, String error) {
        connectorService.logAction(
                record.id(), userId, action,
                targets == null ? null : String.join(", ", targets),
                subject, success, error);
    }

    private static String normalize(String recipient) {
        String value = recipient == null ? "" : recipient.trim().toLowerCase(Locale.ROOT);
        int angle = value.lastIndexOf('<');
        if (angle >= 0 && value.endsWith(">")) {
            value = value.substring(angle + 1, value.length() - 1).trim();
        }
        return value;
    }
}
