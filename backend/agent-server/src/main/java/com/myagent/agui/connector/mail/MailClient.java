package com.myagent.agui.connector.mail;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.NoSuchProviderException;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.search.AndTerm;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.FlagTerm;
import jakarta.mail.search.FromStringTerm;
import jakarta.mail.search.OrTerm;
import jakarta.mail.search.ReceivedDateTerm;
import jakarta.mail.search.SearchTerm;
import jakarta.mail.search.SubjectTerm;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * 邮件的 IMAP / SMTP 实现（Jakarta Mail）。
 *
 * <p>约定：
 * <ul>
 *   <li>**每次操作单独开/关连接**：邮件协议对并发与超时很敏感，共享 Store 会在多线程下串话；</li>
 *   <li>读操作用 {@code READ_ONLY}（不会因为"读过"而改用户邮箱状态，除了显式 {@link #mark}）；</li>
 *   <li>所有异常统一包成 {@link MailException}，{@code message} 是**人话**，直接能展示给用户/模型。</li>
 * </ul>
 */
public class MailClient {

    /** 单封邮件正文上限（超出截断并提示）。 */
    public static final int DEFAULT_MAX_BODY_CHARS = 40_000;

    /** 截断提示的前缀（用常量避免源码里的转义字符被工具链吃掉）。 */
    private static final String NEWLINE_MARK = System.lineSeparator();

    private static final Pattern UNSAFE_FILE_CHARS = Pattern.compile("[\\\\/:*?\"<>|\\r\\n\\t]");

    private final MailSettings settings;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final long maxAttachmentBytes;
    private final long maxAttachmentsTotalBytes;
    private final int maxBodyChars;

    public MailClient(MailSettings settings) {
        this(settings, 10_000, 30_000, 20L * 1024 * 1024, 50L * 1024 * 1024, DEFAULT_MAX_BODY_CHARS);
    }

    public MailClient(
            MailSettings settings,
            int connectTimeoutMs,
            int readTimeoutMs,
            long maxAttachmentBytes,
            long maxAttachmentsTotalBytes,
            int maxBodyChars) {
        this.settings = settings;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.maxAttachmentBytes = maxAttachmentBytes;
        this.maxAttachmentsTotalBytes = maxAttachmentsTotalBytes;
        this.maxBodyChars = maxBodyChars;
    }

    public MailSettings settings() {
        return settings;
    }

    // ===================== 读 =====================

    /** 邮件摘要（列表/搜索结果）。 */
    public record MailSummary(
            long uid,
            String from,
            String subject,
            OffsetDateTime sentAt,
            boolean unread,
            boolean hasAttachment) {}

    /** 一封邮件的详情。 */
    public record MailDetail(
            long uid,
            String from,
            String to,
            String cc,
            String subject,
            OffsetDateTime sentAt,
            String messageId,
            String body,
            boolean truncated,
            List<MailMimeParser.AttachmentMeta> attachments) {}

    /** 最近邮件。 */
    public List<MailSummary> list(String folderName, boolean unreadOnly, int sinceDays, int limit) {
        return withFolder(folderName, Folder.READ_ONLY, (folder, uidFolder) -> {
            Message[] messages = folder.getMessages();
            List<MailSummary> result = new ArrayList<>();
            Date since = sinceDays > 0 ? Date.from(java.time.Instant.now().minusSeconds(sinceDays * 86400L)) : null;
            // 从新到旧遍历（IMAP 的 getMessages 是按 UID 升序）
            for (int i = messages.length - 1; i >= 0 && result.size() < limit; i--) {
                Message message = messages[i];
                if (unreadOnly && message.isSet(Flags.Flag.SEEN)) {
                    continue;
                }
                if (since != null) {
                    Date received = message.getReceivedDate();
                    if (received != null && received.before(since)) {
                        break;
                    }
                }
                result.add(summary(folder, message));
            }
            return result;
        });
    }

    /** 条件搜索。 */
    public List<MailSummary> search(
            String folderName,
            String from,
            String subject,
            String text,
            Integer sinceDays,
            boolean unreadOnly,
            int limit) {
        return withFolder(folderName, Folder.READ_ONLY, (folder, uidFolder) -> {
            List<SearchTerm> terms = new ArrayList<>();
            if (from != null && !from.isBlank()) {
                terms.add(new FromStringTerm(from.trim()));
            }
            if (subject != null && !subject.isBlank()) {
                terms.add(new SubjectTerm(subject.trim()));
            }
            if (sinceDays != null && sinceDays > 0) {
                Date since = Date.from(java.time.Instant.now().minusSeconds(sinceDays * 86400L));
                terms.add(new ReceivedDateTerm(ComparisonTerm.GE, since));
            }
            if (unreadOnly) {
                terms.add(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
            }
            Message[] matched = terms.isEmpty()
                    ? folder.getMessages()
                    : folder.search(terms.size() == 1 ? terms.get(0) : new AndTerm(terms.toArray(SearchTerm[]::new)));
            // 正文关键词服务器端不一定支持；统一在客户端做包含匹配（只对已匹配的少量结果做）
            List<MailSummary> result = new ArrayList<>();
            for (int i = matched.length - 1; i >= 0 && result.size() < limit; i--) {
                Message message = matched[i];
                if (text != null && !text.isBlank() && !containsText(message, text)) {
                    continue;
                }
                result.add(summary(folder, message));
            }
            return result;
        });
    }

    private static boolean containsText(Message message, String text) {
        try {
            String body = MailMimeParser.parse(message).text();
            String needle = text.toLowerCase(java.util.Locale.ROOT);
            return body.toLowerCase(java.util.Locale.ROOT).contains(needle)
                    || String.valueOf(message.getSubject()).toLowerCase(java.util.Locale.ROOT).contains(needle);
        } catch (Exception ex) {
            return false;
        }
    }

    /** 读一封（按 UID）。 */
    public MailDetail read(long uid, String folderName) {
        return withFolder(folderName, Folder.READ_ONLY, (folder, uidFolder) -> {
            Message message = requireMessage(folder, uidFolder, uid);
            long actualUid = uidFolder.getUID(message);
            MailMimeParser.ParsedBody parsed = MailMimeParser.parse(message);
            String body = parsed.text() == null ? "" : parsed.text();
            boolean truncated = body.length() > maxBodyChars;
            if (truncated) {
                body = body.substring(0, maxBodyChars)
                        + "\n\n…（正文过长已截断，共 " + parsed.text().length() + " 字符）";
            }
            return new MailDetail(
                    actualUid,
                    MailMimeParser.address(message.getFrom()),
                    MailMimeParser.address(message.getRecipients(Message.RecipientType.TO)),
                    MailMimeParser.address(message.getRecipients(Message.RecipientType.CC)),
                    String.valueOf(message.getSubject()),
                    toOffset(message.getReceivedDate() == null ? message.getSentDate() : message.getReceivedDate()),
                    firstHeader(message, "Message-ID"),
                    body,
                    truncated,
                    parsed.attachments());
        });
    }

    /** 已保存的附件。 */
    public record SavedAttachment(String fileName, Path path, long sizeBytes, boolean truncated) {}

    /** 把某个附件存到工作区目录。 */
    public SavedAttachment saveAttachment(long uid, int index, String folderName, Path destDir) {
        return withFolder(folderName, Folder.READ_ONLY, (folder, uidFolder) -> {
            Message message = requireMessage(folder, uidFolder, uid);
            Part part = MailMimeParser.attachmentAt(message, index);
            String fileName = MailMimeParser.decodeFileName(part.getFileName());
            if (fileName == null || fileName.isBlank()) {
                fileName = "attachment-" + (index + 1);
            }
            long size = part.getSize();
            if (size > maxAttachmentBytes) {
                throw new MailException("附件「" + fileName + "」太大（" + humanSize(size)
                        + "），超过单件上限 " + humanSize(maxAttachmentBytes)
                        + "。可以让用户改用其它方式提供这个文件。");
            }
            if (size > maxAttachmentsTotalBytes) {
                throw new MailException("附件「" + fileName + "」超过单次合计上限 "
                        + humanSize(maxAttachmentsTotalBytes));
            }
            Files.createDirectories(destDir);
            Path target = uniqueTarget(destDir, uid + "_" + sanitize(fileName));
            try (var input = part.getInputStream()) {
                Files.copy(input, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return new SavedAttachment(fileName, target, Files.size(target), false);
        });
    }

    /** 入站轮询用的一封新邮件。 */
    public record InboundRaw(
            long uid,
            Long uidValidity,
            String messageId,
            String threadKey,
            String from,
            String fromName,
            String subject,
            String text,
            OffsetDateTime receivedAt,
            boolean autoSubmitted,
            String precedence,
            List<String> recipients) {}

    /**
     * 拉取「还没处理过」的新邮件（只读）。
     *
     * @param lastUid 上次处理到的 UID；{@code lastUidValidity} 与当前不一致时说明邮箱被重建，按未读重新取
     * @param limit 单次最多返回多少封（防一次性拉几千封）
     */
    public List<MailClient.InboundRaw> pollNew(long lastUid, Long lastUidValidity, int limit) {
        return withFolder("INBOX", Folder.READ_ONLY, (folder, uidFolder) -> {
            Long validity = uidFolder.getUIDValidity();
            boolean reset = lastUidValidity == null || !lastUidValidity.equals(validity);
            Message[] messages;
            if (reset) {
                // 游标不可信（换邮箱/被重建/第一次跑）：只取未读，避免把历史邮件全灌进来
                messages = folder.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
            } else {
                messages = uidFolder.getMessagesByUID(lastUid + 1, jakarta.mail.UIDFolder.LASTUID);
            }
            List<MailClient.InboundRaw> result = new ArrayList<>();
            int count = 0;
            for (Message message : messages) {
                if (count >= limit) {
                    break;
                }
                long uid = uidFolder.getUID(message);
                if (!reset && uid <= lastUid) {
                    continue;
                }
                count++;
                result.add(inboundRaw(message, uidFolder, message, uid, validity));
            }
            return result;
        });
    }

    private static MailClient.InboundRaw inboundRaw(
            Message message,
            jakarta.mail.UIDFolder uidFolder,
            Message ignored,
            long uid,
            Long validity) {
        try {
            MailMimeParser.ParsedBody parsed = MailMimeParser.parse(message);
            String text = parsed.text() == null ? "" : parsed.text();
            if (text.length() > 8000) {
                text = text.substring(0, 8000) + NEWLINE_MARK + "（正文过长已截断）";
            }
            String from = MailMimeParser.address(message.getFrom());
            String messageId = firstHeader(message, "Message-ID");
            String references = firstHeader(message, "References");
            String threadKey = references != null && !references.isBlank()
                    ? references.split(" ")[0]
                    : (messageId == null ? "uid:" + uid : messageId);
            return new MailClient.InboundRaw(
                    uid,
                    validity,
                    messageId == null ? "uid:" + uid : messageId,
                    threadKey,
                    from,
                    fromName(from),
                    String.valueOf(message.getSubject()),
                    text,
                    toOffset(message.getReceivedDate() == null ? message.getSentDate() : message.getReceivedDate()),
                    autoSubmitted(message),
                    firstHeader(message, "Precedence"),
                    List.of(MailMimeParser.address(message.getRecipients(Message.RecipientType.TO))));
        } catch (Exception ex) {
            throw translate("解析新邮件失败 uid=" + uid, ex);
        }
    }

    private static boolean autoSubmitted(Message message) {
        String value = firstHeader(message, "Auto-Submitted");
        return value != null && !value.isBlank() && !"no".equalsIgnoreCase(value.trim());
    }

    private static String fromName(String from) {
        if (from == null || from.isBlank()) {
            return "";
        }
        int at = from.indexOf('@');
        return at > 0 ? from.substring(0, at) : from;
    }

    /** 文件夹清单。 */
    public List<String> folders() {
        Store store = null;
        try {
            store = connect();
            List<String> names = new ArrayList<>();
            for (Folder folder : store.getDefaultFolder().list("*")) {
                if ((folder.getType() & Folder.HOLDS_MESSAGES) != 0) {
                    names.add(folder.getFullName());
                }
            }
            return names;
        } catch (MessagingException ex) {
            throw translate("列出邮件文件夹失败", ex);
        } finally {
            close(store);
        }
    }

    /** 标记（SEEN/UNSEEN/FLAGGED）或移动（moveTo）。 */
    public void mark(long uid, String flag, String moveTo, String folderName) {
        withFolder(folderName, Folder.READ_WRITE, (folder, uidFolder) -> {
            Message message = requireMessage(folder, uidFolder, uid);
            if (moveTo != null && !moveTo.isBlank()) {
                Folder target = folder.getStore().getFolder(moveTo);
                if (!target.exists()) {
                    throw new MailException("目标文件夹不存在：" + moveTo);
                }
                folder.copyMessages(new Message[] {message}, target);
                message.setFlag(Flags.Flag.DELETED, true);
                folder.expunge();
                return null;
            }
            String normalized = flag == null ? "" : flag.trim().toUpperCase(java.util.Locale.ROOT);
            switch (normalized) {
                case "SEEN" -> message.setFlag(Flags.Flag.SEEN, true);
                case "UNSEEN" -> message.setFlag(Flags.Flag.SEEN, false);
                case "FLAGGED" -> message.setFlag(Flags.Flag.FLAGGED, true);
                case "UNFLAGGED" -> message.setFlag(Flags.Flag.FLAGGED, false);
                default -> throw new MailException("不支持的标记：" + flag + "（可用 SEEN/UNSEEN/FLAGGED/UNFLAGGED）");
            }
            return null;
        });
    }

    // ===================== 写 =====================

    /** 发一封新邮件，返回 Message-ID。 */
    public String send(
            List<String> to,
            List<String> cc,
            List<String> bcc,
            String subject,
            String body,
            boolean html,
            List<Path> attachments) {
        try {
            MimeMessage message = newMessage();
            message.setFrom(new InternetAddress(settings.email(), settings.displayName(), "UTF-8"));
            message.setRecipients(Message.RecipientType.TO, addresses(to));
            if (cc != null && !cc.isEmpty()) {
                message.setRecipients(Message.RecipientType.CC, addresses(cc));
            }
            if (bcc != null && !bcc.isEmpty()) {
                message.setRecipients(Message.RecipientType.BCC, addresses(bcc));
            }
            message.setSubject(subject, "UTF-8");
            message.setSentDate(new Date());
            fillBody(message, body, html, attachments);
            return deliver(message);
        } catch (MailException ex) {
            throw ex;
        } catch (Exception ex) {
            throw translate("发信失败", ex);
        }
    }

    /**
     * 回复一封邮件：自动带上 {@code In-Reply-To}/{@code References}（保持会话串）与 {@code Re:} 前缀。
     */
    public String reply(long uid, String folderName, String body, boolean html, List<Path> attachments) {
        return replyInternal(uid, folderName, body, html, attachments, false);
    }

    /**
     * 自动回复（AUTOREPLY）：额外带上 RFC 3834 的 {@code Auto-Submitted: auto-replied}，
     * 让对方邮箱知道这是机器回复，避免两边自动回复互相轰炸。
     */
    public String replyAuto(long uid, String folderName, String body) {
        return replyInternal(uid, folderName, body, false, List.of(), true);
    }

    private String replyInternal(
            long uid, String folderName, String body, boolean html, List<Path> attachments, boolean autoReplied) {
        return withFolder(folderName, Folder.READ_ONLY, (folder, uidFolder) -> {
            Message original = requireMessage(folder, uidFolder, uid);
            try {
                MimeMessage message = newMessage();
                message.setFrom(new InternetAddress(settings.email(), settings.displayName(), "UTF-8"));
                String replyTo = MailMimeParser.address(original.getFrom());
                message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(replyTo));
                String subject = String.valueOf(original.getSubject());
                message.setSubject(subject.toLowerCase(java.util.Locale.ROOT).startsWith("re:")
                        ? subject : "Re: " + subject, "UTF-8");
                String messageId = firstHeader(original, "Message-ID");
                String references = firstHeader(original, "References");
                if (messageId != null) {
                    message.setHeader("In-Reply-To", messageId);
                    message.setHeader("References",
                            references == null || references.isBlank() ? messageId : references + " " + messageId);
                }
                if (autoReplied) {
                    message.setHeader("Auto-Submitted", "auto-replied");
                    message.setHeader("X-Auto-Response-Suppress", "All");
                }
                message.setSentDate(new Date());
                fillBody(message, body, html, attachments);
                return deliver(message);
            } catch (MessagingException ex) {
                throw translate("构造回复失败", ex);
            }
        });
    }

    // ===================== 探测 =====================

    /**
     * 连通性探测：先 IMAP 登录，再 SMTP 握手（不发信）。
     *
     * @return 步骤描述（成功时用来告诉用户"两条链路都通了"）
     */
    public List<String> probe() {
        List<String> steps = new ArrayList<>();
        Store store = null;
        try {
            store = connect();
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            int count = inbox.getMessageCount();
            inbox.close(false);
            steps.add("IMAP 登录成功，收件箱 " + count + " 封");
        } catch (MessagingException ex) {
            throw translate("IMAP 连接失败", ex);
        } finally {
            close(store);
        }
        jakarta.mail.Transport transport = null;
        try {
            Session session = session();
            transport = session.getTransport(settings.smtpSsl() ? "smtps" : "smtp");
            transport.connect(settings.smtpHost(), settings.smtpPort(), settings.username(), settings.password());
            steps.add("SMTP 登录成功（未发信，仅验证凭据）");
        } catch (MessagingException ex) {
            throw translate("SMTP 连接失败", ex);
        } finally {
            if (transport != null) {
                try {
                    transport.close();
                } catch (MessagingException ignored) {
                    // 关闭失败不影响结论
                }
            }
        }
        return steps;
    }

    // ===================== 内部 =====================

    private interface FolderAction<T> {
        T run(Folder folder, jakarta.mail.UIDFolder uidFolder) throws MessagingException, java.io.IOException;
    }

    private <T> T withFolder(String folderName, int mode, FolderAction<T> action) {
        Store store = null;
        Folder folder = null;
        try {
            store = connect();
            folder = store.getFolder(normalizeFolder(folderName));
            if (!folder.exists()) {
                throw new MailException("文件夹不存在：" + folderName);
            }
            folder.open(mode);
            return action.run(folder, (jakarta.mail.UIDFolder) folder);
        } catch (MailException ex) {
            throw ex;
        } catch (MessagingException | java.io.IOException ex) {
            throw translate("访问邮件失败", ex);
        } finally {
            if (folder != null) {
                try {
                    if (folder.isOpen()) {
                        folder.close(false);
                    }
                } catch (MessagingException ignored) {
                    // 关闭失败忽略
                }
            }
            close(store);
        }
    }

    private static String normalizeFolder(String folderName) {
        return folderName == null || folderName.isBlank() ? "INBOX" : folderName.trim();
    }

    private Message requireMessage(Folder folder, jakarta.mail.UIDFolder uidFolder, long uid)
            throws MessagingException {
        Message message = uidFolder.getMessageByUID(uid);
        if (message == null) {
            throw new MailException("邮件不存在或已被删除：uid=" + uid
                    + "（文件夹 " + folder.getFullName() + "）。可能是别的文件夹里的邮件，先用 mail_folders 看一下，"
                    + "或重新 mail_list/mail_search 拿最新 uid。");
        }
        return message;
    }

    private MailSummary summary(Folder folder, Message message) throws MessagingException {
        long uid = ((jakarta.mail.UIDFolder) folder).getUID(message);
        // 先取"是否未读"，**再**去解析结构：解析会读邮件正文，
        // 个别服务器（以及内存测试服务器）会在读内容时把邮件标成已读，
        // 顺序反了就会出现"刚列出来的邮件显示已读"。
        boolean unread = !message.isSet(Flags.Flag.SEEN);
        boolean hasAttachment = false;
        try {
            hasAttachment = MailMimeParser.attachmentCount(message) > 0;
        } catch (Exception ignored) {
            // 结构解析失败就不标记附件，不影响列表
        }
        return new MailSummary(
                uid,
                MailMimeParser.address(message.getFrom()),
                String.valueOf(message.getSubject()),
                toOffset(message.getReceivedDate() == null ? message.getSentDate() : message.getReceivedDate()),
                unread,
                hasAttachment);
    }

    private Store connect() throws MessagingException {
        Session session = session();
        Store store = session.getStore(settings.imapSsl() ? "imaps" : "imap");
        try {
            store.connect(settings.imapHost(), settings.imapPort(), settings.username(), settings.password());
        } catch (AuthenticationFailedException ex) {
            throw new MailException("IMAP 登录失败：账号或授权码错误（QQ/163/Gmail 要用「授权码」而不是登录密码）");
        } catch (NoSuchProviderException ex) {
            throw new MailException("不支持 IMAP 协议（imapSsl=" + settings.imapSsl() + "）");
        } catch (MessagingException ex) {
            throw new MailException("IMAP 连接失败：" + settings.imapHost() + ":" + settings.imapPort()
                    + " → " + humanMessage(ex));
        }
        return store;
    }

    private Session session() {
        Properties props = new Properties();
        props.put("mail.store.protocol", settings.imapSsl() ? "imaps" : "imap");
        props.put("mail.imaps.host", settings.imapHost());
        props.put("mail.imaps.port", String.valueOf(settings.imapPort()));
        props.put("mail.imaps.connectiontimeout", String.valueOf(connectTimeoutMs));
        props.put("mail.imaps.timeout", String.valueOf(readTimeoutMs));
        props.put("mail.imaps.ssl.enable", String.valueOf(settings.imapSsl()));
        props.put("mail.imap.host", settings.imapHost());
        props.put("mail.imap.port", String.valueOf(settings.imapPort()));
        props.put("mail.imap.connectiontimeout", String.valueOf(connectTimeoutMs));
        props.put("mail.imap.timeout", String.valueOf(readTimeoutMs));
        props.put("mail.imap.starttls.enable", String.valueOf(!settings.imapSsl()));

        props.put("mail.smtp.host", settings.smtpHost());
        props.put("mail.smtp.port", String.valueOf(settings.smtpPort()));
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.connectiontimeout", String.valueOf(connectTimeoutMs));
        props.put("mail.smtp.timeout", String.valueOf(readTimeoutMs));
        props.put("mail.smtp.writetimeout", String.valueOf(readTimeoutMs));
        props.put("mail.smtps.host", settings.smtpHost());
        props.put("mail.smtps.port", String.valueOf(settings.smtpPort()));
        props.put("mail.smtps.auth", "true");
        props.put("mail.smtp.ssl.enable", String.valueOf(settings.smtpSsl()));
        props.put("mail.smtps.ssl.enable", String.valueOf(settings.smtpSsl()));
        // 587 端口常见组合：不加密连接 + STARTTLS
        props.put("mail.smtp.starttls.enable", String.valueOf(!settings.smtpSsl()));
        props.put("mail.smtp.starttls.required", "false");
        return Session.getInstance(props);
    }

    private MimeMessage newMessage() {
        return new MimeMessage(session());
    }

    private void fillBody(MimeMessage message, String body, boolean html, List<Path> attachments)
            throws MessagingException, java.io.IOException {
        if (attachments == null || attachments.isEmpty()) {
            message.setContent(body == null ? "" : body, html ? "text/html; charset=UTF-8" : "text/plain; charset=UTF-8");
            return;
        }
        Multipart multipart = new MimeMultipart();
        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setContent(body == null ? "" : body, html ? "text/html; charset=UTF-8" : "text/plain; charset=UTF-8");
        multipart.addBodyPart(textPart);
        for (Path path : attachments) {
            if (path == null) {
                continue;
            }
            File file = path.toFile();
            if (!file.isFile()) {
                throw new MailException("附件不存在：" + path);
            }
            if (file.length() > maxAttachmentBytes) {
                throw new MailException("附件太大：" + file.getName() + "（" + humanSize(file.length())
                        + "，上限 " + humanSize(maxAttachmentBytes) + "）");
            }
            MimeBodyPart part = new MimeBodyPart();
            part.attachFile(file);
            part.setFileName(MimeUtility.encodeText(file.getName(), "UTF-8", null));
            multipart.addBodyPart(part);
        }
        message.setContent(multipart);
    }

    private String deliver(MimeMessage message) throws MessagingException {
        try {
            jakarta.mail.Transport transport = session().getTransport(settings.smtpSsl() ? "smtps" : "smtp");
            try {
                transport.connect(settings.smtpHost(), settings.smtpPort(), settings.username(), settings.password());
                transport.sendMessage(message, message.getAllRecipients());
            } finally {
                transport.close();
            }
        } catch (AuthenticationFailedException ex) {
            throw new MailException("SMTP 登录失败：账号或授权码错误");
        } catch (MessagingException ex) {
            throw new MailException("发信失败：" + humanMessage(ex));
        }
        return firstHeader(message, "Message-ID");
    }

    private static jakarta.mail.Address[] addresses(List<String> values) throws MessagingException {
        List<jakarta.mail.Address> result = new ArrayList<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            for (jakarta.mail.Address address : InternetAddress.parse(value.trim(), true)) {
                result.add(address);
            }
        }
        return result.toArray(jakarta.mail.Address[]::new);
    }

    private static String firstHeader(Part part, String name) {
        try {
            String[] values = part.getHeader(name);
            return values == null || values.length == 0 ? null : values[0];
        } catch (MessagingException ex) {
            return null;
        }
    }

    private static OffsetDateTime toOffset(Date date) {
        return date == null ? null : OffsetDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
    }

    private static Path uniqueTarget(Path dir, String name) throws java.io.IOException {
        Path target = dir.resolve(name);
        int suffix = 1;
        while (Files.exists(target)) {
            target = dir.resolve(suffix++ + "-" + name);
        }
        return target;
    }

    static String sanitize(String name) {
        String cleaned = UNSAFE_FILE_CHARS.matcher(name).replaceAll("_").trim();
        if (cleaned.isEmpty()) {
            cleaned = "attachment";
        }
        return cleaned.length() > 120 ? cleaned.substring(cleaned.length() - 120) : cleaned;
    }

    static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    private static void close(Store store) {
        if (store != null && store.isConnected()) {
            try {
                store.close();
            } catch (MessagingException ignored) {
                // 关闭失败忽略
            }
        }
    }

    /** 把 Jakarta Mail 的异常翻成人话。 */
    static MailException translate(String prefix, Exception ex) {
        return new MailException(prefix + "：" + humanMessage(ex));
    }

    static String humanMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            message = current.getClass().getSimpleName();
        }
        if (message.contains("timed out") || message.contains("Read timed out")) {
            return "连接超时（检查服务器地址/端口/网络，或公司网络是否限制了 IMAP/SMTP）";
        }
        if (message.contains("UnknownHostException") || message.contains("Name or service not known")) {
            return "域名解析失败（服务器地址写错了？）";
        }
        if (message.contains("Connection refused")) {
            return "端口未开放（服务器地址或端口不对，或被防火墙拦截）";
        }
        return message;
    }

    /** 邮件操作的业务异常（message 直接可展示）。 */
    public static class MailException extends RuntimeException {
        public MailException(String message) {
            super(message);
        }
    }
}
