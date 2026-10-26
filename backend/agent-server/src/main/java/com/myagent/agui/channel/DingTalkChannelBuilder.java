package com.myagent.agui.channel;

import com.myagent.channelconfig.entity.ChannelApp;
import io.agentscope.extensions.channel.dingtalk.DingTalkChannel;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 钉钉渠道的构造与凭据自检。 */
@Component
public class DingTalkChannelBuilder implements ImChannelBuilder {

    @Override
    public String channelType() {
        return "DINGTALK";
    }

    @Override
    public Channel build(ChannelApp app, Map<String, String> config, BuildContext context) {
        Map<String, Object> props = new LinkedHashMap<>(config);
        props.putIfAbsent("mode", "stream");
        // channelId 用数据库 id：回调地址与渠道实例一一对应，也便于日志排查
        String channelId = String.valueOf(app.getId());
        ChannelConfig channelConfig = ChannelConfig.of(channelId, String.valueOf(app.getAgentId()));
        return DingTalkChannel.fromProperties(
                channelId,
                channelConfig,
                props,
                new PgInboundEventDeduplicator(context.jdbc(), app.getId()),
                new PgAccessTokenStore(context.jdbc(), app.getId()));
    }

    @Override
    public void preflight(ChannelApp app, Map<String, String> config) {
        // 用官方 accessToken 接口验一次：失败会带钉钉返回的真实原因（如 invalidClientIdOrSecret）
        DingTalkMediaClient.fromConfig(config).v1Token();
    }
}
