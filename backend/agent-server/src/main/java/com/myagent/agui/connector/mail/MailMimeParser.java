package com.myagent.agui.connector.mail;

import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.InternetAddress;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 邮件正文/附件的解析（Jakarta Mail 的 {@code Multipart} 递归）。
 *
 * <p>为什么单独成类：邮件结构千奇百怪（嵌套 multipart、只有 HTML 没有纯文本、内联图片、
 * 附件名乱码），这些规则必须能单测——直接塞进工具里就只能靠真邮箱试。
 */
public final class MailMimeParser {

    /** 正文解析结果。 */
    public record ParsedBody(String text, boolean html, List<AttachmentMeta> attachments) {}

    /**
     * 一个附件（内联图片也算，{@code inline=true}）。
     *
     * @param index 在该封邮件里的序号（下载时定位用）
     */
    public record AttachmentMeta(int index, String fileName, long size, boolean inline, String contentType) {}

    /** 换行符常量（避免源码里的转义被工具链吃掉）。 */
    private static final String NEWLINE = System.lineSeparator();

    private MailMimeParser() {
        // 工具类
    }

    /** 解析正文与附件清单（**不下载内容**，只读结构）。 */
    public static ParsedBody parse(Part part) throws MessagingException, IOException {
        StringBuilder text = new StringBuilder();
        StringBuilder html = new StringBuilder();
        List<AttachmentMeta> attachments = new ArrayList<>();
        walk(part, text, html, attachments, 0);
        String body = text.length() > 0 ? text.toString() : HtmlText.toPlain(html.toString());
        return new ParsedBody(body.strip(), html.length() > 0 && text.length() == 0, attachments);
    }

    private static void walk(Part part, StringBuilder text, StringBuilder html,
            List<AttachmentMeta> attachments, int depth) throws MessagingException, IOException {
        if (depth > 12) {
            return;
        }
        Object content;
        try {
            content = part.getContent();
        } catch (Exception ex) {
            // 有些邮件某一段损坏（非法编码）：跳过这一段，别让整封邮件读不出来
            return;
        }
        // 先看结构：MimeMultipart 一定是"容器"，递归下去（不要靠 getContentType 判断，
        // 某些客户端发来的 multipart 类型头不规范，靠类型头会漏掉整段内容）
        if (content instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                walk(multipart.getBodyPart(i), text, html, attachments, depth + 1);
            }
            return;
        }
        String disposition = disposition(part);
        String fileName = part.getFileName();
        boolean attachment = "attachment".equals(disposition)
                || (fileName != null && !fileName.isBlank());
        if (attachment) {
            String name = decodeFileName(fileName);
            attachments.add(new AttachmentMeta(
                    attachments.size(),
                    name == null || name.isBlank() ? "attachment-" + (attachments.size() + 1) : name,
                    Math.max(part.getSize(), 0),
                    "inline".equals(disposition),
                    safeContentType(part)));
            return;
        }
        String contentType = safeContentType(part);
        if (content instanceof String string) {
            if (contentType.contains("text/html") || (contentType.isBlank() && looksLikeHtml(string))) {
                html.append(string).append(NEWLINE);
            } else {
                text.append(string).append(NEWLINE);
            }
        }
    }

    private static boolean looksLikeHtml(String value) {
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("<html") || lower.contains("<body") || lower.contains("<p>");
    }

    private static void collectAttachments(Part part, List<Part> out, int depth) {
        if (depth > 12) {
            return;
        }
        Object content;
        try {
            content = part.getContent();
        } catch (Exception ex) {
            return;
        }
        if (content instanceof Multipart multipart) {
            try {
                for (int i = 0; i < multipart.getCount(); i++) {
                    collectAttachments(multipart.getBodyPart(i), out, depth + 1);
                }
            } catch (MessagingException ex) {
                return;
            }
            return;
        }
        String fileName;
        try {
            fileName = part.getFileName();
        } catch (MessagingException ex) {
            return;
        }
        if ("attachment".equals(disposition(part)) || (fileName != null && !fileName.isBlank())) {
            out.add(part);
        }
    }

    /** 取第 index 个附件（与 {@link ParsedBody#attachments()} 顺序一致）。 */
    public static Part attachmentAt(Part part, int index) throws MessagingException, IOException {
        List<Part> parts = new ArrayList<>();
        collectAttachments(part, parts, 0);
        if (index < 0 || index >= parts.size()) {
            throw new IllegalArgumentException("附件序号超出范围：0.." + Math.max(parts.size() - 1, 0));
        }
        return parts.get(index);
    }

    /** 附件个数（工具在做范围校验前先数一下）。 */
    public static int attachmentCount(Part part) throws MessagingException, IOException {
        List<Part> parts = new ArrayList<>();
        collectAttachments(part, parts, 0);
        return parts.size();
    }

    private static boolean isMultipart(Part part) {
        try {
            return part.isMimeType("multipart/*");
        } catch (MessagingException ex) {
            return false;
        }
    }

    private static String disposition(Part part) {
        try {
            String disposition = part.getDisposition();
            return disposition == null ? "" : disposition.toLowerCase(java.util.Locale.ROOT);
        } catch (MessagingException ex) {
            return "";
        }
    }

    private static String safeContentType(Part part) {
        try {
            String contentType = part.getContentType();
            return contentType == null ? "" : contentType.toLowerCase(java.util.Locale.ROOT);
        } catch (MessagingException ex) {
            return "";
        }
    }

    /** 附件名可能被编码成 RFC2047（=?UTF-8?...?=），也可能带路径，统一清洗。 */
    static String decodeFileName(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            String decoded = jakarta.mail.internet.MimeUtility.decodeText(raw);
            return decoded.replace('\\', '/')
                    .substring(decoded.replace('\\', '/').lastIndexOf('/') + 1)
                    .replaceAll("[\\r\\n\\t]", "")
                    .trim();
        } catch (Exception ex) {
            return raw.replaceAll("[\\\\/\\r\\n\\t]", "_").trim();
        }
    }

    /** 邮件地址解析成 {@code a@b.com}（解析失败返回原串）。 */
    static String address(Address[] addresses) {
        if (addresses == null || addresses.length == 0) {
            return "";
        }
        List<String> result = new ArrayList<>();
        for (Address address : addresses) {
            if (address instanceof InternetAddress internet && internet.getAddress() != null) {
                result.add(internet.getAddress());
            } else {
                result.add(String.valueOf(address));
            }
        }
        return String.join(", ", result);
    }
}