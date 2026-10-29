package com.myagent.agui.connector.mail;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 邮件连接器的配置（从 {@code connector.config_enc} 解密后的键值解析而来）。
 *
 * <p>只做"读配置 + 给默认值"，不做校验（校验在 {@code ConnectorTypeRegistry} 的必填键里）。
 *
 * @param email 邮箱地址（也是发件人）
 * @param allowSend 是否允许 Agent 发信
 * @param allowedRecipients 发信白名单（空=不限制；支持 {@code @域.com} 允许整域）
 * @param autoReplySenders 自动回复白名单（空=对所有人回；同样支持 {@code @域.com}）
 */
public record MailSettings(
        String email,
        String displayName,
        String imapHost,
        int imapPort,
        boolean imapSsl,
        String smtpHost,
        int smtpPort,
        boolean smtpSsl,
        String username,
        String password,
        boolean allowSend,
        List<String> allowedRecipients,
        List<String> autoReplySenders,
        String inboundMode) {

    public static MailSettings of(Map<String, String> config) {
        return new MailSettings(
                trim(config.get("email")),
                trim(config.get("displayName")),
                trim(config.get("imapHost")),
                port(config.get("imapPort"), 993),
                bool(config.get("imapSsl"), true),
                trim(config.get("smtpHost")),
                port(config.get("smtpPort"), 465),
                bool(config.get("smtpSsl"), true),
                valueOr(config.get("username"), trim(config.get("email"))),
                config.get("password") == null ? "" : config.get("password"),
                bool(config.get("allowSend"), true),
                split(config.get("allowedRecipients")),
                split(config.get("allowedAutoReplySenders")),
                valueOr(config.get("inboundMode"), "OFF").toUpperCase(Locale.ROOT));
    }

    /** 发件人显示：{@code 显示名 <邮箱>}。 */
    public String fromHeader() {
        return displayName == null || displayName.isBlank()
                ? email
                : displayName + " <" + email + ">";
    }

    /** 发信白名单判定：空=放行；否则要命中完整地址或域名后缀。 */
    public boolean allowsRecipient(String address) {
        return matches(allowedRecipients, address);
    }

    /** 自动回复白名单判定：空=对所有人自动回复。 */
    public boolean allowsAutoReplySender(String address) {
        return matches(autoReplySenders, address);
    }

    /** 规则匹配：完整地址精确命中，或以 {@code @域} 命中域名（大小写不敏感）。 */
    static boolean matches(List<String> rules, String address) {
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        String target = address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
        if (target.isEmpty()) {
            return false;
        }
        int at = target.lastIndexOf('@');
        String domain = at < 0 ? "" : target.substring(at + 1);
        return rules.stream().anyMatch(rule -> {
            String normalized = rule == null ? "" : rule.trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty()) {
                return false;
            }
            if (normalized.startsWith("@")) {
                return !domain.isEmpty() && domain.equals(normalized.substring(1));
            }
            return normalized.equals(target);
        });
    }

    private static List<String> split(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split("[,;\\s]+"))
                .map(String::trim)
                .filter(text -> !text.isEmpty())
                .toList();
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int port(String value, int fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static boolean bool(String value, boolean fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized);
    }
}
