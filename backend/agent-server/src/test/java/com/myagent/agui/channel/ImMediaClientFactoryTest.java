package com.myagent.agui.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.myagent.channelconfig.entity.ChannelApp;
import com.myagent.channelconfig.service.ChannelAppService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 媒体客户端工厂：按 channelType 路由、按应用缓存、未知类型返回 null。 */
class ImMediaClientFactoryTest {

    private static ChannelApp app(long id, String type) {
        ChannelApp app = new ChannelApp();
        app.setId(id);
        app.setChannelType(type);
        app.setAgentId(1L);
        app.setOwnerUserId(1L);
        return app;
    }

    @Test
    void 按类型路由到对应provider() {
        ChannelAppService service = mock(ChannelAppService.class);
        when(service.config(any())).thenReturn(Map.of("appKey", "k"));
        ImMediaClient ding = mock(ImMediaClient.class);
        ImMediaClient fei = mock(ImMediaClient.class);
        ImMediaClientFactory factory = new ImMediaClientFactory(service, List.of(
                new ImMediaClientFactory.MediaClientProvider() {
                    public String channelType() {
                        return "DINGTALK";
                    }

                    public ImMediaClient create(Long id, Map<String, String> config) {
                        return ding;
                    }
                },
                new ImMediaClientFactory.MediaClientProvider() {
                    public String channelType() {
                        return "FEISHU";
                    }

                    public ImMediaClient create(Long id, Map<String, String> config) {
                        return fei;
                    }
                }));

        assertThat(factory.get(app(1L, "DINGTALK"))).isSameAs(ding);
        assertThat(factory.get(app(2L, "feishu"))).isSameAs(fei);
        assertThat(factory.supports("FEISHU")).isTrue();
        assertThat(factory.supports("QQ")).isFalse();
    }

    @Test
    void 未知渠道返回null且不报错() {
        ChannelAppService service = mock(ChannelAppService.class);
        ImMediaClientFactory factory = new ImMediaClientFactory(service, List.of());

        assertThat(factory.get(app(1L, "DINGTALK"))).isNull();
        assertThat(factory.get(null)).isNull();
        verify(service, never()).config(any());
    }

    @Test
    void 同一应用只建一次_失效后重建() {
        ChannelAppService service = mock(ChannelAppService.class);
        when(service.config(any())).thenReturn(Map.of());
        AtomicInteger created = new AtomicInteger();
        ImMediaClient client = mock(ImMediaClient.class);
        ImMediaClientFactory factory = new ImMediaClientFactory(service, List.of(
                new ImMediaClientFactory.MediaClientProvider() {
                    public String channelType() {
                        return "DINGTALK";
                    }

                    public ImMediaClient create(Long id, Map<String, String> config) {
                        created.incrementAndGet();
                        return client;
                    }
                }));

        ChannelApp target = app(1L, "DINGTALK");
        factory.get(target);
        factory.get(target);
        assertThat(created.get()).isEqualTo(1);

        factory.invalidate(1L);
        factory.get(target);
        assertThat(created.get()).isEqualTo(2);
        verify(service, times(2)).config(1L);
    }
}
