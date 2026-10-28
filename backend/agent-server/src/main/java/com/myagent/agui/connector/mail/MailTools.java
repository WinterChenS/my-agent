package com.myagent.agui.connector.mail;

import com.myagent.connectorconfig.ConnectorRecord;
import com.myagent.connectorconfig.ConnectorService;
import io.agentscope.core.tool.ToolCallParam;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 邮件连接器的 8 个工具。
 *
 * <p>命名 {@code mail_*}，与平台其它工具（{@code send_im_message} / {@code run_command}）风格一致。
 * 读写标注如实：{@code mail_send} / {@code mail_reply} / {@code mail_mark} 是写操作，
 * Agent 处于 {@code REQUIRE_APPROVAL} 时平台会弹审批卡（官方能力，无需额外代码）。
 */
public final class MailTools {

    private MailTools() {
        // 工具集工厂
    }

    /** 构造全部邮件工具（每个 Agent 一份，内部无状态）。 */
    public static List<io.agentscope.core.tool.AgentTool> create(
            long agentId,
            ConnectorService connectorService,
            Path workspaceRoot,
            int connectTimeoutMs,
            int readTimeoutMs,
            long maxAttachmentBytes,
            long maxAttachmentsTotalBytes,
            int maxBodyChars) {
        return List.of(
                new MailListTool(agentId, connectorService, workspaceRoot,
                        connectTimeoutMs, readTimeoutMs, maxAttachmentBytes, maxAttachmentsTotalBytes, maxBodyChars),
                new MailSearchTool(agentId, connectorService, workspaceRoot,
                        connectTimeoutMs, readTimeoutMs, maxAttachmentBytes, maxAttachmentsTotalBytes, maxBodyChars),
                new MailReadTool(agentId, connectorService, workspaceRoot,
                        connectTimeoutMs, readTimeoutMs, maxAttachmentBytes, maxAttachmentsTotalBytes, maxBodyChars),
                new MailAttachmentTool(agentId, connectorService, workspaceRoot,
                        connectTimeoutMs, readTimeoutMs, maxAttachmentBytes, maxAttachmentsTotalBytes, maxBodyChars),
                new MailFoldersTool(agentId, connectorService, workspaceRoot,
                        connectTimeoutMs, readTimeoutMs, maxAttachmentBytes, maxAttachmentsTotalBytes, maxBodyChars),
                new MailSendTool(agentId, connectorService, workspaceRoot,
                        connectTimeoutMs, readTimeoutMs, maxAttachmentBytes, maxAttachmentsTotalBytes, maxBodyChars),
                new MailReplyTool(agentId, connectorService, workspaceRoot,
                        connectTimeoutMs, readTimeoutMs, maxAttachmentBytes, maxAttachmentsTotalBytes, maxBodyChars),
                new MailMarkTool(agentId, connectorService, workspaceRoot,
                        connectTimeoutMs, readTimeoutMs, maxAttachmentBytes, maxAttachmentsTotalBytes, maxBodyChars));
    }

    // ===================== 读 =====================

    /** 最近邮件列表。 */
    static final class MailListTool extends AbstractMailTool {

        MailListTool(long agentId, ConnectorService connectorService, Path workspaceRoot,
                int connectTimeoutMs, int readTimeoutMs, long maxAttachmentBytes,
                long maxAttachmentsTotalBytes, int maxBodyChars) {
            super(agentId, connectorService, workspaceRoot, connectTimeoutMs, readTimeoutMs,
                    maxAttachmentBytes, maxAttachmentsTotalBytes, maxBodyChars);
        }

        @Override
        public String getName() {
            return "mail_list";
        }

        @Override
        public String getDescription() {
            return "列出邮箱里最近的邮件（默认收件箱 INBOX，按时间从新到旧）。"
                    + "返回每封的 uid、发件人、主题、时间、是否未读、是否有附件。"
                    + "**读正文要用 mail_read（传 uid）**；uid 只在当前文件夹有效，换了文件夹要重新列。"
                    + "用户说「看看我邮箱今天有什么邮件」时先用它。";
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> properties = connectorParam("");
            properties.put("folder", Map.of("type", "string", "description", "可选：文件夹，默认 INBOX"));
            properties.put("unreadOnly", Map.of("type", "boolean", "description", "可选：只看未读"));
            properties.put("sinceDays", Map.of("type", "integer", "description", "可选：只看最近几天（默认 7）"));
            properties.put("limit", Map.of("type", "integer", "description", "可选：最多返回几封（默认 10，最大 50）"));
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", properties);
            return schema;
        }

