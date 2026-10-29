package com.myagent.agui.connector.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** 邮件配置解析与发信白名单。 */
class MailSettingsTest {

    private static Map<String, String> config() {
        return new java.util.LinkedHashMap<>(Map.of(
                "email", "me@company.com",
                "displayName", "张三",
                "imapHost", "imap.company.com",
                "imapPort", "993",
                "smtpHost", "smtp.company.com",
                "smtpPort", "465",
                "username", "me@company.com",
                "password", "secret"));
    }

    @Test
    void 默认值_SSL开启_允许发信_白名单为空表示不限制() {
        MailSettings settings = MailSettings.of(config());

        assertThat(settings.imapPort()).isEqualTo(993);
        assertThat(settings.imapSsl()).isTrue();
        assertThat(settings.smtpSsl()).isTrue();
        assertThat(settings.allowSend()).isTrue();
        assertThat(settings.allowedRecipients()).isEmpty();
        assertThat(settings.allowsRecipient("anyone@anywhere.com")).isTrue();
        assertThat(settings.fromHeader()).isEqualTo("张三 <me@company.com>");
    }

    @Test
    void 端口写错时回退默认值_不让保存直接炸() {
        Map<String, String> broken = config();
        broken.put("imapPort", "abc");
        broken.put("smtpPort", "");
        MailSettings settings = MailSettings.of(broken);
        assertThat(settings.imapPort()).isEqualTo(993);
        assertThat(settings.smtpPort()).isEqualTo(465);
    }

    @Test
    void 白名单_支持完整地址与整域() {
        Map<String, String> withList = config();
        withList.put("allowedRecipients", "boss@company.com, @partner.com");
        MailSettings settings = MailSettings.of(withList);

        assertThat(settings.allowsRecipient("boss@company.com")).isTrue();
        assertThat(settings.allowsRecipient("anyone@partner.com")).isTrue();
        assertThat(settings.allowsRecipient("boss@other.com")).isFalse();
        assertThat(settings.allowsRecipient("stranger@partner.com.evil.com")).isFalse();
    }

    @Test
    void 自动回复白名单_空表示都回_支持整域() {
        MailSettings open = MailSettings.of(config());
        assertThat(open.autoReplySenders()).isEmpty();
        assertThat(open.allowsAutoReplySender("anyone@anywhere.com")).isTrue();

        Map<String, String> withList = config();
        withList.put("allowedAutoReplySenders", "boss@company.com, @partner.com");
        MailSettings limited = MailSettings.of(withList);

        assertThat(limited.allowsAutoReplySender("boss@company.com")).isTrue();
        assertThat(limited.allowsAutoReplySender("anyone@partner.com")).isTrue();
        assertThat(limited.allowsAutoReplySender("stranger@other.com")).isFalse();
    }

    @Test
    void 关闭发信与用户名回退到邮箱() {
        Map<String, String> off = config();
        off.put("allowSend", "false");
        off.remove("username");
        MailSettings settings = MailSettings.of(off);

        assertThat(settings.allowSend()).isFalse();
        assertThat(settings.username()).isEqualTo("me@company.com");
    }

    @Test
    void 显示名留空时用邮箱做发件人() {
        Map<String, String> noName = config();
        noName.remove("displayName");
        assertThat(MailSettings.of(noName).fromHeader()).isEqualTo("me@company.com");
    }
}
