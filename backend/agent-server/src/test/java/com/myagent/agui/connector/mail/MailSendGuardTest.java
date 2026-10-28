package com.myagent.agui.connector.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.myagent.common.BizException;
import com.myagent.connectorconfig.ConnectorRecord;
import com.myagent.connectorconfig.ConnectorService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 发信护栏：发信不可撤销，这几条规则必须先在单测里死掉。 */
class MailSendGuardTest {

    private final ConnectorService connectorService = mock(ConnectorService.class);

    private static ConnectorRecord record() {
        return new ConnectorRecord(1L, "MAIL", "工作邮箱", null, 9L, "enc", true, "OK", null, null,
                "OFF", null, 0L, OffsetDateTime.now(), OffsetDateTime.now());
    }

    private MailSendGuard guard(Map<String, String> config) {
        MailSettings settings = MailSettings.of(config);
        return new MailSendGuard(settings, connectorService, record(), 9L,
                (relative, userId) -> java.nio.file.Path.of(relative));
    }

    private static Map<String, String> config() {
        Map<String, String> config = new java.util.LinkedHashMap<>();
        config.put("email", "me@company.com");
        config.put("imapHost", "imap.company.com");
        config.put("imapPort", "993");
        config.put("smtpHost", "smtp.company.com");
        config.put("smtpPort", "465");
        config.put("username", "me@company.com");
        config.put("password", "x");
        return config;
    }

    @Test
    void 关闭发信时直接拒绝_并告诉用户去哪里打开() {
        Map<String, String> config = config();
        config.put("allowSend", "false");

        String rejection = guard(config).check(List.of("a@b.com"), List.of(), List.of());

        assertThat(rejection).contains("允许发信").contains("能力 → 连接器");
    }

    @Test
    void 不能发给自己_防自动回复死循环() {
        String rejection = guard(config()).check(List.of("ME@company.com"), List.of(), List.of());
        assertThat(rejection).contains("不能发给自己");
    }

    @Test
    void 白名单外的收件人被拒绝_抄送密送也算() {
        Map<String, String> config = config();
        config.put("allowedRecipients", "boss@company.com");
        MailSendGuard guard = guard(config);

        assertThat(guard.check(List.of("boss@company.com"), List.of(), List.of())).isNull();
        assertThat(guard.check(List.of("boss@company.com"), List.of("x@other.com"), List.of()))
                .contains("不在这个邮箱的发信白名单里");
        assertThat(guard.check(List.of("boss@company.com"), List.of(), List.of("y@other.com")))
                .contains("白名单");
    }

    @Test
    void 没填收件人_地址非法_都被挡住() {
        assertThat(guard(config()).check(List.of(), List.of(), List.of())).contains("没有收件人");
        assertThat(guard(config()).check(List.of("不是邮箱"), List.of(), List.of())).contains("不是有效邮箱");
    }

    @Test
    void 带显示名的收件人地址也能正确解析() {
        assertThat(guard(config()).check(List.of("张三 <zhangsan@company.com>"), List.of(), List.of()))
                .isNull();
    }

    @Test
    void 超出每小时上限时工具给出可读错误() {
        doThrow(BizException.badRequest("该邮箱本小时已发送 60 封")).when(connectorService).assertSendQuota(anyLong());

        assertThatThrownBy(() -> guard(config()).check(List.of("a@b.com"), List.of(), List.of()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("本小时已发送");
    }

    @Test
    void 审计会写到连接器上() {
        guard(config()).record("SEND", List.of("a@b.com"), "主题", true, null);

        verify(connectorService).logAction(1L, 9L, "SEND", "a@b.com", "主题", true, null);
    }

    @Test
    void 附件路径必须存在() {
        assertThatThrownBy(() -> guard(config()).resolveAttachments(List.of("/no/such/file.pdf"), 9L))
                .isInstanceOf(MailClient.MailException.class)
                .hasMessageContaining("附件不存在");
    }
}