        @Override
        protected String run(MailContext context, ToolCallParam param) {
            String folder = asString(param.getInput().get("folder"));
            boolean unreadOnly = asBool(param.getInput().get("unreadOnly"), false);
            int sinceDays = asInt(param.getInput().get("sinceDays"), 7);
            int limit = Math.min(Math.max(asInt(param.getInput().get("limit"), 10), 1), 50);
            List<MailClient.MailSummary> messages =
                    context.client().list(folder, unreadOnly, sinceDays, limit);
            if (messages.isEmpty()) {
                return "没有符合条件的邮件（" + (folder == null ? "INBOX" : folder)
                        + (unreadOnly ? "，只看未读" : "") + "，最近 " + sinceDays + " 天）。";
            }
            StringBuilder out = new StringBuilder("共 ").append(messages.size()).append(" 封（")
                    .append(context.settings().email()).append("）：\n");
            for (MailClient.MailSummary message : messages) {
                out.append("- uid=").append(message.uid())
                        .append(message.unread() ? " [未读]" : "")
                        .append(message.hasAttachment() ? " [有附件]" : "")
                        .append(" 发件人：").append(message.from())
                        .append(" | 主题：").append(message.subject())
                        .append(" | 时间：").append(format(message.sentAt()))
                        .append('\n');
            }
            out.append("读正文用 mail_read(uid=...)。");
            return out.toString();
        }
    }

    /** 条件搜索。 */
    static final class MailSearchTool extends AbstractMailTool {

        MailSearchTool(long agentId, ConnectorService connectorService, Path workspaceRoot,
                int connectTimeoutMs, int readTimeoutMs, long maxAttachmentBytes,
                long maxAttachmentsTotalBytes, int maxBodyChars) {
            super(agentId, connectorService, workspaceRoot, connectTimeoutMs, readTimeoutMs,
                    maxAttachmentBytes, maxAttachmentsTotalBytes, maxBodyChars);
        }

        @Override
        public String getName() {
            return "mail_search";
        }

        @Override
        public String getDescription() {
            return "按条件搜索邮件，比 mail_list 更适合「找那封关于发票的邮件」。"
                    + "可按发件人(from)、主题(subject)、正文关键词(text)、最近天数(sinceDays)、只看未读(unreadOnly) 组合搜索。"
                    + "关键词用**短词**（例如「发票」「合同」），不要整句问句。";
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> properties = connectorParam("");
            properties.put("folder", Map.of("type", "string", "description", "可选：在哪个文件夹里搜，默认 INBOX"));
            properties.put("from", Map.of("type", "string", "description", "可选：发件人包含的字符串"));
            properties.put("subject", Map.of("type", "string", "description", "可选：主题包含的关键词"));
            properties.put("text", Map.of("type", "string", "description", "可选：正文包含的关键词"));
            properties.put("sinceDays", Map.of("type", "integer", "description", "可选：最近几天（默认 30）"));
            properties.put("unreadOnly", Map.of("type", "boolean", "description", "可选：只看未读"));
            properties.put("limit", Map.of("type", "integer", "description", "可选：最多返回几封（默认 10，最大 50）"));
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", properties);
            return schema;
        }

        @Override
        protected String run(MailContext context, ToolCallParam param) {
            String folder = asString(param.getInput().get("folder"));
            String from = asString(param.getInput().get("from"));
            String subject = asString(param.getInput().get("subject"));
            String text = asString(param.getInput().get("text"));
            int sinceDays = asInt(param.getInput().get("sinceDays"), 30);
            boolean unreadOnly = asBool(param.getInput().get("unreadOnly"), false);
            int limit = Math.min(Math.max(asInt(param.getInput().get("limit"), 10), 1), 50);
            if ((from == null || from.isBlank()) && (subject == null || subject.isBlank())
                    && (text == null || text.isBlank()) && !unreadOnly) {
                return "请至少给一个搜索条件（from / subject / text / unreadOnly），否则请用 mail_list。";
            }
            List<MailClient.MailSummary> messages =
                    context.client().search(folder, from, subject, text, sinceDays, unreadOnly, limit);
            if (messages.isEmpty()) {
                return "没有符合条件的邮件。可以放宽条件（比如去掉正文关键词、把 sinceDays 调大）。";
            }
            StringBuilder out = new StringBuilder("搜索到 ").append(messages.size()).append(" 封：\n");
            for (MailClient.MailSummary message : messages) {
                out.append("- uid=").append(message.uid())
                        .append(message.unread() ? " [未读]" : "")
                        .append(message.hasAttachment() ? " [有附件]" : "")
                        .append(" 发件人：").append(message.from())
                        .append(" | 主题：").append(message.subject())
                        .append(" | 时间：").append(format(message.sentAt()))
                        .append('\n');
            }
            return out.toString();
        }
    }

