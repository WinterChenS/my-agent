package com.myagent.agui.browser.ext;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Agent 工具 → 执行器的门面（方案 12.1）。
 *
 * <p>{@code browser_*} 工具只做参数收集和结果格式化，不放业务逻辑：
 * 本机模式走 {@link LocalExtensionExecutor}，远程模式仍由旧的 {@code BrowserSessionRegistry} 负责。
 */
@Component
public class BrowserToolService {

    private final LocalExtensionExecutor executor;
    private final BrowserControlSessionService sessions;
    private final BrowserExtensionProperties properties;
    private final BrowserBridgeHub hub;
    private final BrowserExtensionStore store;

    public BrowserToolService(
            LocalExtensionExecutor executor,
            BrowserControlSessionService sessions,
            BrowserExtensionProperties properties,
            BrowserBridgeHub hub,
            BrowserExtensionStore store) {
        this.executor = executor;
        this.sessions = sessions;
        this.properties = properties;
        this.hub = hub;
        this.store = store;
    }

    public boolean localMode() {
        return properties.localMode();
    }

    public boolean enabled() {
        return !properties.disabled();
    }

    public BrowserExecutionGateway.ExecResult run(
            long userId, String conversationId, String action, Map<String, Object> args) {
        return executor.execute(
                new BrowserExecutionGateway.Ctx(userId, conversationId, null, null), action, args);
    }

    /** 关闭：结束控制会话（撤销本机标签页授权），并通知扩展释放。 */
    public BrowserExecutionGateway.ExecResult close(long userId, String conversationId) {
        sessions.activeOf(userId, conversationId).ifPresent(row -> sessions.revoke(userId, row.id()));
        return BrowserExecutionGateway.ExecResult.ok("已结束浏览器控制会话，本机标签页授权已释放。", null, null);
    }

    /** 网页/工具共用的状态：设备在线、会话、状态机、页面。 */
    public Map<String, Object> status(long userId, String conversationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", properties.mode());
        body.put("protocolVersion", properties.protocolVersion());
        body.put("minimumVersion", properties.minimumVersion());
        java.util.ArrayList<Map<String, Object>> devices = new java.util.ArrayList<>();
        for (BrowserExtensionStore.DeviceRow device : store.devicesOf(userId)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", device.id());
            item.put("displayName", device.displayName());
            item.put("browser", device.browser());
            item.put("extensionVersion", device.extensionVersion());
            item.put("status", device.status());
            item.put("online", hub.online(device.id()));
            item.put("lastSeenAt", device.lastSeenAt() == null ? null : device.lastSeenAt().toString());
            devices.add(item);
        }
        body.put("devices", devices);
        var session = sessions.activeOf(userId, conversationId);
        session.ifPresent(row -> {
            body.put("controlSessionId", row.id());
            body.put("state", row.state());
            body.put("leaseVersion", row.leaseVersion());
            body.put("origin", row.origin());
            body.put("title", row.title());
            body.put("tabHandle", row.tabHandle());
            body.put("pageRevision", row.pageRevision());
            body.put("deviceId", row.deviceId());
            body.put("lastError", row.lastError());
            body.put("expiresAt", row.expiresAt().toString());
            body.put("online", hub.online(row.deviceId()));
        });
        return body;
    }

    /** 工具结果文本：错误统一 {@code [错误码] 说明}，让模型知道该等还是该换办法。 */
    public String format(BrowserExecutionGateway.ExecResult result) {
        if (!result.ok()) {
            String code = result.errorCode() == null ? "COMMAND_FAILED" : result.errorCode();
            return "[" + code + "] " + (result.message() == null ? "浏览器操作失败" : result.message());
        }
        StringBuilder sb = new StringBuilder();
        if (result.text() != null && !result.text().isBlank()) {
            sb.append(result.text());
        } else {
            sb.append("操作已完成");
        }
        if (result.origin() != null) {
            sb.append("\n（当前页面：").append(result.origin());
            if (result.pageRevision() > 0) {
                sb.append("，版本 ").append(result.pageRevision());
            }
            sb.append("）");
        }
        return sb.toString();
    }

    /** 截图：把扩展回传的 base64 交回去（工具层决定要不要落盘）。 */
    public String screenshotDataUrl(BrowserExecutionGateway.ExecResult result) {
        JsonNode data = result.data();
        if (data == null) {
            return null;
        }
        String image = data.path("image").asText(null);
        if (image == null || image.isBlank()) {
            return null;
        }
        return image.startsWith("data:") ? image : "data:image/jpeg;base64," + image;
    }
}
