package com.myagent.agui.connector.dingtalk;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 钉钉文档连接器的配置。
 *
 * <p>与 Notion 的最大差异：钉钉用**企业应用凭据 + operatorId**（以谁的身份操作），
 * 没有"每人一个令牌"。所以这里的 {@code operatorId} 决定能看/改哪些文档，
 * 留空时由 {@code DingTalkOperatorResolver} 自动映射成"该用户在钉钉机器人里的 staffId"。
 *
 * @param operatorId 以谁的钉钉身份操作（userId）；空=自动
 * @param defaultWorkspaceId 默认知识库/空间
 * @param allowedWorkspaceIds 知识库白名单（空=不限）
 * @param allowedDocKeys 文档白名单（空=不限）
 * @param allowWrite 是否允许写入（新建/追加/覆盖）
 * @param maxBlocks 单篇最多读多少块
 */
public record DingTalkDocSettings(
        String operatorId,
        String defaultWorkspaceId,
        List<String> allowedWorkspaceIds,
        List<String> allowedDocKeys,
        boolean allowWrite,
        int maxBlocks) {

    public static final int DEFAULT_MAX_BLOCKS = 300;

    public static DingTalkDocSettings of(Map<String, String> config) {
        return new DingTalkDocSettings(
                trim(config.get("operatorId")),
                Ids.normalize(config.get("defaultWorkspaceId")),
                split(config.get("allowedWorkspaceIds")),
                split(config.get("allowedDocKeys")),
                !"false".equalsIgnoreCase(trim(config.get("allowWrite"))),
                positiveInt(config.get("maxBlocks"), DEFAULT_MAX_BLOCKS));
    }

    /** 知识库白名单判定（空=不限）。 */
    public boolean allowsWorkspace(String rawId) {
        return allows(allowedWorkspaceIds, rawId);
    }

    /** 文档白名单判定（空=不限）。 */
    public boolean allowsDoc(String rawId) {
        return allows(allowedDocKeys, rawId);
    }

    private static boolean allows(List<String> whitelist, String rawId) {
        if (whitelist.isEmpty()) {
            return true;
        }
        String id = Ids.normalize(rawId);
        if (id == null) {
            return false;
        }
        return whitelist.stream()
                .map(Ids::normalize)
                .anyMatch(candidate -> candidate != null && candidate.equalsIgnoreCase(id));
    }

    static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static int positiveInt(String value, int fallback) {
        try {
            int parsed = value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
            return parsed <= 0 ? fallback : Math.min(parsed, 2000);
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
                .distinct()
                .toList();
    }

    /**
     * 从用户粘的链接/id 里提取标识。
     *
     * <p>钉钉文档链接形如：
     * <ul>
     *   <li>知识库：{@code https://alidocs.dingtalk.com/i/nodes/{nodeId}?utm_scene=team_space}</li>
     *   <li>文档：{@code https://alidocs.dingtalk.com/i/nodes/{nodeId}} 或 {@code .../i/{docKey}}</li>
     *   <li>旧版：{@code https://space.dingtalk.com/s/xxxx}</li>
     * </ul>
     * 我们在不知道具体形态时按"最后一段或 nodes 后面那段"提取，够用且不会瞎猜。
     */
    public static final class Ids {

        private static final Pattern AFTER_NODES = Pattern.compile("/(?:nodes|docs|i)/([A-Za-z0-9_-]{8,})");
        private static final Pattern ANY_SEGMENT = Pattern.compile("([A-Za-z0-9_-]{16,})");

        private Ids() {
        }

        public static String normalize(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String value = raw.trim();
            if (!value.contains("/") && !value.contains("?")) {
                return value;
            }
            int query = value.indexOf('?');
            if (query > 0) {
                value = value.substring(0, query);
            }
            Matcher nodes = AFTER_NODES.matcher(value);
            if (nodes.find()) {
                return nodes.group(1);
            }
            Matcher any = ANY_SEGMENT.matcher(value.replaceAll("/+$", ""));
            String last = null;
            while (any.find()) {
                last = any.group(1);
            }
            return last != null ? last : value.substring(value.lastIndexOf('/') + 1);
        }

        /** 从链接里判断是知识库还是文档（拿不准就返回 null，由调用方处理）。 */
        public static String kindOf(String raw) {
            if (raw == null) {
                return null;
            }
            String lower = raw.toLowerCase(Locale.ROOT);
            if (lower.contains("/wiki/") || lower.contains("knowledge")) {
                return "workspace";
            }
            if (lower.contains("/nodes/") || lower.contains("/docs/")) {
                return "doc";
            }
            return null;
        }
    }
}
