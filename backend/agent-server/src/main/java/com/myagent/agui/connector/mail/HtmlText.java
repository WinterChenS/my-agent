package com.myagent.agui.connector.mail;

/**
 * HTML → 纯文本（邮件只有 HTML 正文时用）。
 *
 * <p>故意不引第三方库：只需要"让模型能读懂"，不需要完美还原。
 * 处理：去掉 {@code <script>/<style>} 整块、{@code <br>/<p>/<div>/<tr>/<li>} 转换行、
 * 去掉其余标签、解码常见实体、压缩空行。
 */
public final class HtmlText {

    private HtmlText() {
        // 工具类
    }

    public static String toPlain(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String text = html
                .replaceAll("(?is)<(script|style|head)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|tr|li|h[1-6]|blockquote|table)>", "\n")
                .replaceAll("(?i)<li[^>]*>", "- ")
                .replaceAll("(?s)<!--.*?-->", " ")
                .replaceAll("(?s)<[^>]+>", " ");
        text = decodeEntities(text);
        // 压缩连续空白（保留换行结构，模型读起来才不乱）
        StringBuilder out = new StringBuilder(text.length());
        boolean lastWasSpace = false;
        int newlines = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\n') {
                if (newlines < 2) {
                    out.append('\n');
                }
                newlines++;
                lastWasSpace = true;
                continue;
            }
            newlines = 0;
            if (ch == ' ' || ch == '\t' || ch == '\r' || ch == '\u00a0') {
                if (!lastWasSpace) {
                    out.append(' ');
                    lastWasSpace = true;
                }
                continue;
            }
            out.append(ch);
            lastWasSpace = false;
        }
        return out.toString().strip();
    }

    static String decodeEntities(String text) {
        return text
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&mdash;", "—")
                .replace("&ndash;", "–")
                .replace("&hellip;", "…")
                .replace("&rsquo;", "’")
                .replace("&lsquo;", "‘")
                .replace("&ldquo;", "“")
                .replace("&rdquo;", "”");
    }
}
