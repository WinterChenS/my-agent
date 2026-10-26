package com.myagent.agui.channel;

import com.myagent.channelconfig.entity.ChannelApp;
import com.myagent.channelconfig.service.ChannelAppService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 按渠道应用缓存 {@link DingTalkMediaClient}（内部持有 access_token 缓存与 HttpClient）。
 *
 * <p>配置变更（换机器人凭据）时必须失效，否则会继续用旧 appKey 换 token。
 */
@Component
public class DingTalkMediaClientFactory {

    private final ChannelAppService channelAppService;
    private final Map<Long, DingTalkMediaClient> cache = new ConcurrentHashMap<>();

    public DingTalkMediaClientFactory(ChannelAppService channelAppService) {
        this.channelAppService = channelAppService;
    }

    /** 非钉钉渠道返回 null（飞书媒体后续接入）。 */
    public DingTalkMediaClient get(ChannelApp app) {
        if (app == null || !"DINGTALK".equals(app.getChannelType())) {
            return null;
        }
        return cache.computeIfAbsent(app.getId(), id -> {
            Map<String, String> config = channelAppService.config(id);
            return DingTalkMediaClient.fromConfig(config);
        });
    }

    public void invalidate(Long channelAppId) {
        if (channelAppId != null) {
            cache.remove(channelAppId);
        }
    }
}
