package com.myagent.agui.connector.inbound;

import com.myagent.agui.connector.ConnectorProvider;
import com.myagent.agui.connector.ConnectorToolkit;
import com.myagent.connectorconfig.ConnectorRecord;
import com.myagent.connectorconfig.ConnectorService;
import com.myagent.connectorconfig.ConnectorStore;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 入站邮件轮询：定时把「配置了 NOTIFY / AUTOREPLY」的连接器的新邮件拉下来交给
 * {@link ConnectorInboundService}。
 *
 * <p>多节点安全：每个连接器先用数据库租约（{@code lease_owner/lease_until}）抢占，
 * 抢到才拉；租约到期自动可被别的节点接手（节点崩了不会永久卡住）。
 * 没有引入新组件：复用同一个 PostgreSQL。
 */
@Component
public class ConnectorInboundPoller {

    private static final Logger log = LoggerFactory.getLogger(ConnectorInboundPoller.class);

    private final ConnectorStore store;
    private final ConnectorService connectorService;
    private final ConnectorToolkit connectorToolkit;
    private final ConnectorInboundService inboundService;
    private final boolean enabled;
    private final int leaseMinutes;
    private final String nodeId = "node-" + java.util.UUID.randomUUID();

    public ConnectorInboundPoller(
            ConnectorStore store,
            ConnectorService connectorService,
            ConnectorToolkit connectorToolkit,
            ConnectorInboundService inboundService,
            @Value("${myagent.connector.inbound.enabled:true}") boolean enabled,
            @Value("${myagent.connector.inbound.lease-minutes:5}") int leaseMinutes) {
        this.store = store;
        this.connectorService = connectorService;
        this.connectorToolkit = connectorToolkit;
        this.inboundService = inboundService;
        this.enabled = enabled;
        this.leaseMinutes = leaseMinutes <= 0 ? 5 : leaseMinutes;
    }

    @Scheduled(fixedDelayString = "${myagent.connector.inbound.poll-millis:60000}",
            initialDelayString = "${myagent.connector.inbound.initial-delay-ms:30000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        List<ConnectorRecord> connectors;
        try {
            connectors = store.listInboundEnabled();
        } catch (Exception ex) {
            log.warn("查询入站连接器失败: {}", ex.toString());
            return;
        }
        for (ConnectorRecord record : connectors) {
            if (!store.claimLease(record.id(), nodeId, leaseMinutes)) {
                continue;
            }
            try {
                ConnectorProvider provider = connectorToolkit.providerFor(record.type());
                if (provider == null) {
                    continue;
                }
                ConnectorProvider.PollResult result = provider.poll(record, connectorService.config(record));
                if (result.baselined()) {
                    // 第一次跑（或邮箱被重建）：只打基线，从下一封真正到达的邮件开始算，
                    // 绝不把历史未读邮件当新邮件推出去（用户实测投诉过一次）。
                    log.info("入站轮询建立基线 connectorId={} uidValidity={} lastUid={}（不回灌历史邮件）",
                            record.id(), result.uidValidity(), result.lastUid());
                } else if (!result.messages().isEmpty()) {
                    int handled = inboundService.handle(record, result.messages());
                    log.info("入站邮件 connectorId={} 拉取 {} 封，处理 {} 封",
                            record.id(), result.messages().size(), handled);
                }
                if (result.hasCursor()) {
                    store.saveInboundCursor(record.id(), result.uidValidity(), result.lastUid());
                }
            } catch (Exception ex) {
                log.warn("入站轮询失败 connectorId={}: {}", record.id(), ex.toString());
            } finally {
                store.releaseLease(record.id(), nodeId);
            }
        }
    }
}
