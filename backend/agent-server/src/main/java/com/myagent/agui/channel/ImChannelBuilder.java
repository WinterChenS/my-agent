package com.myagent.agui.channel;

import com.myagent.channelconfig.entity.ChannelApp;
import io.agentscope.harness.agent.gateway.channel.Channel;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 按渠道类型构造官方 {@link Channel} 实例（AgentScope 类型只出现在本接口实现里）。
 *
 * <p>为什么抽出来：原来 {@code ChannelRuntime.start()} 里写死 {@code if (DINGTALK)}，
 * 加一家 IM 就要改这个类，很容易连带改坏已跑通的渠道。现在每家一个 Bean，
 * {@link ChannelRuntime} 只按 type 查表。
 */
public interface ImChannelBuilder {

    /** 渠道类型字面量（大写，与 channel_app.channel_type 一致）。 */
    String channelType();

    /**
     * 构造渠道实例（**不**启动）。
     *
     * @param context 运行时基础设施（目前只需要 JdbcTemplate：去重表与 token 表都落库）
     */
    Channel build(ChannelApp app, Map<String, String> config, BuildContext context);

    /**
     * 启动前的凭据自检（可选，失败即抛出）。
     *
     * <p>为什么要它：官方长连接客户端鉴权失败时只在后台不断重试并打日志，
     * 管理页会一直显示"运行中"，用户根本不知道是 appKey/appSecret 错了。
     */
    default void preflight(ChannelApp app, Map<String, String> config) {
        // 默认不检查
    }

    /** 构造渠道时能用到的基础设施。 */
    record BuildContext(JdbcTemplate jdbc) {}
}