    /** 读正文。 */
    static final class MailReadTool extends AbstractMailTool {

        MailReadTool(long agentId, ConnectorService connectorService, Path workspaceRoot,
                int connectTimeoutMs, int readTimeoutMs, long maxAttachmentBytes,
                long maxAttachmentsTotalBytes, int maxBodyChars) {
            super(agentId, connectorService, workspaceRoot, connectTimeoutMs, readTimeoutMs,
                    maxAttachmentBytes, maxAttachmentsTotalBytes, maxBodyChars);
        }

        @Override
        public String getName() {
            return "mail_read";
        }

        @Override
        public String getDescription() {
            return "读取一封邮件的完整内容（发件人/收件人/主题/时间/正文/附件清单）。"
                    + "uid 来自 mail_list 或 mail_search。正文过长会被截断（会明确提示）。"
                    + "附件不会自动下载：要文件就用 mail_get_attachment。";
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> properties = connectorParam("");
            properties.put("uid", Map.of("type", "integer", "description", "邮件 uid（来自 mail_list/mail_search）"));
            properties.put("folder", Map.of("type", "string", "description", "可选：邮件所在文件夹，默认 INBOX"));
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", properties);
            schema.put("required", List.of("uid"));
            return schema;
        }

        @Override
        protected String run(MailContext context, ToolCallParam param) {
            Long uid = asLong(param.getInput().get("uid"));
            if (uid == null) {
                return "请提供 uid（从 mail_list 或 mail_search 里取）。";
            }
            MailClient.MailDetail detail = context.client().read(uid, asString(param.getInput().get("folder")));
            StringBuilder out = new StringBuilder();
            out.append("主题：").append(detail.subject()).append('\n');
            out.append("发件人：").append(detail.from()).append('\n');
            out.append("收件人：").append(detail.to()).append('\n');
            if (detail.cc() != null && !detail.cc().isBlank()) {
                out.append("抄送：").append(detail.cc()).append('\n');
            }
            out.append("时间：").append(format(detail.sentAt())).append('\n');
            if (detail.attachments() != null && !detail.attachments().isEmpty()) {
                out.append("附件（").append(detail.attachments().size()).append(" 个）：\n");
                for (MailMimeParser.AttachmentMeta attachment : detail.attachments()) {
                    out.append("  - [").append(attachment.index()).append("] ").append(attachment.fileName())
                            .append("（").append(MailClient.humanSize(attachment.size()))
                            .append(attachment.inline() ? "，内联图片" : "")
                            .append("）\n");
                }
                out.append("下载用 mail_get_attachment(uid=").append(uid).append(", index=0)。\n");
            }
            out.append("\n--- 正文 ---\n").append(detail.body() == null ? "（无正文）" : detail.body());
            return out.toString();
        }
    }

    /** 下载附件到工作区。 */
    static final class MailAttachmentTool extends AbstractMailTool {

        MailAttachmentTool(long agentId, ConnectorService connectorService, Path workspaceRoot,
                int connectTimeoutMs, int readTimeoutMs, long maxAttachmentBytes,
                long maxAttachmentsTotalBytes, int maxBodyChars) {
            super(agentId, connectorService, workspaceRoot, connectTimeoutMs, readTimeoutMs,
                    maxAttachmentBytes, maxAttachmentsTotalBytes, maxBodyChars);
        }

        @Override
        public String getName() {
            return "mail_get_attachment";
        }

