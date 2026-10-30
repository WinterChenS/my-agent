package com.myagent.agui.connector.dingtalk;

import com.myagent.common.CryptoService;
import com.myagent.channelconfig.entity.ChannelApp;
import com.myagent.channelconfig.mapper.ChannelAppMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 钉钉文档用的**企业应用凭据**来自哪里。
 *
 * <p>设计取舍：文档能力复用平台上已有的那个钉钉机器人应用（管理员只需在开放平台给它加文档权限），
 * 所以这里优先从 {@code channel_app}（type=DINGTALK、启用中）取 appKey/appSecret；
 * 也可以用 {@code myagent.connector.dingtalk-doc.app-key/app-secret} 单独指定。
 *
 * <p>注意：这**不是**"用户凭据"——用户身份由 {@code operatorId} 决定（见 {@link DingTalkOperatorResolver}）。
 */
@Component
public class DingTalkAppCredentials {

    private static final Logger log = LoggerFactory.getLogger(DingTalkAppCredentials.class);

    private final ChannelAppMapper channelAppMapper;
    private final CryptoService cryptoService;
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
    private final String configuredAppKey;
    private final String configuredAppSecret;

    public DingTalkAppCredentials(
            ChannelAppMapper channelAppMapper,
            CryptoService cryptoService,
            @Value("${myagent.connector.dingtalk-doc.app-key:}") String configuredAppKey,
            @Value("${myagent.connector.dingtalk-doc.app-secret:}") String configuredAppSecret) {
        this.channelAppMapper = channelAppMapper;
        this.cryptoService = cryptoService;
        this.configuredAppKey = configuredAppKey == null ? "" : configuredAppKey.trim();
        this.configuredAppSecret = configuredAppSecret == null ? "" : configuredAppSecret.trim();
    }

    /** 解析出可用的 appKey/appSecret；都没有则返回 null。 */
    public Map<String, String> resolve() {
        if (!configuredAppKey.isBlank() && !configuredAppSecret.isBlank()) {
            return Map.of("appKey", configuredAppKey, "appSecret", configuredAppSecret);
        }
        List<ChannelApp> apps = channelAppMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<ChannelApp>lambdaQuery()
                        .eq(ChannelApp::getChannelType, "DINGTALK")
                        .orderByDesc(ChannelApp::getId));
        for (ChannelApp app : apps) {
            if (app.getConfigEnc() == null || app.getConfigEnc().isBlank()) {
                continue;
            }
            try {
                String plain = cryptoService.decrypt(app.getConfigEnc());
                Map<String, String> config = json.readValue(plain, new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, String>>() {});
                String appKey = config.get("appKey");
                String appSecret = config.get("appSecret");
                if (appKey != null && !appKey.isBlank() && appSecret != null && !appSecret.isBlank()) {
                    return Map.of("appKey", appKey, "appSecret", appSecret);
                }
            } catch (Exception ex) {
                log.debug("解析钉钉渠道凭据失败 appId={}: {}", app.getId(), ex.getMessage());
            }
        }
        return null;
    }
}
