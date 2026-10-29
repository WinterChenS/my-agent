package com.myagent.connectorconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.myagent.common.BizException;
import com.myagent.common.CryptoService;
import com.myagent.connectorconfig.dto.ConnectorRequest;
import com.myagent.connectorconfig.dto.ConnectorResponse;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** 连接器配置：凭据加密、掩码、属主校验、必填校验、入站模式校验。 */
class ConnectorServiceTest {

    private final ConnectorStore store = mock(ConnectorStore.class);
    private final CryptoService crypto = new CryptoService("unit-test-key");
    private final ConnectorTypeRegistry registry = new ConnectorTypeRegistry();
    private ConnectorService service;

    @BeforeEach
    void setUp() {
        ObjectProvider<ConnectorProbePort> probes =
                new StaticListableBeanFactory().getBeanProvider(ConnectorProbePort.class);
        service = new ConnectorService(store, registry, crypto, probes, 60);
        when(store.agentIdsOf(anyLong())).thenReturn(List.of());
    }

    /** 完整配置（更新/读取测试要能通过必填校验与解密）。 */
    private static String fullConfigJson() {
        return "{\"email\":\"me@company.com\",\"imapHost\":\"imap.company.com\",\"imapPort\":\"993\","
                + "\"smtpHost\":\"smtp.company.com\",\"smtpPort\":\"465\",\"username\":\"me@company.com\","
                + "\"password\":\"old\"}";
    }

