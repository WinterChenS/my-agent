package com.myagent.agui.channel;

import com.myagent.channelconfig.entity.ChannelApp;
import com.myagent.channelconfig.entity.ChannelPeer;
import com.myagent.channelconfig.mapper.ChannelPeerMapper;
import com.myagent.channelconfig.event.ChannelConfigChangedEvent;
import com.myagent.channelconfig.service.ChannelAppService;
import com.myagent.channelconfig.service.ChannelPushPort;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.extensions.channel.common.InboundEventDeduplicator;
import io.agentscope.extensions.channel.dingtalk.DingTalkChannel;
import io.agentscope.harness.agent.gateway.ChannelManager;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Component;

/**
 * 渠道生命周期：按 {@code channel_app} 表把钉钉渠道实例起停，并负责主动推送。
 *
 * <p>门禁 G8：渠道协议、消息解析、Stream 长连接、出站文本全部走官方
 * {@link DingTalkChannel}；我们只补两件官方没有的东西——<b>媒体收发</b>
 * （{@link DingTalkMediaClient} 的 {@link ImMediaClient} 实现）和<b>入站去重/token 的持久化</b>（官方 SPI 的实现）。
 *
 * <p>为什么生命周期要自己管而不是用 {@code GatewayBootstrap}：一个平台里可能有多个
 * 机器人（多个 Agent），每个都要独立启停、独立凭据、独立去重表；官方 bootstrap 是"单网关"模型。
 */
@Component
public class ChannelRuntime implements ChannelPushPort {

    private static final Logger log = LoggerFactory.getLogger(ChannelRuntime.class);

    private final ChannelAppService channelAppService;
    private final PlatformChannelGateway gateway;
    private final ImMediaClientFactory mediaClientFactory;
    private final com.myagent.artifact.service.ArtifactService artifactService;
    private final ChannelPeerMapper peerMapper;
    private final com.myagent.agui.AguiProperties aguiProperties;
    private final JdbcTemplate jdbc;

    /** 各渠道的构造器（按 channelType 查；加新渠道只需新增一个 Bean）。 */
    private final Map<String, ImChannelBuilder> builders = new HashMap<>();