        @Override
        public String getDescription() {
            return "把某个邮件的附件下载到你的工作区（返回相对路径），然后用 run_command 处理它"
                    + "（例如 Python 读 Excel、pdfplumber 读 PDF）。"
                    + "附件序号(index) 从 mail_read 的附件清单里取，从 0 开始。";
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> properties = connectorParam("");
            properties.put("uid", Map.of("type", "integer", "description", "邮件 uid"));
            properties.put("index", Map.of("type", "integer", "description", "附件序号（从 0 开始）"));
            properties.put("folder", Map.of("type", "string", "description", "可选：邮件所在文件夹，默认 INBOX"));
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", properties);
            schema.put("required", List.of("uid", "index"));
            return schema;
        }

        @Override
        protected String run(MailContext context, ToolCallParam param) {
            Long uid = asLong(param.getInput().get("uid"));
            Integer index = asInt(param.getInput().get("index"), null);
            if (uid == null || index == null) {
                return "请提供 uid 和 index（附件序号从 0 开始）。";
            }
            long userId = Long.parseLong(param.getRuntimeContext().getUserId());
            Path dir = context.attachmentDir(workspaceRoot, userId);
            MailClient.SavedAttachment saved =
                    context.client().saveAttachment(uid, index, asString(param.getInput().get("folder")), dir);
            Path base = workspaceRoot.resolve(String.valueOf(userId));
            String relative = base.relativize(saved.path()).toString().replace('\\', '/');
            return "已保存附件「" + saved.fileName() + "」到工作区：" + relative
                    + "（" + MailClient.humanSize(saved.sizeBytes()) + "）\n"
                    + "可以用 run_command 处理它，例如： python -c \"import openpyxl;...\"，或 pdfplumber 读 PDF。";
        }
    }

    /** 列文件夹。 */
    static final class MailFoldersTool extends AbstractMailTool {

        MailFoldersTool(long agentId, ConnectorService connectorService, Path workspaceRoot,
                int connectTimeoutMs, int readTimeoutMs, long maxAttachmentBytes,
                long maxAttachmentsTotalBytes, int maxBodyChars) {
            super(agentId, connectorService, workspaceRoot, connectTimeoutMs, readTimeoutMs,
                    maxAttachmentBytes, maxAttachmentsTotalBytes, maxBodyChars);
        }

        @Override
        public String getName() {
            return "mail_folders";
        }

        @Override
        public String getDescription() {
            return "列出邮箱里的文件夹（收件箱、已发送、垃圾箱、自定义标签等）。"
                    + "当用户说「看看已发送」或 mail_list 找不到邮件时，先用它确认文件夹名称。";
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", connectorParam(""));
            return schema;
        }

        @Override
        protected String run(MailContext context, ToolCallParam param) {
            List<String> folders = context.client().folders();
            if (folders.isEmpty()) {
                return "邮箱里没有可用文件夹（可能是协议权限受限）。";
            }
            return "文件夹清单（" + context.settings().email() + "）：\n- "
                    + String.join("\n- ", folders);
        }
    }

    // ===================== 写 =====================

    /** 发信。 */
    static final class MailSendTool extends AbstractMailTool {

        MailSendTool(long agentId, ConnectorService connectorService, Path workspaceRoot,
                int connectTimeoutMs, int readTimeoutMs, long maxAttachmentBytes,
                long maxAttachmentsTotalBytes, int maxBodyChars) {
            super(agentId, connectorService, workspaceRoot, connectTimeoutMs, readTimeoutMs,
                    maxAttachmentBytes, maxAttachmentsTotalBytes, maxBodyChars);
        }

        @Override
        public String getName() {
            return "mail_send";
        }

        @Override
        public String getDescription() {
            return "**以用户的身份真实发送一封邮件**（不是草稿，发出去无法撤回）。"
                    + "发送前必须让用户确认收件人、主题和正文要点；用户没明确要求发信时不要自己发。"
                    + "附件用工作区相对路径（例如 uploads/报告.pdf）。"
                    + "如果用户配置了发信白名单，收件人不符会被拒绝。";
        }

