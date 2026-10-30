package com.myagent.agui.reach;

import java.net.InetAddress;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 出网 URL 策略（SSRF 护栏）。
 *
 * <p>为什么必须有：Agent Reach 的 {@code read} 能读任意 URL —— 一旦把它的输出交给模型，
 * 就等于给模型开了一个「能访问任意地址」的 SSRF 入口。内网服务（192.168.x.x、localhost、
 * 云厂商元数据 169.254.169.254、Postgres/Redis 端口）必须挡在外面。
 *
 * <p>挡什么：
 * <ul>
 *   <li>非 http/https（file://、gopher://、jar:、data:…）
 *   <li>带 userinfo 的 URL（{@code http://user:pass@host}，容易被用来混淆真实主机）
 *   <li>不合格的主机名：localhost、无点主机名（内网短名）、*.local / *.internal / *.localdomain
 *   <li>字面量 IP 命中私有/环回/链路本地/组播/未指定地址
 *   <li>DNS 解析后落到上面这些段（默认开启；DNS rebinding 的窗口仍需网络层兜底）
 *   <li>常见内网/管理端口（22/25/3306/5432/6379/9200/11211…）
 * </ul>
 *
 * <p>注意：这是**网关层的护栏**，不是唯一防线。真正的"唯一出口"还要靠容器网络策略
 * （只允许出到 80/443 的公网）——见 docs/agent-reach-集成方案.md。
 */
public class ReachUrlPolicy {

    private static final Set<String> BLOCKED_SCHEMES = Set.of("file", "ftp", "gopher", "jar", "data", "ldap");
    private static final Set<Integer> BLOCKED_PORTS = Set.of(22, 23, 25, 143, 445, 3306, 5432, 6379, 9200, 11211,
            2375, 2376, 10250);
    private static final List<String> BLOCKED_SUFFIXES = List.of(".local", ".internal", ".localdomain", ".intranet",
            ".lan", ".home", ".corp", ".test");

    private final boolean allowPrivateNetwork;
    private final boolean resolveDns;

    public ReachUrlPolicy(boolean allowPrivateNetwork, boolean resolveDns) {
        this.allowPrivateNetwork = allowPrivateNetwork;
        this.resolveDns = resolveDns;
    }

    /** 通过返回 null；不通过返回给模型看的中文原因。 */
    public String reject(String url) {
        if (url == null || url.isBlank()) {
            return "url 不能为空";
        }
        String trimmed = url.trim();
        if (trimmed.length() > 2000) {
            return "url 太长";
        }

        java.net.URI uri;
        try {
            uri = java.net.URI.create(trimmed);
        } catch (RuntimeException e) {
            return "url 不是合法链接，请给出完整的 http(s) 地址";
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return "只支持 http/https 链接（不支持 " + (scheme.isEmpty() ? "相对地址" : scheme) + "）";
        }
        if (uri.getUserInfo() != null) {
            return "url 不能带用户名/密码，请给出干净的链接";
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "url 缺少主机名";
        }

        String lowerHost = host.toLowerCase(Locale.ROOT);
        if (!allowPrivateNetwork) {
            if (lowerHost.equals("localhost") || lowerHost.endsWith(".localhost")) {
                return "出于安全考虑，不能读取本机/内网地址：" + host;
            }
            if (BLOCKED_SUFFIXES.stream().anyMatch(lowerHost::endsWith)) {
                return "出于安全考虑，不能读取内网域名：" + host;
            }
            if (!lowerHost.contains(".") && !isIpLiteral(lowerHost)) {
                return "只支持公网域名，不能读取内网短名：" + host;
            }
            if (isIpLiteral(lowerHost)) {
                String reason = rejectAddress(lowerHost);
                if (reason != null) {
                    return reason;
                }
            } else if (resolveDns) {
                String reason = rejectResolvedDns(lowerHost);
                if (reason != null) {
                    return reason;
                }
            }
        }

        int port = uri.getPort();
        if (port > 0 && BLOCKED_PORTS.contains(port)) {
            return "出于安全考虑，不能访问端口 " + port;
        }
        if (BLOCKED_SCHEMES.contains(scheme)) {
            return "不支持这种链接类型";
        }
        return null;
    }

    private String rejectResolvedDns(String host) {
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                String reason = rejectAddress(address.getHostAddress());
                if (reason != null) {
                    return "域名解析到内网地址，已拒绝：" + host;
                }
            }
        } catch (java.net.UnknownHostException e) {
            return "域名解析失败：" + host + "（域名可能不存在或本机 DNS 不可用）";
        }
        return null;
    }

    private String rejectAddress(String literal) {
        try {
            InetAddress address = InetAddress.getByName(literal);
            if (address.isLoopbackAddress() || address.isAnyLocalAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) {
                return "出于安全考虑，不能读取内网/本机地址：" + literal;
            }
            byte[] raw = address.getAddress();
            // 100.64/10（运营商级 NAT）、169.254/16（云元数据）、127/8、0/8 额外兜一层
            if (raw.length == 4) {
                int first = raw[0] & 0xFF;
                int second = raw[1] & 0xFF;
                if (first == 127 || first == 10 || first == 0
                        || (first == 172 && second >= 16 && second <= 31)
                        || (first == 192 && second == 168)
                        || (first == 169 && second == 254)
                        || (first == 100 && second >= 64 && second <= 127)) {
                    return "出于安全考虑，不能读取内网/本机地址：" + literal;
                }
            } else if (raw.length == 16) {
                // fc00::/7（唯一本地）、fe80::/10（链路本地）
                int first = raw[0] & 0xFF;
                if ((first & 0xFE) == 0xFC || (first == 0xFE && (raw[1] & 0xC0) == 0x80)) {
                    return "出于安全考虑，不能读取内网/本机地址：" + literal;
                }
            }
            return null;
        } catch (java.net.UnknownHostException e) {
            return null;
        }
    }

    /** 是不是 IPv4 字面量或 IPv6 字面量。 */
    private boolean isIpLiteral(String host) {
        return host.matches("^\\d{1,3}(\\.\\d{1,3}){3}$") || host.contains(":");
    }
}
