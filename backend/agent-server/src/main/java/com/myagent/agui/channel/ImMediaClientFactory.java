package com.myagent.agui.channel;

import com.myagent.channelconfig.entity.ChannelApp;
import com.myagent.channelconfig.service.ChannelAppService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 按渠道类型提供 {@link ImMediaClient}，并按渠道应用缓存实例（内部持有 token 缓存与 HttpClient）。
 *
 * <p>加新渠道时**只写一个 {@link MediaClientProvider} 实现并注册成 Bean**，本类与调用方都不用改。
 * 之前这里是写死钉钉的工厂，第二家进来就要改网关，容易改坏第一家。
 */
@Component
public class ImMediaClientFactory {

    private static final Logger log = LoggerFactory.getLogger(ImMediaClientFactory.class);

    /** 各渠道的媒体客户端构造器。 */
    public interface MediaClientProvider {
        /** 渠道类型字面量，与 channel_app.channel_type 一致（大写）。 */
        String channelType();

        /** 用该渠道的明文配置建客户端。 */
        ImMediaClient create(Long channelAppId, Map<String, String> config);
    }

    private final ChannelAppService channelAppService;
    private final Map<String, MediaClientProvider> providers = new HashMap<>();
    private final Map<Long, ImMediaClient> cache = new ConcurrentHashMap<>();

    public ImMediaClientFactory(
            ChannelAppService channelAppService, List<MediaClientProvider> providerBeans) {
        this.channelAppService = channelAppService;
        for (MediaClientProvider provider : providerBeans) {
            providers.put(provider.channelType().toUpperCase(java.util.Locale.ROOT), provider);
        }
        if (!providers.isEmpty()) {
            log.info("已注册 IM 媒体客户端渠道: {}", providers.keySet());
        }
    }

    /** 该渠道是否支持媒体收发。 */
    public boolean supports(String channelType) {
        return channelType != null
                && providers.containsKey(channelType.toUpperCase(java.util.Locale.ROOT));
    }

    /** 该渠道不支持媒体时返回 null（调用方据此降级成"只回正文"）。 */
    public ImMediaClient get(ChannelApp app) {
        if (app == null || !supports(app.getChannelType())) {
            return null;
        }
        return cache.computeIfAbsent(app.getId(), id -> {
            MediaClientProvider provider =
                    providers.get(app.getChannelType().toUpperCase(java.util.Locale.ROOT));
            return provider.create(id, channelAppService.config(id));
        });
    }

    /** 配置变更（换机器人凭据）时必须失效。 */
    public void invalidate(Long channelAppId) {
        if (channelAppId != null) {
            cache.remove(channelAppId);
        }
    }

    /** 便于单测：直接换算好的构建函数。 */
    static ImMediaClient cached(
            Map<Long, ImMediaClient> cache, Long appId, Function<Long, ImMediaClient> creator) {
        return cache.computeIfAbsent(appId, creator);
    }
}