        @Override
        public boolean isReadOnly() {
            return false;
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> properties = connectorParam("");
            properties.put("to", Map.of("type", "string", "description", "收件人，多个用英文逗号分隔"));
            properties.put("cc", Map.of("type", "string", "description", "可选：抄送"));
            properties.put("bcc", Map.of("type", "string", "description", "可选：密送"));
            properties.put("subject", Map.of("type", "string", "description", "主题"));
            properties.put("body", Map.of("type", "string", "description", "正文（纯文本）"));
            properties.put("html", Map.of("type", "boolean", "description", "可选：body 是否是 HTML（默认 false）"));
            properties.put("attachments", Map.of(
                    "type", "array",
                    "items", Map.of("type", "string"),
                    "description", "可选：附件的工作区相对路径列表"));
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", properties);
            schema.put("required", List.of("to", "subject", "body"));
            return schema;
        }

        @Override
        protected String run(MailContext context, ToolCallParam param) {
            List<String> to = asStringList(param.getInput().get("to"));
            List<String> cc = asStringList(param.getInput().get("cc"));
            List<String> bcc = asStringList(param.getInput().get("bcc"));
            String subject = asString(param.getInput().get("subject"));
            String body = asString(param.getInput().get("body"));
            boolean html = asBool(param.getInput().get("html"), false);
            if (to.isEmpty()) {
                return "缺少收件人（to）。";
            }
            if (subject == null || subject.isBlank()) {
                return "缺少主题（subject）。";
            }
            long userId = Long.parseLong(param.getRuntimeContext().getUserId());
            MailSendGuard guard = new MailSendGuard(context.settings(), context.connectorService(),
                    context.record(), userId, this::resolveWorkspacePath);
            String rejection = guard.check(to, cc, bcc);
            if (rejection != null) {
                return rejection;
            }
            List<Path> attachments = guard.resolveAttachments(asStringList(param.getInput().get("attachments")), userId);
            try {
                String messageId = context.client().send(to, cc, bcc, subject, body, html, attachments);
                guard.record("SEND", to, subject, true, null);
                return "已发送邮件给 " + String.join(", ", to) + "（主题：" + subject + "）"
                        + (attachments.isEmpty() ? "" : "，附件 " + attachments.size() + " 个")
                        + "。" + (messageId == null ? "" : "\nMessage-ID：" + messageId);
            } catch (RuntimeException ex) {
                guard.record("SEND", to, subject, false, ex.getMessage());
                throw ex;
            }
        }
    }

    /** 回复。 */
    static final class MailReplyTool extends AbstractMailTool {

        MailReplyTool(long agentId, ConnectorService connectorService, Path workspaceRoot,
                int connectTimeoutMs, int readTimeoutMs, long maxAttachmentBytes,
                long maxAttachmentsTotalBytes, int maxBodyChars) {
            super(agentId, connectorService, workspaceRoot, connectTimeoutMs, readTimeoutMs,
                    maxAttachmentBytes, maxAttachmentsTotalBytes, maxBodyChars);
        }

        @Override
        public String getName() {
            return "mail_reply";
        }

        @Override
        public String getDescription() {
            return "**回复**一封已有邮件（自动带上会话关系，收件人取自原发件人）。"
                    + "发出去无法撤回，用户没明确要求回复时不要自己回。"
                    + "uid 来自 mail_list / mail_search，正文里不要重复引用整段原文。";
        }

        @Override
        public boolean isReadOnly() {
            return false;
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> properties = connectorParam("");
            properties.put("uid", Map.of("type", "integer", "description", "要回复的邮件 uid"));
            properties.put("body", Map.of("type", "string", "description", "回复正文（纯文本）"));
            properties.put("html", Map.of("type", "boolean", "description", "可选：body 是否是 HTML（默认 false）"));
            properties.put("folder", Map.of("type", "string", "description", "可选：邮件所在文件夹，默认 INBOX"));
            properties.put("attachments", Map.of(
                    "type", "array",
                    "items", Map.of("type", "string"),
                    "description", "可选：附件的工作区相对路径列表"));
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", properties);
            schema.put("required", List.of("uid", "body"));
            return schema;
        }