    private static Map<String, String> mailConfig() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("email", "me@company.com");
        config.put("imapHost", "imap.company.com");
        config.put("imapPort", "993");
        config.put("smtpHost", "smtp.company.com");
        config.put("smtpPort", "465");
        config.put("username", "me@company.com");
        config.put("password", "授权码");
        return config;
    }

    private ConnectorRecord record(String configEnc, long owner) {
        return new ConnectorRecord(1L, "MAIL", "工作邮箱", null, owner, configEnc, true, "UNKNOWN", null,
                null, "OFF", null, 0L, OffsetDateTime.now(), OffsetDateTime.now());
    }

    @Test
    void 创建时凭据加密入库_响应里只有掩码() {
        when(store.insert(anyString(), anyString(), any(), anyLong(), anyString(), anyBoolean(), anyString()))
                .thenReturn(1L);
        ConnectorRecord saved = record(crypto.encrypt("{\"email\":\"me@company.com\",\"password\":\"授权码\"}"), 9L);
        when(store.find(1L)).thenReturn(Optional.of(saved));

        ConnectorResponse response = service.create(
                new ConnectorRequest("MAIL", "工作邮箱", null, true, mailConfig(), "OFF", List.of(3L)), 9L);

        ArgumentCaptor<String> enc = ArgumentCaptor.forClass(String.class);
        verify(store).insert(eq("MAIL"), eq("工作邮箱"), eq(null), eq(9L), enc.capture(), eq(true), eq("OFF"));
        assertThat(enc.getValue()).doesNotContain("授权码");
        assertThat(crypto.decrypt(enc.getValue())).contains("授权码");

        assertThat(response.config().get("password")).isEqualTo("***");
        assertThat(response.config().get("email")).isEqualTo("me@company.com");
        assertThat(response.account()).isEqualTo("me@company.com");
        assertThat(response.capabilities()).contains("READ", "SEND", "INBOUND");
    }

    @Test
    void 缺少必填项时字段名要说人话() {
        Map<String, String> config = mailConfig();
        config.remove("password");

        assertThatThrownBy(() -> service.create(
                new ConnectorRequest("MAIL", "工作邮箱", null, true, config, "OFF", null), 9L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("密码 / 授权码");
    }

    @Test
    void 更新时掩码不覆盖真实值_空串表示清空非敏感项() {
        String enc = crypto.encrypt(fullConfigJson().replace("\"old\"", "\"old\",\"displayName\":\"旧名\""));
        when(store.find(1L)).thenReturn(Optional.of(record(enc, 9L)));

        Map<String, String> incoming = new LinkedHashMap<>();
        incoming.put("password", "***");
        incoming.put("displayName", "");

        service.update(1L, new ConnectorRequest("MAIL", null, null, null, incoming, null, null), 9L, false);

        ArgumentCaptor<String> updated = ArgumentCaptor.forClass(String.class);
        verify(store).update(eq(1L), eq("工作邮箱"), eq(null), updated.capture(), eq(true), eq("OFF"), eq(true));
        String plain = crypto.decrypt(updated.getValue());
        assertThat(plain).contains("\"password\":\"old\"").doesNotContain("旧名");
    }

    @Test
    void 只改开关不会把探测状态打回未测试() {
        String enc = crypto.encrypt(fullConfigJson());
        when(store.find(1L)).thenReturn(Optional.of(record(enc, 9L)));

        service.update(1L, new ConnectorRequest("MAIL", null, null, false, null, null, null), 9L, false);

        verify(store).update(eq(1L), eq("工作邮箱"), eq(null), anyString(), eq(false), eq("OFF"), eq(false));
    }

    @Test
    void 改配置会把探测状态打回未测试() {
        String enc = crypto.encrypt(fullConfigJson());
        when(store.find(1L)).thenReturn(Optional.of(record(enc, 9L)));
        Map<String, String> incoming = new LinkedHashMap<>();
        incoming.put("imapHost", "imap.new-host.com");

        service.update(1L, new ConnectorRequest("MAIL", null, null, null, incoming, null, null), 9L, false);

        verify(store).update(eq(1L), eq("工作邮箱"), eq(null), anyString(), eq(true), eq("OFF"), eq(true));
    }

    @Test
    void 不能操作别人的连接器() {
        when(store.find(1L)).thenReturn(Optional.of(record(crypto.encrypt(fullConfigJson()), 8L)));

        assertThatThrownBy(() -> service.get(1L, 9L, false))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只能操作自己的连接器");
        assertThat(service.get(1L, 9L, true)).isNotNull();
    }

    @Test
    void 别名格式非法直接拒绝() {
        assertThatThrownBy(() -> ConnectorService.normalizeAlias("Work Mail"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("别名");
        assertThat(ConnectorService.normalizeAlias("Work")).isEqualTo("work");
        assertThat(ConnectorService.normalizeAlias("")).isNull();
    }

    @Test
    void 入站模式只允许三种取值() {
        ConnectorTypeDescriptor descriptor = registry.require("MAIL");
        assertThat(ConnectorService.normalizeInboundMode("notify", descriptor)).isEqualTo("NOTIFY");
        assertThat(ConnectorService.normalizeInboundMode(null, descriptor)).isEqualTo("OFF");
        assertThatThrownBy(() -> ConnectorService.normalizeInboundMode("AUTO", descriptor))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不支持的入站模式");
    }

    @Test
    void 每小时发信上限按审计记录计数() {
        when(store.countActionsSince(eq(1L), eq("SEND"), any())).thenReturn(40);
        when(store.countActionsSince(eq(1L), eq("REPLY"), any())).thenReturn(20);

        assertThatThrownBy(() -> service.assertSendQuota(1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("达到上限");

        when(store.countActionsSince(eq(1L), eq("REPLY"), any())).thenReturn(0);
        service.assertSendQuota(1L);
    }

    @Test
    void 改入站模式会清掉旧游标_下次轮询重新打基线() {
        String enc = crypto.encrypt(fullConfigJson());
        ConnectorRecord off = record(enc, 9L);
        when(store.find(1L)).thenReturn(Optional.of(off));

        service.update(1L, new ConnectorRequest("MAIL", null, null, null, null, "NOTIFY", null), 9L, false);

        // 不清游标的话，一开启就把从上次游标到现在的历史未读邮件全推一遍（用户实测踩过）
        verify(store).resetInboundCursor(1L);
    }

    @Test
    void 入站模式没变时不动游标() {
        String enc = crypto.encrypt(fullConfigJson());
        ConnectorRecord notify = new ConnectorRecord(1L, "MAIL", "工作邮箱", null, 9L, enc, true, "OK", null,
                null, "NOTIFY", 7L, 12610L, OffsetDateTime.now(), OffsetDateTime.now());
        when(store.find(1L)).thenReturn(Optional.of(notify));

        service.update(1L, new ConnectorRequest("MAIL", null, null, null, null, "NOTIFY", null), 9L, false);

        verify(store, never()).resetInboundCursor(anyLong());
    }

    @Test
    void 端口必须是数字_枚举必须在下拉候选里() {
        Map<String, String> config = mailConfig();
        config.put("imapPort", "abc");
        assertThatThrownBy(() -> service.create(
                new ConnectorRequest("MAIL", "工作邮箱", null, true, config, "OFF", null), 9L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("IMAP 端口")
                .hasMessageContaining("必须是数字");

        Map<String, String> badSsl = mailConfig();
        badSsl.put("imapSsl", "yes");
        assertThatThrownBy(() -> service.create(
                new ConnectorRequest("MAIL", "工作邮箱", null, true, badSsl, "OFF", null), 9L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("IMAP 使用 SSL")
                .hasMessageContaining("只能选");

        // 合法值能通过（可选字段不填也不报错）
        Map<String, String> ok = mailConfig();
        ok.put("imapSsl", "false");
        ok.put("smtpPort", "587");
        when(store.insert(anyString(), anyString(), any(), anyLong(), anyString(), anyBoolean(), anyString()))
                .thenReturn(2L);
        when(store.find(2L)).thenReturn(Optional.of(record(crypto.encrypt(fullConfigJson()), 9L)));
        assertThat(service.create(
                new ConnectorRequest("MAIL", "工作邮箱", null, true, ok, "OFF", null), 9L)).isNotNull();
    }

    @Test
    void 白名单项必须是邮箱或域名_写错了保存时就报() {
        Map<String, String> config = mailConfig();
        config.put("allowedRecipients", "老板, boss@company.com");
        assertThatThrownBy(() -> service.create(
                new ConnectorRequest("MAIL", "工作邮箱", null, true, config, "OFF", null), 9L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("发信白名单")
                .hasMessageContaining("老板");

        Map<String, String> ok = mailConfig();
        ok.put("allowedRecipients", "@company.com, boss@partner.com");
        ok.put("allowedAutoReplySenders", "@partner.com");
        when(store.insert(anyString(), anyString(), any(), anyLong(), anyString(), anyBoolean(), anyString()))
                .thenReturn(3L);
        when(store.find(3L)).thenReturn(Optional.of(record(crypto.encrypt(fullConfigJson()), 9L)));
        assertThat(service.create(
                new ConnectorRequest("MAIL", "工作邮箱", null, true, ok, "OFF", null), 9L)).isNotNull();
    }

    @Test
    void 类型描述的字段都声明了控件类型() {
        ConnectorTypeDescriptor descriptor = registry.require("MAIL");
        assertThat(descriptor.fields()).allSatisfy(field ->
                assertThat(field.type()).isNotNull());
        // 枚举项必须是下拉，且带中文标签（否则又变成让用户手打 true/OFF）
        assertThat(descriptor.fields()).filteredOn(ConnectorTypeDescriptor.Field::isSelect)
                .isNotEmpty()
                .allSatisfy(field -> assertThat(field.options()).isNotEmpty());
        // 端口是数字输入
        assertThat(descriptor.fields()).filteredOn(field -> field.key().endsWith("Port"))
                .allSatisfy(field -> assertThat(field.type())
                        .isEqualTo(ConnectorTypeDescriptor.FieldType.NUMBER));
    }

    @Test
    void 不支持的连接器类型给出可选清单() {
        assertThatThrownBy(() -> service.create(
                new ConnectorRequest("SLACK", "x", null, true, Map.of(), "OFF", null), 9L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("MAIL");
        verify(store, never()).insert(anyString(), anyString(), any(), anyLong(), anyString(), anyBoolean(), anyString());
    }
}
