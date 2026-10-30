package com.myagent.agui.connector;

import com.myagent.connectorconfig.ConnectorService;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 工具里「该用哪个连接器」的统一解析。
 *
 * <p>规则（邮件与 Notion 共用，保证提示语一致）：
 * <ol>
 *   <li>拿不到用户身份 → 明确说"无法确定用哪个账号"；</li>
 *   <li>该用户没有绑定到本 Agent 的这种连接器 → 告诉他去「能力 → 连接器」添加并绑定；</li>
 *   <li>绑了多个又没指定 → 列出候选，让模型用 {@code connector} 参数选；</li>
 *   <li>选中的是**停用**状态 → 告诉他去聊天框下方的连接器按钮里勾上启用（不是"我没有这个功能"）。</li>
 * </ol>
 */
public final class ConnectorToolResolver {

    /** 解析结果：要么拿到连接器，要么拿到"给用户看的一句话"。 */
    public record Resolution(ConnectorService.ResolvedConnector picked, String error) {

        public boolean ok() {
            return picked != null;
        }

        public static Resolution of(ConnectorService.ResolvedConnector picked) {
            return new Resolution(picked, null);
        }

        public static Resolution fail(String error) {
            return new Resolution(null, error);
        }
    }

    private ConnectorToolResolver() {
    }

    public static Resolution resolve(
            ConnectorService connectorService,
            long agentId,
            String type,
            String userId,
            String wanted) {
        if (userId == null || userId.isBlank()) {
            return Resolution.fail("拿不到当前用户身份，无法确定用哪个账号。请让用户重新登录后再试。");
        }
        long uid;
        try {
            uid = Long.parseLong(userId.trim());
        } catch (NumberFormatException ex) {
            return Resolution.fail("当前用户身份异常（" + userId + "），无法使用连接器。");
        }
        List<ConnectorService.ResolvedConnector> mine = connectorService.resolveForUser(agentId, uid).stream()
                .filter(candidate -> candidate.record().type().equalsIgnoreCase(type))
                .toList();
        if (mine.isEmpty()) {
            return Resolution.fail(noConnectorHint(type));
        }
        ConnectorService.ResolvedConnector picked = pick(mine, wanted);
        if (picked == null) {
            return Resolution.fail("你绑定了多个连接器，请用参数 `connector` 指定其中一个："
                    + describe(mine) + "。例如 \"connector\":\"" + label(mine.get(0)) + "\"");
        }
        if (!picked.record().enabled()) {
            return Resolution.fail("「" + picked.record().name() + "」这个连接器现在是**停用**状态，所以暂时用不了。"
                    + "请告诉用户：在聊天框下方点「连接器」按钮，把「" + picked.record().name()
                    + "」勾上启用（或到「能力 → 连接器」里启用），**下一轮**对话就能用。");
        }
        return Resolution.of(picked);
    }

    /** 没有任何可用连接器时的提示：说清"怎么才能用上"。 */
    static String noConnectorHint(String type) {
        String name = "NOTION".equalsIgnoreCase(type) ? "Notion" : "邮箱";
        return "当前用户还没有可用的" + name + "连接器（或没有绑定到本 Agent）。"
                + "请告诉用户：到「能力 → 连接器」添加自己的" + name
                + "连接器并绑定给当前 Agent；绑定后**下一轮**对话即可使用。在此之前无法替他操作。";
    }

    static ConnectorService.ResolvedConnector pick(
            List<ConnectorService.ResolvedConnector> mine, String wanted) {
        if (wanted != null && !wanted.isBlank()) {
            String key = wanted.trim().toLowerCase(Locale.ROOT);
            Optional<ConnectorService.ResolvedConnector> byAlias = mine.stream()
                    .filter(connector -> key.equals(lower(connector.record().alias()))
                            || key.equals(lower(connector.record().name())))
                    .findFirst();
            return byAlias.orElse(null);
        }
        return mine.size() == 1 ? mine.get(0) : null;
    }

    private static String describe(List<ConnectorService.ResolvedConnector> mine) {
        return String.join("、", mine.stream().map(ConnectorToolResolver::label).toList());
    }

    private static String label(ConnectorService.ResolvedConnector connector) {
        return connector.record().alias() == null
                ? connector.record().name()
                : connector.record().name() + "(" + connector.record().alias() + ")";
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