        @Override
        protected String run(MailContext context, ToolCallParam param) {
            Long uid = asLong(param.getInput().get("uid"));
            String body = asString(param.getInput().get("body"));
            boolean html = asBool(param.getInput().get("html"), false);
            if (uid == null) {
                return "缺少 uid（要回复哪封邮件）。";
            }
            if (body == null || body.isBlank()) {
                return "缺少回复正文（body）。";
            }
            long userId = Long.parseLong(param.getRuntimeContext().getUserId());
            MailSendGuard guard = new MailSendGuard(context.settings(), context.connectorService(),
                    context.record(), userId, this::resolveWorkspacePath);
            String rejection = guard.check(guard.originalSender(context.client(), uid,
                    asString(param.getInput().get("folder"))), List.of(), List.of());
            if (rejection != null) {
                return rejection;
            }
            List<Path> attachments = guard.resolveAttachments(asStringList(param.getInput().get("attachments")), userId);
            try {
                String messageId = context.client().reply(uid, asString(param.getInput().get("folder")), body, html, attachments);
                guard.record("REPLY", List.of("uid=" + uid), "Re: 回复", true, null);
                return "已回复 uid=" + uid + " 的邮件。" + (messageId == null ? "" : "\nMessage-ID：" + messageId);
            } catch (RuntimeException ex) {
                guard.record("REPLY", List.of("uid=" + uid), "Re: 回复", false, ex.getMessage());
                throw ex;
            }
        }
    }

    /** 标记 / 移动。 */
    static final class MailMarkTool extends AbstractMailTool {

        MailMarkTool(long agentId, ConnectorService connectorService, Path workspaceRoot,
                int connectTimeoutMs, int readTimeoutMs, long maxAttachmentBytes,
                long maxAttachmentsTotalBytes, int maxBodyChars) {
            super(agentId, connectorService, workspaceRoot, connectTimeoutMs, readTimeoutMs,
                    maxAttachmentBytes, maxAttachmentsTotalBytes, maxBodyChars);
        }

        @Override
        public String getName() {
            return "mail_mark";
        }

        @Override
        public String getDescription() {
            return "给邮件打标记（已读/未读/星标）或移动到其它文件夹（归档、垃圾箱）。"
                    + "这会**改动用户真实邮箱**，用户没要求时不要主动整理。"
                    + "移动前先用 mail_folders 确认目标文件夹名称。";
        }

        @Override
        public boolean isReadOnly() {
            return false;
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> properties = connectorParam("");
            properties.put("uid", Map.of("type", "integer", "description", "邮件 uid"));
            properties.put("flag", Map.of("type", "string",
                    "description", "可选：SEEN / UNSEEN / FLAGGED / UNFLAGGED"));
            properties.put("moveTo", Map.of("type", "string", "description", "可选：移动到的文件夹名（如 Trash）"));
            properties.put("folder", Map.of("type", "string", "description", "可选：邮件所在文件夹，默认 INBOX"));
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", properties);
            schema.put("required", List.of("uid"));
            return schema;
        }

        @Override
        protected String run(MailContext context, ToolCallParam param) {
            Long uid = asLong(param.getInput().get("uid"));
            if (uid == null) {
                return "缺少 uid。";
            }
            String flag = asString(param.getInput().get("flag"));
            String moveTo = asString(param.getInput().get("moveTo"));
            if ((flag == null || flag.isBlank()) && (moveTo == null || moveTo.isBlank())) {
                return "请给出 flag 或 moveTo 之一。";
            }
            long userId = Long.parseLong(param.getRuntimeContext().getUserId());
            MailSendGuard guard = new MailSendGuard(context.settings(), context.connectorService(),
                    context.record(), userId, this::resolveWorkspacePath);
            try {
                context.client().mark(uid, flag, moveTo, asString(param.getInput().get("folder")));
                String action = moveTo == null || moveTo.isBlank() ? "标记 " + flag : "移动到 " + moveTo;
                guard.record("MARK", List.of("uid=" + uid), action, true, null);
                return "已" + action + "（uid=" + uid + "）。";
            } catch (RuntimeException ex) {
                guard.record("MARK", List.of("uid=" + uid), String.valueOf(flag), false, ex.getMessage());
                throw ex;
            }
        }
    }

    // ===================== 工具内小工具 =====================

    private static String format(java.time.OffsetDateTime time) {
        if (time == null) {
            return "未知时间";
        }
        return time.toLocalDateTime().toString().replace('T', ' ');
    }

    /** 让 {@code AbstractMailTool.resolveWorkspacePath} 能在工具外的辅助类里复用。 */
    @FunctionalInterface
    interface PathResolver {
        Path resolve(String relative, long userId);
    }

    /** 占位：把 List.of 里的空附件判空写清楚。 */
    static List<Path> emptyAttachments() {
        return new ArrayList<>();
    }
}