    private final ChannelManager channelManager = new ChannelManager();
    /** 串行执行起停，避免"改配置 + 手动重启"并发把渠道建成两份。 */
    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "channel-runtime");
                t.setDaemon(true);
                return t;
            });

    public ChannelRuntime(
            ChannelAppService channelAppService,
            PlatformChannelGateway gateway,
            ImMediaClientFactory mediaClientFactory,
            com.myagent.artifact.service.ArtifactService artifactService,
            ChannelPeerMapper peerMapper,
            com.myagent.agui.AguiProperties aguiProperties,
            java.util.List<ImChannelBuilder> builderBeans,
            JdbcTemplate jdbc) {
        for (ImChannelBuilder builder : builderBeans) {
            builders.put(builder.channelType().toUpperCase(java.util.Locale.ROOT), builder);
        }
        this.channelAppService = channelAppService;
        this.gateway = gateway;
        this.mediaClientFactory = mediaClientFactory;
        this.artifactService = artifactService;
        this.peerMapper = peerMapper;
        this.aguiProperties = aguiProperties;
        this.jdbc = jdbc;
    }

    // ===================== 生命周期 =====================

    @EventListener(ApplicationReadyEvent.class)
    public void startAll() {
        worker.execute(() -> {
            for (ChannelApp app : channelAppService.enabledApps()) {
                try {
                    start(app.getId());
                } catch (Exception ex) {
                    log.warn("渠道启动失败 appId={}: {}", app.getId(), ex.toString());
                }
            }
        });
    }

    /**
     * 配置变更在**事务提交后**再重载。
     *
     * <p>踩过的坑：事件在 @Transactional 方法里发布，如果这里立刻读库，读到的还是旧值
     * （enabled 仍是 false）→ 表现为「点了启用但渠道没起来」。AFTER_COMMIT 才是正确时机。
     */
    @org.springframework.transaction.event.TransactionalEventListener(
            phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true)
    public void onConfigChanged(ChannelConfigChangedEvent event) {
        worker.execute(() -> {
            try {
                stop(event.appId());
                mediaClientFactory.invalidate(event.appId());
                if (event.deleted()) {
                    return;
                }
                ChannelApp app = channelAppService.require(event.appId());
                if (Boolean.TRUE.equals(app.getEnabled())) {
                    start(app.getId());
                }
            } catch (Exception ex) {
                log.warn("渠道重载失败 appId={}: {}", event.appId(), ex.toString());
            }
        });
    }

    @EventListener(ContextClosedEvent.class)
    public void stopAll() {
        for (String channelId : channelManager.channelIds()) {
            try {
                channelManager.getChannel(channelId).ifPresent(Channel::stop);
            } catch (Exception ex) {
                log.debug("停止渠道失败 channelId={}: {}", channelId, ex.getMessage());
            }
        }
        worker.shutdownNow();
    }

    /** 启动一个渠道（幂等：已在跑会先停）。 */
    public void start(Long appId) {
        ChannelApp app = channelAppService.require(appId);
        ImChannelBuilder builder = builderFor(app.getChannelType());
        if (builder == null) {
            channelAppService.markStatus(appId, "ERROR",
                    "该渠道类型暂未接入运行时（已接入：" + builders.keySet() + "）");
            return;
        }
        Map<String, String> config = channelAppService.config(appId);
        try {
            // 先验一次凭据：官方长连接客户端鉴权失败只在后台重试并打日志，
            // 管理页会一直显示"运行中"，用户根本不知道是凭据错了。
            builder.preflight(app, config);
            Channel channel = builder.build(app, config, new ImChannelBuilder.BuildContext(jdbc));
            channel.init(gateway);
            channel.start();
            channelManager.register(channel);
            channelAppService.markStatus(appId, "RUNNING", null);
            log.info("渠道已启动 type={} appId={} name={} mode={}", app.getChannelType(), appId,
                    app.getName(), config.getOrDefault("mode", "stream"));
        } catch (Exception ex) {
            log.error("渠道启动失败 type={} appId={}: {}", app.getChannelType(), appId, ex.toString(), ex);
            channelAppService.markStatus(appId, "ERROR", rootMessage(ex));
        }
    }

    public void stop(Long appId) {
        String channelId = String.valueOf(appId);
        channelManager.getChannel(channelId).ifPresent(channel -> {
            try {
                channel.stop();
            } catch (Exception ex) {
                log.warn("停止渠道失败 appId={}: {}", appId, ex.toString());
            }
        });
        channelManager.unregister(channelId);
        channelAppService.markStatus(appId, "STOPPED", null);
    }

    private ImChannelBuilder builderFor(String channelType) {
        return channelType == null
                ? null
                : builders.get(channelType.trim().toUpperCase(java.util.Locale.ROOT));
    }

    /** 已接入运行时的渠道类型（管理页/排障用）。 */
    public java.util.Set<String> runtimeChannelTypes() {
        return java.util.Set.copyOf(builders.keySet());
    }

    // ===================== 主动推送 =====================

    @Override
    public int push(Long channelAppId, String peerKey, String text, List<Long> artifactIds) {
        ChannelApp app = channelAppService.require(channelAppId);
        ChannelPeer peer = findPeer(channelAppId, peerKey);
        if (peer == null) {
            throw com.myagent.common.BizException.badRequest("该会话还没有和机器人聊过，无法主动推送: " + peerKey);
        }
        OutboundAddress address = peer.getOutboundAddress() == null
                ? null
                : OutboundAddress.fromMap(readMap(peer.getOutboundAddress()));
        if (address == null) {
            throw com.myagent.common.BizException.badRequest("该会话缺少推送地址，请在 IM 里先发一条消息");
        }
        int sent = 0;
        if (text != null && !text.isBlank()) {
            sendText(app, address, text);
            sent += 1;
        }
        // 发媒体必须用裸 peer id（address.to 是 "channelId:KIND:id" 复合串，钉钉会报 staffId.notExisted）
        sent += sendArtifacts(app, peer.getPeerKey(), peer.getPeerKind(), artifactIds);
        return sent;
    }

    @Override
    public int pushFiles(
            Long channelAppId, String peerKey, String text, List<String> workspacePaths) {
        ChannelApp app = channelAppService.require(channelAppId);
        ChannelPeer peer = findPeer(channelAppId, peerKey);
        if (peer == null || peer.getOutboundAddress() == null) {
            throw com.myagent.common.BizException.badRequest("该会话还没有和机器人聊过，无法推送: " + peerKey);
        }
        OutboundAddress address = OutboundAddress.fromMap(readMap(peer.getOutboundAddress()));
        int sent = 0;
        if (text != null && !text.isBlank()) {
            sendText(app, address, text);
            sent += 1;
        }
        if (workspacePaths == null || workspacePaths.isEmpty()) {
            return sent;
        }
        ImMediaClient client = mediaClientFactory.get(app);
        if (client == null) {
            return sent;
        }
        java.nio.file.Path root = java.nio.file.Path.of(
                aguiProperties.workspaceRoot()).toAbsolutePath().normalize()
                .resolve(String.valueOf(app.getOwnerUserId()));
        boolean direct = !"GROUP".equalsIgnoreCase(peer.getPeerKind());
        for (String path : workspacePaths) {
            try {
                java.nio.file.Path file = root.resolve(path).normalize();
                if (!file.startsWith(root)) {
                    log.warn("拒绝推送工作区外的路径: {}", path);
                    continue;
                }
                if (!java.nio.file.Files.isRegularFile(file)) {
                    log.warn("推送失败，文件不存在: {}", file);
                    continue;
                }
                byte[] bytes = java.nio.file.Files.readAllBytes(file);
                client.sendMedia(direct, peer.getPeerKey(), file.getFileName().toString(), bytes);
                sent += 1;
            } catch (Exception ex) {
                log.warn("按路径推送失败 path={}: {}", path, ex.toString());
            }
        }
        return sent;
    }

    private void sendText(ChannelApp app, OutboundAddress address, String text) {
        Channel channel = channelManager.getChannel(String.valueOf(app.getId()))
                .orElseThrow(() -> com.myagent.common.BizException.badRequest("渠道未启动，无法推送"));
        Msg msg = Msg.builder().role(MsgRole.ASSISTANT).textContent(text).build();
        channel.deliver(address, List.of(msg));
    }

    private int sendArtifacts(
            ChannelApp app, String peerId, String peerKind, List<Long> artifactIds) {
        if (artifactIds == null || artifactIds.isEmpty()) {
            return 0;
        }
        ImMediaClient client = mediaClientFactory.get(app);
        if (client == null) {
            return 0;
        }
        boolean direct = !"GROUP".equalsIgnoreCase(peerKind);
        int sent = 0;
        for (Long artifactId : artifactIds) {
            try {
                // 用 contentBytes：download() 对 MinIO 产物只给预签名 URL（bytes 为 null）
                byte[] bytes = artifactService.contentBytes(app.getOwnerUserId(), artifactId);
                if (bytes == null || bytes.length == 0) {
                    log.warn("产物内容为空，跳过推送 artifactId={}", artifactId);
                    continue;
                }
                String fileName = artifactService.fileNameOf(app.getOwnerUserId(), artifactId);
                client.sendMedia(direct, peerId, fileName, bytes);
                sent += 1;
            } catch (Exception ex) {
                log.warn("推送产物失败 artifactId={}: {}", artifactId, ex.toString());
            }
        }
        return sent;
    }

    private ChannelPeer findPeer(Long channelAppId, String peerKey) {
        if (channelAppId == null || peerKey == null || peerKey.isBlank()) {
            return null;
        }
        return peerMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<ChannelPeer>lambdaQuery()
                .eq(ChannelPeer::getChannelAppId, channelAppId)
                .eq(ChannelPeer::getPeerKey, peerKey));
    }

    private static Map<String, Object> readMap(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null ? current.getClass().getSimpleName() : message;
    }

    /** 供管理页「最近一次启动检查」使用。 */
    public List<String> runningChannelIds() {
        return new ArrayList<>(channelManager.channelIds());
    }

    /** 等待起停任务完成（单测友好）。 */
    void awaitIdle(long timeoutMillis) {
        try {
            worker.submit(() -> {}).get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            log.debug("等待渠道任务空闲失败: {}", ex.getMessage());
        }
    }
}
