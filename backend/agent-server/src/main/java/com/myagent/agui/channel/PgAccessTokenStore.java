package com.myagent.agui.channel;

import io.agentscope.extensions.channel.common.AccessTokenStore;
import io.agentscope.extensions.channel.common.CachedAccessToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 官方 {@link AccessTokenStore} 的 Postgres 实现。
 *
 * <p>为什么落库：钉钉的 access_token 每次刷新会让**上一次的 token 失效**。
 * 多副本部署时如果各存各的，两个副本会互相把对方刷掉（表现为间歇性 401）。
 * 落库 + {@link #putIfNewer} 让所有副本共用一份。
 */
public class PgAccessTokenStore implements AccessTokenStore {

    private static final Logger log = LoggerFactory.getLogger(PgAccessTokenStore.class);

    private final JdbcTemplate jdbc;
    private final Long channelAppId;

    public PgAccessTokenStore(JdbcTemplate jdbc, Long channelAppId) {
        this.jdbc = jdbc;
        this.channelAppId = channelAppId;
    }

    @Override
    public CachedAccessToken get() {
        try {
            return jdbc.query(
                    "SELECT token_value, refresh_at_ms FROM channel_access_token WHERE channel_app_id = ?",
                    rs -> rs.next()
                            ? new CachedAccessToken(rs.getString(1), rs.getLong(2))
                            : null,
                    channelAppId);
        } catch (Exception ex) {
            log.warn("读取渠道 access token 失败 appId={}: {}", channelAppId, ex.toString());
            return null;
        }
    }

    @Override
    public void put(CachedAccessToken token) {
        if (token == null) {
            return;
        }
        try {
            jdbc.update(
                    "INSERT INTO channel_access_token (channel_app_id, token_value, refresh_at_ms, updated_at) "
                            + "VALUES (?, ?, ?, now()) "
                            + "ON CONFLICT (channel_app_id) DO UPDATE "
                            + "SET token_value = EXCLUDED.token_value, "
                            + "    refresh_at_ms = EXCLUDED.refresh_at_ms, updated_at = now()",
                    channelAppId, token.value(), token.refreshAtMs());
        } catch (Exception ex) {
            log.warn("写入渠道 access token 失败 appId={}: {}", channelAppId, ex.toString());
        }
    }

    @Override
    public void clear() {
        try {
            jdbc.update("DELETE FROM channel_access_token WHERE channel_app_id = ?", channelAppId);
        } catch (Exception ex) {
            log.warn("清理渠道 access token 失败 appId={}: {}", channelAppId, ex.toString());
        }
    }
}
