package com.myagent.agui.connector.mail;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * 邮件正文/附件解析：真实邮箱结构千奇百怪，解析错就会让模型"看不到附件"或"读到 HTML 标签"。
 */
class MailMimeParserTest {

    private static MimeMessage message() {
        return new MimeMessage(Session.getInstance(new Properties()));
    }

    @Test
    void 纯文本正文直接可用() throws Exception {
        MimeMessage message = message();
        message.setText("你好，这是正文。");

        MailMimeParser.ParsedBody parsed = MailMimeParser.parse(message);

        assertThat(parsed.text()).isEqualTo("你好，这是正文。");
        assertThat(parsed.attachments()).isEmpty();
    }

    @Test
    void 只有HTML正文时转成纯文本() throws Exception {
        MimeMessage message = message();
        message.setContent("<html><body><p>第一段</p><p>第二段<br/>换行</p>"
                + "<script>alert(1)</script></body></html>", "text/html; charset=UTF-8");
        message.saveChanges();

        MailMimeParser.ParsedBody parsed = MailMimeParser.parse(message);

        assertThat(parsed.text()).contains("第一段", "第二段", "换行");
        assertThat(parsed.text()).doesNotContain("alert(1)", "<p>");
        assertThat(parsed.html()).isTrue();
    }

    @Test
    void 多部分嵌套_优先用纯文本并列出附件() throws Exception {
        MimeMessage message = message();
        MimeMultipart root = new MimeMultipart();
        MimeMultipart alternative = new MimeMultipart("alternative");

        MimeBodyPart plain = new MimeBodyPart();
        plain.setText("纯文本版本", "UTF-8");
        MimeBodyPart html = new MimeBodyPart();
        html.setContent("<b>HTML 版本</b>", "text/html; charset=UTF-8");
        alternative.addBodyPart(plain);
        alternative.addBodyPart(html);

        MimeBodyPart alternativePart = new MimeBodyPart();
        alternativePart.setContent(alternative);
        root.addBodyPart(alternativePart);

        MimeBodyPart attachment = new MimeBodyPart();
        attachment.setText("发票内容,1000");
        attachment.setFileName("invoice.csv");
        attachment.setDisposition(jakarta.mail.Part.ATTACHMENT);
        root.addBodyPart(attachment);

        message.setContent(root);
        message.saveChanges();

        MailMimeParser.ParsedBody parsed = MailMimeParser.parse(message);

        assertThat(parsed.text()).isEqualTo("纯文本版本");
        assertThat(parsed.attachments()).hasSize(1);
        assertThat(parsed.attachments().get(0).fileName()).isEqualTo("invoice.csv");
        assertThat(MailMimeParser.attachmentCount(message)).isEqualTo(1);
    }

    @Test
    void 内联图片也算附件_但标注inline() throws Exception {
        MimeMessage message = message();
        MimeMultipart root = new MimeMultipart("mixed");
        MimeBodyPart text = new MimeBodyPart();
        text.setText("见附图", "UTF-8");
        root.addBodyPart(text);

        MimeBodyPart image = new MimeBodyPart();
        image.setContent(new byte[] {1, 2, 3}, "image/png");
        image.setFileName("chart.png");
        image.setDisposition(jakarta.mail.Part.INLINE);
        root.addBodyPart(image);

        message.setContent(root);
        message.saveChanges();

        MailMimeParser.ParsedBody parsed = MailMimeParser.parse(message);

        assertThat(parsed.attachments()).hasSize(1);
        assertThat(parsed.attachments().get(0).inline()).isTrue();
        assertThat(parsed.attachments().get(0).fileName()).isEqualTo("chart.png");
    }

    @Test
    void 附件名带路径与编码时被清洗() {
        assertThat(MailMimeParser.decodeFileName("C:\\\\temp\\\\report.pdf")).isEqualTo("report.pdf");
        assertThat(MailMimeParser.decodeFileName("../../etc/passwd")).isEqualTo("passwd");
        assertThat(MailMimeParser.decodeFileName("=?UTF-8?B?5oql5ZGKLnhsc3g=?=")).isEqualTo("报告.xlsx");
        assertThat(MailMimeParser.decodeFileName(null)).isNull();
    }

    @Test
    void 发件人地址解析() throws Exception {
        assertThat(MailMimeParser.address(new jakarta.mail.Address[] {
                new InternetAddress("a@b.com", "张三"), new InternetAddress("c@d.com")}))
                .isEqualTo("a@b.com, c@d.com");
        assertThat(MailMimeParser.address(new jakarta.mail.Address[0])).isEmpty();
    }

    @Test
    void 取第N个附件_越界要报错() throws Exception {
        MimeMessage message = message();
        MimeMultipart root = new MimeMultipart("mixed");
        MimeBodyPart text = new MimeBodyPart();
        text.setText("正文", "UTF-8");
        root.addBodyPart(text);
        MimeBodyPart file = new MimeBodyPart();
        file.setContent(new byte[] {9}, "text/plain");
        file.setFileName("a.txt");
        file.setDisposition(jakarta.mail.Part.ATTACHMENT);
        root.addBodyPart(file);
        message.setContent(root);
        message.saveChanges();

        assertThat(MailMimeParser.attachmentAt(message, 0)).isNotNull();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> MailMimeParser.attachmentAt(message, 3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 邮件类型判定不会误把正文当附件() throws Exception {
        MimeMessage message = message();
        message.setContent("纯文本", "text/plain; charset=UTF-8");
        message.saveChanges();
        assertThat(MailMimeParser.attachmentCount(message)).isZero();
        assertThat(Message.RecipientType.TO).isNotNull();
    }
}
