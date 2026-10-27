package com.myagent.agui.channel;

import io.agentscope.extensions.channel.common.InboundEventDeduplicator;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 官方 {@link InboundEventDeduplicator} 的 Postgres 实现。
 *
 * <p>为什么不能只用官方的 {@code IdempotencyStore}：它是**进程内内存**（TTL 5 分钟），
 * 多副本部署时同一个事件会被每个副本各消费一次。落库用
 * {@code INSERT ... ON CONFLICT DO NOTHING} 天然做到全局唯一。
 */
public class PgInboundEventDeduplicator implements InboundEventDeduplicator {

    private static final Logger log = LoggerFactory.getLogger(PgInboundEventDeduplicator.class);

    /** 每隔多少次调用清理一次过期记录（避免表无限增长）。 */
    private static final long SWEEP_EVERY = 500;

    /** 事件记录保留时长：钉钉/飞书的重推都在分钟级，24 小时足够。 */
    private static final long RETENTION_HOURS = 24;

    private final JdbcTemplate jdbc;
    private final Long channelAppId;
    private final AtomicLong counter = new AtomicLong();

    public PgInboundEventDeduplicator(JdbcTemplate jdbc, Long channelAppId) {
        this.jdbc = jdbc;
        this.channelAppId = channelAppId;
    }

    @Override
    public boolean firstSeen(String eventKey) {
        if (eventKey == null || eventKey.isBlank()) {
            return true; // 没有事件 id 的消息无法去重，放行（官方实现同语义）
        }
        try {
            int rows = jdbc.update(
                    "INSERT INTO channel_event_seen (channel_app_id, event_key) VALUES (?, ?) "
                            + "ON CONFLICT (channel_app_id, event_key) DO NOTHING",
                    channelAppId, truncate(eventKey));
            if (counter.incrementAndGet() % SWEEP_EVERY == 0) {
                sweep();
            }
            return rows > 0;
        } catch (Exception ex) {
            // 去重表不可用时不能把用户的正常消息挡掉
            log.warn("入站事件去重失败 appId={}: {}", channelAppId, ex.toString());
            return true;
        }
    }

    private void sweep() {
        try {
            int removed = jdbc.update(
                    "DELETE FROM channel_event_seen WHERE channel_app_id = ? "
                            + "AND seen_at < now() - make_interval(hours => ?)",
                    channelAppId, (int) RETENTION_HOURS);
            if (removed > 0) {
                log.debug("清理过期入站事件记录 {} 条 appId={}", removed, channelAppId);
            }
        } catch (Exception ex) {
            log.debug("清理过期入站事件失败: {}", ex.getMessage());
        }
    }

    private static String truncate(String value) {
        return value.length() <= 256 ? value : value.substring(0, 256);
    }
}
