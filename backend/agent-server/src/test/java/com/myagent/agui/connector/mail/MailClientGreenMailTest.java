package com.myagent.agui.connector.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 邮件收发**全链路**测试：用内存 IMAP/SMTP（GreenMail）跑真实协议，
 * 不依赖任何外部邮箱账号。
 *
 * <p>覆盖：列表 / 搜索 / 读正文 / 下载附件 / 标记 / 文件夹 / 发信 / 回复（带 In-Reply-To）/ 连通性探测。
 */
class MailClientGreenMailTest {

    private static final String USER = "me@example.com";
    private static final String PASSWORD = "secret";

    private static GreenMail greenMail;

    @BeforeAll
    static void start() {
        greenMail = new GreenMail(new ServerSetup[] {ServerSetupTest.SMTP, ServerSetupTest.IMAP});
        greenMail.start();
        greenMail.setUser(USER, USER, PASSWORD);
    }

    /** 每个用例一份干净邮箱：GreenMail 在 @BeforeAll 启动，邮件会跨用例累积。 */
    @org.junit.jupiter.api.BeforeEach
    void reset() throws Exception {
        greenMail.purgeEmailFromAllMailboxes();
    }

    @AfterAll
    static void stop() {
        if (greenMail != null) {
            greenMail.stop();
        }
    }

    private static Map<String, String> config(MailClientGreenMailTestContext context) {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("email", USER);
        config.put("displayName", "测试用户");
        config.put("imapHost", "127.0.0.1");
        config.put("imapPort", String.valueOf(greenMail.getImap().getPort()));
        config.put("imapSsl", "false");
        config.put("smtpHost", "127.0.0.1");
        config.put("smtpPort", String.valueOf(greenMail.getSmtp().getPort()));
        config.put("smtpSsl", "false");
        config.put("username", USER);
        config.put("password", PASSWORD);
        return config;
    }

    /** 只是个名字容器，避免测试方法签名里反复拼 map。 */
    private interface MailClientGreenMailTestContext {}

    private static MailClient client() {
        return new MailClient(MailSettings.of(config(new MailClientGreenMailTestContext() {})), 5000, 5000,
                5L * 1024 * 1024, 10L * 1024 * 1024, 40_000);
    }

