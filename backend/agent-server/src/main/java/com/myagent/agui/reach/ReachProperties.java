package com.myagent.agui.reach;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Agent Reach（互联网出口）配置。
 *
 * <p>它被定位为 **Agent 访问互联网的唯一途径**：搜索、读网页、读 GitHub/V2EX/B站/YouTube 字幕
 * 都走它的 CLI（只读），平台其它工具不再直接出网。
 *
 * <p>所有阈值集中在这里，不散落到调用点。
 */
@Configuration
public class ReachProperties {

    private final boolean enabled;
    private final String binary;
    private final int timeoutSeconds;
    private final int maxItems;
    private final int maxChars;
    private final List<String> channels;
    private final boolean allowPrivateNetwork;
    private final boolean resolveDns;
    private final String homeRoot;
    private final int concurrencyPerUser;
    private final int maxLimit;

    public ReachProperties(
            @Value("${myagent.reach.enabled:true}") boolean enabled,
            @Value("${myagent.reach.binary:agent-reach}") String binary,
            @Value("${myagent.reach.timeout-seconds:45}") int timeoutSeconds,
            @Value("${myagent.reach.max-items:8}") int maxItems,
            @Value("${myagent.reach.max-chars:12000}") int maxChars,
            @Value("${myagent.reach.channels:web,github,v2ex,bilibili,youtube}") String channels,
            @Value("${myagent.reach.allow-private-network:false}") boolean allowPrivateNetwork,
            @Value("${myagent.reach.resolve-dns:true}") boolean resolveDns,
            @Value("${myagent.reach.home-root:}") String homeRoot,
            @Value("${myagent.reach.concurrency-per-user:2}") int concurrencyPerUser,
            @Value("${myagent.reach.max-limit:10}") int maxLimit) {
        this.enabled = enabled;
        this.binary = binary;
        this.timeoutSeconds = timeoutSeconds;
        this.maxItems = maxItems;
        this.maxChars = maxChars;
        this.channels = Arrays.stream(channels.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(java.util.Locale.ROOT))
                .toList();
        this.allowPrivateNetwork = allowPrivateNetwork;
        this.resolveDns = resolveDns;
        this.homeRoot = homeRoot == null ? "" : homeRoot.trim();
        this.concurrencyPerUser = concurrencyPerUser;
        this.maxLimit = maxLimit;
    }

    public boolean enabled() {
        return enabled;
    }

    public String binary() {
        return binary;
    }

    public int timeoutSeconds() {
        return timeoutSeconds;
    }

    public int maxItems() {
        return maxItems;
    }

    public int maxChars() {
        return maxChars;
    }

    /** 允许 Agent 使用的渠道白名单（登录类渠道默认不开：容器里没有浏览器，也不该替用户登录）。 */
    public List<String> channels() {
        return channels;
    }

    public boolean channelAllowed(String channel) {
        return channel != null && channels.contains(channel.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /** 是否允许读内网地址（默认 false：这个工具是网关，必须挡住内网横向探测/云元数据）。 */
    public boolean allowPrivateNetwork() {
        return allowPrivateNetwork;
    }

    public boolean resolveDns() {
        return resolveDns;
    }

    /**
     * 每个用户的 HOME 根目录：{@code ~/.agent-reach} 会落在 {@code homeRoot/<userId>/.agent-reach}。
     *
     * <p>agent-reach 用 HOME 作为隔离边界（它自己的文档也这么写），所以这样就能做到
     * 「每个用户的登录态/配置互不可见」，多人共用一个 Agent 也不会串。
     */
    public String homeRoot() {
        return homeRoot;
    }

    public int concurrencyPerUser() {
        return concurrencyPerUser;
    }

    public int maxLimit() {
        return maxLimit;
    }
}
