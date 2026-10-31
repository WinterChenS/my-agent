package com.myagent.agui.browser.ext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 控制会话状态机（方案第 10 节）。
 *
 * <pre>
 * AWAITING_TAB --用户在本机授权标签页--> HUMAN_CONTROL --交还--> AGENT_CONTROL
 * AGENT_CONTROL --本机暂停/人工交互--> HUMAN_CONTROL
 * 任意活跃状态 --断线/更新/detach--> SUSPENDED
 * 任意活跃状态 --撤销/关页面--> REVOKED
 * </pre>
 *
 * <p>关键约定：
 * <ul>
 *   <li>每次暂停/撤销都递增 {@code lease_version}，之前下发的命令全部失效（扩展执行前会校验）；</li>
 *   <li>授权后默认进 {@code HUMAN_CONTROL}，由用户点「交还」才允许 Agent 写操作 ——
 *       不再有"60 秒无操作自动恢复写操作"；</li>
 *   <li>服务端状态只是镜像，扩展每个动作前还会再查一次本机状态。</li>
 * </ul>
 */
@Service
public class BrowserControlSessionService {

    private static final Logger log = LoggerFactory.getLogger(BrowserControlSessionService.class);

    public static final String AWAITING_TAB = "AWAITING_TAB";
    public static final String HUMAN_CONTROL = "HUMAN_CONTROL";
    public static final String AGENT_CONTROL = "AGENT_CONTROL";
    public static final String SUSPENDED = "SUSPENDED";
    public static final String REVOKED = "REVOKED";
    public static final String EXPIRED = "EXPIRED";

    private final BrowserExtensionStore store;
    private final BrowserBridgeHub hub;
    private final BrowserExtensionProperties properties;

    public BrowserControlSessionService(
            BrowserExtensionStore store, BrowserBridgeHub hub, BrowserExtensionProperties properties) {
        this.store = store;
        this.hub = hub;
        this.properties = properties;
    }

    /** 取（或创建）当前会话的浏览器控制会话。创建时状态是 AWAITING_TAB：需要用户在本机授权标签页。 */
    public BrowserExtensionStore.SessionRow ensureSession(
            long userId, String conversationId, String preferredDeviceId) {
        Optional<BrowserExtensionStore.SessionRow> existing =
                store.activeSession(userId, conversationId == null ? "" : conversationId);
        if (existing.isPresent()) {
            return existing.get();
        }
        String deviceId = preferredDeviceId != null ? preferredDeviceId : defaultDevice(userId);
        String id = DeviceTokenService.newId("cs-");
        BrowserExtensionStore.SessionRow row = new BrowserExtensionStore.SessionRow(
                id, userId, conversationId, deviceId, null, null, null, 0, AWAITING_TAB, 1, null,
                OffsetDateTime.now().plusSeconds(properties.controlSessionTtlSeconds()), null);
        store.insertSession(row);
        log.info("创建浏览器控制会话 sessionId={} userId={} conversationId={} deviceId={}",
                id, userId, conversationId, deviceId);
        return row;
    }

    public Optional<BrowserExtensionStore.SessionRow> activeOf(long userId, String conversationId) {
        return store.activeSession(userId, conversationId == null ? "" : conversationId);
    }

    public Optional<BrowserExtensionStore.SessionRow> byId(String id) {
        return store.sessionById(id);
    }

    /** 本人在线设备优先；都没在线就取最近一台已配对设备（前端会提示"离线"）。 */
    public String defaultDevice(long userId) {
        List<BrowserExtensionStore.DeviceRow> devices = store.devicesOf(userId);
        return devices.stream()
                .filter(d -> "ACTIVE".equals(d.status()) && hub.online(d.id()))
                .map(BrowserExtensionStore.DeviceRow::id)
                .findFirst()
                .or(() -> devices.stream().filter(d -> "ACTIVE".equals(d.status()))
                        .map(BrowserExtensionStore.DeviceRow::id).findFirst())
                .orElse(null);
    }

    // ---------------- 扩展上报 ----------------

    /** 扩展上报标签页授权/页面变化。授权完成（AWAITING_TAB）时进入 HUMAN_CONTROL。 */
    public void onTabState(BrowserBridgeHub.Conn conn, JsonNode frame) {
        String sessionId = frame.path("controlSessionId").asText("");
        store.sessionById(sessionId).ifPresent(row -> {
            if (row.userId() != conn.userId()) {
                log.warn("设备 {} 上报了不属于自己的会话 {}", conn.deviceId(), sessionId);
                return;
            }
            String tabHandle = text(frame, "tabHandle");
            String origin = text(frame, "origin");
            String title = text(frame, "title");
            int pageRevision = frame.path("pageRevision").asInt(row.pageRevision());
            store.updateTab(sessionId, tabHandle, origin, title, pageRevision);
            String control = text(frame, "controlState");
            if (AWAITING_TAB.equals(row.state()) && tabHandle != null) {
                // 两种授权路径分开处理：
                //  * 手动授权（用户点扩展）：默认 HUMAN_CONTROL，用户点「交还」才交给 Agent；
                //  * 自动接管（用户开了一次性全局授权）：扩展明确上报 AGENT_CONTROL，直接生效。
                String target = AGENT_CONTROL.equals(control) ? AGENT_CONTROL : HUMAN_CONTROL;
                store.transition(sessionId, target, null);
                log.info("会话 {} 已授权标签页 {} ({}), 进入 {}", sessionId, tabHandle, origin, target);
                // 必须把递增后的 leaseVersion 回给扩展：否则扩展本地还是旧租期，
                // Agent 的下一步命令会被它自己判成「命令过期」。
                hub.send(conn.deviceId(), sessionUpdate(sessionId, pageRevision));
            } else if (control != null && !control.equals(row.state())) {
                applyState(sessionId, control, null);
            }
        });
    }