    /** 往 GreenMail 里塞一封带附件的邮件（模拟"别人发给我的信"）。 */
    private static void deliverIncoming(String subject, String body, String attachmentName, String attachmentText)
            throws Exception {
        Session session = Session.getInstance(new Properties());
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress("boss@partner.com", "老板"));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(USER));
        message.setSubject(subject, "UTF-8");
        MimeMultipart multipart = new MimeMultipart("mixed");
        MimeBodyPart text = new MimeBodyPart();
        text.setText(body, "UTF-8");
        multipart.addBodyPart(text);
        if (attachmentName != null) {
            MimeBodyPart file = new MimeBodyPart();
            file.setContent(attachmentText.getBytes(java.nio.charset.StandardCharsets.UTF_8), "text/csv");
            file.setFileName(attachmentName);
            file.setDisposition(jakarta.mail.Part.ATTACHMENT);
            multipart.addBodyPart(file);
        }
        message.setContent(multipart);
        message.saveChanges();

        try (jakarta.mail.Transport transport = session.getTransport("smtp")) {
            transport.connect("127.0.0.1", greenMail.getSmtp().getPort(), null, null);
            transport.sendMessage(message, message.getAllRecipients());
        }
        greenMail.waitForIncomingEmail(5000, 1);
    }

    @Test
    void 探测成功_列表_读正文_下载附件() throws Exception {
        deliverIncoming("报价单", "请查收附件里的报价。", "quote.csv", "item,price\nA,100\n");

        MailClient client = client();
        assertThat(client.probe()).isNotEmpty();

        List<MailClient.MailSummary> list = client.list("INBOX", false, 7, 10);
        assertThat(list).hasSize(1);
        MailClient.MailSummary summary = list.get(0);
        assertThat(summary.subject()).isEqualTo("报价单");
        assertThat(summary.from()).contains("boss@partner.com");
        assertThat(summary.unread()).isTrue();
        assertThat(summary.hasAttachment()).isTrue();

        MailClient.MailDetail detail = client.read(summary.uid(), "INBOX");
        assertThat(detail.subject()).isEqualTo("报价单");
        assertThat(detail.body()).contains("请查收附件里的报价");
        assertThat(detail.attachments()).hasSize(1);
        assertThat(detail.attachments().get(0).fileName()).isEqualTo("quote.csv");
        assertThat(detail.messageId()).isNotBlank();
    }

    @Test
    void 附件能落到工作区目录(@TempDir Path tempDir) throws Exception {
        deliverIncoming("合同", "附件是合同扫描件。", "contract.txt", "合同正文");
        MailClient client = client();
        long uid = client.list("INBOX", false, 30, 10).get(0).uid();

        MailClient.SavedAttachment saved = client.saveAttachment(uid, 0, "INBOX", tempDir);

        assertThat(saved.fileName()).isEqualTo("contract.txt");
        assertThat(Files.exists(saved.path())).isTrue();
        assertThat(Files.readString(saved.path())).isEqualTo("合同正文");
        // 越界序号给出可操作错误
        assertThatThrownBy(() -> client.saveAttachment(uid, 5, "INBOX", tempDir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("附件序号超出范围");
    }

    @Test
    void 搜索_未读过滤_标记已读() throws Exception {
        deliverIncoming("测试搜索-发票", "发票正文", null, null);
        MailClient client = client();
        long uid = client.list("INBOX", false, 30, 10).get(0).uid();

        assertThat(client.search("INBOX", null, "发票", null, 30, false, 10)).isNotEmpty();
        assertThat(client.search("INBOX", "boss@partner.com", null, null, 30, false, 10)).isNotEmpty();
        assertThat(client.search("INBOX", null, "根本不存在的关键词", null, 30, false, 10)).isEmpty();

        client.mark(uid, "SEEN", null, "INBOX");
        assertThat(client.list("INBOX", false, 30, 10).get(0).unread()).isFalse();
        assertThat(client.list("INBOX", true, 30, 10)).isEmpty();
    }

    @Test
    void 发信成功_对方能收到且编码正确() throws Exception {
        MailClient client = client();

        String messageId = client.send(
                List.of("someone@partner.com"), List.of(), List.of(),
                "来自测试的邮件", "正文有中文，也有 emoji 🙂", false, List.of());

        assertThat(messageId).isNotBlank();
        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        jakarta.mail.internet.MimeMessage[] received = greenMail.getReceivedMessages();
        assertThat(received[received.length - 1].getSubject()).isEqualTo("来自测试的邮件");
        assertThat(String.valueOf(received[received.length - 1].getContent())).contains("中文");
    }

    @Test
    void 回复会带上InReplyTo并保持主题前缀() throws Exception {
        deliverIncoming("周报", "本周进展如下", null, null);
        MailClient client = client();
        MailClient.MailDetail detail = client.read(client.list("INBOX", false, 30, 10).get(0).uid(), "INBOX");

        client.reply(client.list("INBOX", false, 30, 10).get(0).uid(), "INBOX", "收到，我下午处理。", false, List.of());

        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        jakarta.mail.internet.MimeMessage[] received = greenMail.getReceivedMessages();
        jakarta.mail.internet.MimeMessage reply = received[received.length - 1];
        assertThat(reply.getSubject()).isEqualTo("Re: 周报");
        assertThat(reply.getHeader("In-Reply-To")).isNotEmpty();
        assertThat(reply.getHeader("In-Reply-To")[0]).isEqualTo(detail.messageId());
    }

    @Test
    void 自动回复带AutoSubmitted头_防止互相轰炸() throws Exception {
        deliverIncoming("自动回复防护", "正文", null, null);
        MailClient client = client();
        long uid = client.list("INBOX", false, 30, 10).get(0).uid();

        client.replyAuto(uid, "INBOX", "这是自动回复");

        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        jakarta.mail.internet.MimeMessage[] received = greenMail.getReceivedMessages();
        assertThat(received[received.length - 1].getHeader("Auto-Submitted")[0]).isEqualTo("auto-replied");
    }

    @Test
    void 入站拉取_只取游标之后的新邮件() throws Exception {
        deliverIncoming("第一封", "第一封正文", null, null);
        MailClient client = client();

        List<MailClient.InboundRaw> first = client.pollNew(0L, null, 20);
        assertThat(first).hasSize(1);
        MailClient.InboundRaw raw = first.get(0);
        assertThat(raw.subject()).isEqualTo("第一封");
        assertThat(raw.from()).contains("boss@partner.com");
        assertThat(raw.messageId()).isNotBlank();
        assertThat(raw.threadKey()).isNotBlank();
        assertThat(raw.autoSubmitted()).isFalse();
        assertThat(raw.text()).contains("第一封正文");

        // 游标推进后再拉：同一封不应该再出现（否则每轮都会重复处理）
        assertThat(client.pollNew(raw.uid(), raw.uidValidity(), 20)).isEmpty();

        deliverIncoming("第二封", "第二封正文", null, null);
        assertThat(client.pollNew(raw.uid(), raw.uidValidity(), 20))
                .anySatisfy(message -> assertThat(message.subject()).isEqualTo("第二封"));
    }

    @Test
    void 入站拉取能识别对方是自动回复() throws Exception {
        Session session = Session.getInstance(new Properties());
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress("auto@partner.com"));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(USER));
        message.setSubject("自动回复");
        message.setText("我不在办公室", "UTF-8");
        message.setHeader("Auto-Submitted", "auto-replied");
        message.saveChanges();
        try (jakarta.mail.Transport transport = session.getTransport("smtp")) {
            transport.connect("127.0.0.1", greenMail.getSmtp().getPort(), null, null);
            transport.sendMessage(message, message.getAllRecipients());
        }
        greenMail.waitForIncomingEmail(5000, 1);

        assertThat(client().pollNew(0L, null, 20))
                .anySatisfy(raw -> assertThat(raw.autoSubmitted()).isTrue());
    }

    @Test
    void 文件夹清单包含收件箱() {
        assertThat(client().folders()).anySatisfy(name -> assertThat(name).containsIgnoringCase("INBOX"));
    }

    @Test
    void 密码错误时给的是人话() {
        Map<String, String> config = config(new MailClientGreenMailTestContext() {});
        config.put("password", "wrong-password");
        MailClient client = new MailClient(MailSettings.of(config), 5000, 5000,
                1024 * 1024, 1024 * 1024, 10_000);

        assertThatThrownBy(client::probe)
                .isInstanceOf(MailClient.MailException.class)
                .hasMessageContaining("登录失败");
    }
}
