package com.myagent.agui.channel;

import java.util.Map;
import org.springframework.stereotype.Component;

/** 钉钉的媒体客户端注册（{@link ImMediaClientFactory} 按 channelType 找到它）。 */
@Component
public class DingTalkMediaClientProvider implements ImMediaClientFactory.MediaClientProvider {

    @Override
    public String channelType() {
        return "DINGTALK";
    }

    @Override
    public ImMediaClient create(Long channelAppId, Map<String, String> config) {
        return DingTalkMediaClient.fromConfig(config);
    }
}