    /** 扩展上报本机事件：人工接管、暂停、交还、debugger detach、关页面。 */
    public void onExtensionEvent(BrowserBridgeHub.Conn conn, JsonNode frame) {
        String sessionId = frame.path("controlSessionId").asText("");
        String event = frame.path("event").asText("");
        store.sessionById(sessionId).ifPresent(row -> {
            if (row.userId() != conn.userId()) {
                return;
            }
            switch (event) {
                case "human_control", "paused" -> applyState(sessionId, HUMAN_CONTROL, null);
                case "resumed", "agent_control" -> applyState(sessionId, AGENT_CONTROL, null);
                case "debugger_detached" -> applyState(sessionId, SUSPENDED, "调试会话已断开");
                case "tab_closed" -> applyState(sessionId, SUSPENDED, "标签页已关闭");
                case "origin_changed" -> applyState(sessionId, "AWAITING_ORIGIN_GRANT", "页面跳到了新站点");
                default -> log.debug("忽略扩展事件 {}（会话 {}）", event, sessionId);
            }
            // 把服务端确认后的状态回给扩展，前端轮询也能看到
            hub.send(conn.deviceId(), sessionUpdate(sessionId, frame.path("pageRevision").asInt(row.pageRevision())));
        });
    }

    private void applyState(String sessionId, String state, String error) {
        store.transition(sessionId, state, error);
        store.sessionById(sessionId).ifPresent(row -> log.info(
                "会话 {} 状态 -> {}{} (lease={})", sessionId, state,
                error == null ? "" : ("，原因：" + error), row.leaseVersion()));
    }

    // ---------------- 网页操作 ----------------

    public BrowserExtensionStore.SessionRow pause(long userId, String sessionId) {
        BrowserExtensionStore.SessionRow row = requireOwned(userId, sessionId);
        applyState(sessionId, HUMAN_CONTROL, "用户主动暂停");
        hub.send(row.deviceId(), sessionUpdate(sessionId, row.pageRevision()));
        return store.sessionById(sessionId).orElseThrow();
    }

    public BrowserExtensionStore.SessionRow resume(long userId, String sessionId) {
        BrowserExtensionStore.SessionRow row = requireOwned(userId, sessionId);
        if (AWAITING_TAB.equals(row.state())) {
            throw new com.myagent.common.BizException(40021, "还没授权标签页，先在扩展里选择网页",
                    org.springframework.http.HttpStatus.BAD_REQUEST);
        }
        applyState(sessionId, AGENT_CONTROL, null);
        hub.send(row.deviceId(), sessionUpdate(sessionId, row.pageRevision()));
        return store.sessionById(sessionId).orElseThrow();
    }

    public void revoke(long userId, String sessionId) {
        BrowserExtensionStore.SessionRow row = requireOwned(userId, sessionId);
        store.endSession(sessionId);
        hub.send(row.deviceId(), sessionUpdate(sessionId, row.pageRevision()));
        log.info("用户撤销控制会话 {}（设备 {}）", sessionId, row.deviceId());
    }

    public BrowserExtensionStore.SessionRow requireOwned(long userId, String sessionId) {
        return store.sessionById(sessionId)
                .filter(row -> row.userId() == userId)
                .orElseThrow(() -> new com.myagent.common.BizException(
                        40404, "控制会话不存在", org.springframework.http.HttpStatus.NOT_FOUND));
    }

    /** 到期清理：到期会话直接结束；扩展开着但没人用的，由扩展自己回收标签页授权。 */
    @Scheduled(fixedDelayString = "${myagent.browser.extension.cleanup-millis:60000}")
    public void expire() {
        int expired = store.expireStale();
        if (expired > 0) {
            log.info("清理了 {} 个过期的浏览器控制会话", expired);
        }
    }

    public ObjectNode sessionUpdate(String sessionId, int pageRevision) {
        ObjectNode update = BrowserBridgeHub.frame("session.update");
        update.put("controlSessionId", sessionId);
        store.sessionById(sessionId).ifPresent(row -> {
            update.put("state", row.state());
            update.put("leaseVersion", row.leaseVersion());
            update.put("pageRevision", pageRevision);
            update.put("origin", row.origin());
            update.put("tabHandle", row.tabHandle());
        });
        return update;
    }

    private static String text(JsonNode frame, String field) {
        JsonNode node = frame.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }
}
