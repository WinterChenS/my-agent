package com.myagent.agui.connector.notion;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Notion 连接器的配置（从 {@code connector.config_enc} 解密后的键值解析）。
 *
 * <p>凭据模型（两种都支持，都是 Bearer token）：
 * <ul>
 *   <li><b>个人访问令牌 PAT</b>：以创建者本人的身份工作，**不需要**给页面「添加连接」——推荐；</li>
 *   <li><b>内部集成令牌</b>：机器人身份，需要把页面共享给集成。</li>
 * </ul>
 *
 * @param allowWrite 是否允许写入（新建/追加/改属性）；关闭时写工具直接拒绝
 * @param allowedPageIds 页面白名单（空=不限）
 * @param allowedDataSources 数据库白名单（空=不限）
 */
public record NotionSettings(
        String token,
        String workspaceName,
        String defaultPageId,
        int maxBlocksPerPage,
        boolean allowWrite,
        List<String> allowedPageIds,
        List<String> allowedDataSources) {

    public static final int DEFAULT_MAX_BLOCKS = 200;

    public static NotionSettings of(Map<String, String> config) {
        return new NotionSettings(
                config.get("token") == null ? "" : config.get("token").trim(),
                trim(config.get("workspaceName")),
                NotionIds.normalizeId(config.get("defaultPageId")),
                positiveInt(config.get("maxBlocksPerPage"), DEFAULT_MAX_BLOCKS),
                !"false".equalsIgnoreCase(trim(config.get("allowWrite"))),
                split(config.get("allowedPageIds")),
                split(config.get("allowedDataSources")));
    }

    /** 页面白名单判定（空=不限）。传入的 id 会先归一化，所以链接/带连字符都能比。 */
    public boolean allowsPage(String rawId) {
        if (allowedPageIds.isEmpty()) {
            return true;
        }
        String id = NotionIds.normalizeId(rawId);
        return id != null && allowedPageIds.stream()
                .map(NotionIds::normalizeId)
                .anyMatch(candidate -> candidate != null && candidate.equalsIgnoreCase(id));
    }

    /** 数据库（data source）白名单判定（空=不限）。 */
    public boolean allowsDataSource(String rawId) {
        if (allowedDataSources.isEmpty()) {
            return true;
        }
        String id = NotionIds.normalizeId(rawId);
        return id != null && allowedDataSources.stream()
                .map(NotionIds::normalizeId)
                .anyMatch(candidate -> candidate != null && candidate.equalsIgnoreCase(id));
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static int positiveInt(String value, int fallback) {
        try {
            int parsed = value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
            return parsed <= 0 ? fallback : Math.min(parsed, 1000);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static List<String> split(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split("[,;，；\\s]+"))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .map(item -> item.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    /**
     * Notion id 归一化：用户经常直接粘页面链接。
     *
     * <p>支持：
     * <ul>
     *   <li>{@code 2f1b8c9d4e5f4a1b8c9d4e5f4a1b8c9d}（32 位裸 id）</li>
     *   <li>{@code 2f1b8c9d-4e5f-4a1b-8c9d-4e5f4a1b8c9d}（标准 uuid）</li>
     *   <li>{@code https://www.notion.so/Workspace/Some-Title-2f1b8c9d4e5f4a1b8c9d4e5f4a1b8c9d?pvs=4}（链接）</li>
     * </ul>
     */
    public static final class NotionIds {

        private static final Pattern UUID_LIKE =
                Pattern.compile("([0-9a-fA-F]{32}|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");

        private NotionIds() {
        }

        public static String normalizeId(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String value = raw.trim();
            // 链接里取最后一段（可能带 ?pvs= / #anchor）
            int query = value.indexOf('?');
            if (query > 0) {
                value = value.substring(0, query);
            }
            value = value.replaceAll("/+$", "");
            int slash = value.lastIndexOf('/');
            if (slash >= 0) {
                value = value.substring(slash + 1);
            }
            Matcher matcher = UUID_LIKE.matcher(value);
            if (matcher.find()) {
                String id = matcher.group(1).replace("-", "").toLowerCase(Locale.ROOT);
                return id.length() == 32 ? withDashes(id) : null;
            }
            Matcher anywhere = UUID_LIKE.matcher(raw);
            if (anywhere.find()) {
                String id = anywhere.group(1).replace("-", "").toLowerCase(Locale.ROOT);
                return id.length() == 32 ? withDashes(id) : null;
            }
            return null;
        }

        private static String withDashes(String id32) {
            return id32.substring(0, 8) + "-" + id32.substring(8, 12) + "-" + id32.substring(12, 16)
                    + "-" + id32.substring(16, 20) + "-" + id32.substring(20);
        }
    }
}
