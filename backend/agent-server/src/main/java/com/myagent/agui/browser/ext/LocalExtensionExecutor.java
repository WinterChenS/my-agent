package com.myagent.agui.browser.ext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 本机扩展执行器：把「动作」翻译成命令帧，下发给用户本机的扩展，等它回结果。
 *
 * <p>授权与状态机在这里的落法：
 * <ul>
 *   <li>没有控制会话 → 建一个 {@code AWAITING_TAB}，并请求扩展弹「选择网页」；</li>
 *   <li>{@code AWAITING_TAB} 时只允许请求授权，不允许任何读/写动作；</li>
 *   <li>{@code HUMAN_CONTROL} 时一律拒绝写动作（读快照也拒绝，避免"用户在看，Agent 在偷读"）；</li>
 *   <li>{@code AGENT_CONTROL} 才执行，且带上 {@code leaseVersion} + {@code pageRevision}；</li>
 *   <li>扩展回来 {@code HUMAN_CONTROL}/{@code SUSPENDED} 时，服务端镜像同步更新。</li>
 * </ul>
 */
@Component
public class LocalExtensionExecutor implements BrowserExecutionGateway {

    private static final Logger log = LoggerFactory.getLogger(LocalExtensionExecutor.class);

    /** 写动作（会改变外部状态）。读动作只有快照/截图/等待。 */
    private static final List<String> WRITE_ACTIONS = List.of("click", "type_text", "press_key", "navigate");

    private final BrowserExtensionProperties properties;
    private final BrowserExtensionStore store;
    private final BrowserBridgeHub hub;
    private final BrowserControlSessionService sessions;

    public LocalExtensionExecutor(
            BrowserExtensionProperties properties,
            BrowserExtensionStore store,
            BrowserBridgeHub hub,
            BrowserControlSessionService sessions) {
        this.properties = properties;
        this.store = store;
        this.hub = hub;
        this.sessions = sessions;
    }

    @Override
    public String mode() {
        return "local-extension";
    }

    @Override
    public ExecResult execute(Ctx ctx, String action, Map<String, Object> args) {
        long userId = ctx.userId();
        String conversationId = ctx.conversationId();

        String deviceId = ctx.deviceId() != null ? ctx.deviceId() : sessions.defaultDevice(userId);
        if (deviceId == null) {
            return ExecResult.fail("EXTENSION_REQUIRED",
                    "还没有配对任何浏览器扩展。请先在「浏览器」面板安装扩展并连接当前账号，"
                            + "然后在本机标签页点击扩展图标授权要操作的网页。");
        }
        if (!hub.online(deviceId)) {
            return ExecResult.fail("DEVICE_OFFLINE",
                    "浏览器扩展当前离线（电脑休眠、浏览器关闭或扩展被停用）。"
                            + "请在浏览器里打开扩展确认连接，或重新配对。");
        }

        BrowserExtensionStore.SessionRow session = sessions.ensureSession(userId, conversationId, deviceId);
        if (!session.deviceId().equals(deviceId)) {
            // 会话已经绑到另一台设备上：不允许"任意选最近在线设备"
            deviceId = session.deviceId();
            if (!hub.online(deviceId)) {
                return ExecResult.fail("DEVICE_OFFLINE", "该会话绑定的浏览器设备离线");
            }
        }

        String state = session.state();
        if (BrowserControlSessionService.AWAITING_TAB.equals(state)) {
            requestTabGrant(session, args);
            // 扩展会**自己打开网页**；如果用户开了「自动接管」，它打开后立刻上报授权，
            // 这里等一小会儿就能直接继续执行，不用让 Agent 白跑一轮、也不用用户再说什么。
            session = awaitGranted(session, 5_000);
            state = session.state();
            if (BrowserControlSessionService.AWAITING_TAB.equals(state)) {
                return ExecResult.fail("AWAITING_TAB",
                        "已经在你的浏览器里打开这个网页，请在扩展侧边栏点一次「授权当前标签页」"
                                + "（点完会自动开启「自动接管」，以后就不用再点了）。"
                                + "如果侧边栏显示「自动接管」已开启却仍然要授权，说明扩展还是旧版本："
                                + "到 chrome://extensions 点扩展的刷新按钮更新到最新版。");
            }
            if (BrowserControlSessionService.HUMAN_CONTROL.equals(state)) {
                return ExecResult.fail("AWAITING_TAB",
                        "网页已打开并授权，但现在是你自己控制（还没交还给 Agent）。"
                                + "点侧边栏的「交还给 Agent」后继续。");
            }
        }
        if (BrowserControlSessionService.HUMAN_CONTROL.equals(state)) {
            return ExecResult.fail("HUMAN_CONTROL",
                    "现在是你自己在操作浏览器（Agent 已暂停）。要让我继续，请在右侧面板点「交还」"
                            + "或在扩展侧边栏点「交还控制权」。");
        }
        if (BrowserControlSessionService.SUSPENDED.equals(state)) {
            return ExecResult.fail("SUSPENDED",
                    "浏览器控制已暂停（" + (session.lastError() == null ? "连接断开或调试会话结束" : session.lastError())
                            + "）。请重新确认授权后再继续。");
        }
        if (BrowserControlSessionService.REVOKED.equals(state)
                || BrowserControlSessionService.EXPIRED.equals(state)) {
            return ExecResult.fail("TAB_NOT_AUTHORIZED", "浏览器控制会话已结束，请重新授权网页。");
        }

        // 下发给扩展的命令
        String requestId = DeviceTokenService.newId("rq-");
        ObjectNode command = BrowserBridgeHub.frame("command");
        command.put("protocolVersion", properties.protocolVersion());
        command.put("requestId", requestId);
        command.put("controlSessionId", session.id());
        hub.connection(deviceId).ifPresent(c -> command.put("connectionEpoch", c.epoch().get()));
        command.put("leaseVersion", session.leaseVersion());
        command.put("pageRevision", session.pageRevision());
        command.put("action", action);
        ObjectNode target = command.putObject("target");
        if (session.tabHandle() != null) {
            target.put("tabHandle", session.tabHandle());
        }
        if (args.get("elementRef") != null) {
            target.put("elementRef", String.valueOf(args.get("elementRef")));
        }
        command.put("expectedOrigin", session.origin() == null ? "" : session.origin());
        command.set("args", BrowserBridgeHub.mapper().valueToTree(args));
        command.put("expiresAt", Instant.now().plusSeconds(15).toString());

        long startedAt = System.currentTimeMillis();
        JsonNode result;
        try {
            result = hub.request(deviceId, command, properties.commandTimeoutSeconds()).join();
        } catch (RuntimeException e) {
            String code = e instanceof BrowserBridgeHub.BridgeException be ? be.errorCode() : "COMMAND_FAILED";
            audit(requestId, session, action, "ERROR", code, System.currentTimeMillis() - startedAt);
            return ExecResult.fail(code, e.getMessage() == null ? "下发命令失败" : e.getMessage());
        }

        long duration = System.currentTimeMillis() - startedAt;
        String status = result.path("status").asText("ERROR");
        String errorCode = result.hasNonNull("errorCode") ? result.path("errorCode").asText() : null;
        String message = result.hasNonNull("message") ? result.path("message").asText() : null;
        int pageRevision = result.path("pageRevision").asInt(session.pageRevision());
        String controlState = result.path("controlState").asText(null);

        store.updateTab(session.id(), session.tabHandle(), session.origin(), session.title(), pageRevision);
        if (controlState != null && !controlState.equals(session.state())) {
            if (BrowserControlSessionService.HUMAN_CONTROL.equals(controlState)) {
                store.transition(session.id(), BrowserControlSessionService.HUMAN_CONTROL, null);
            } else if (BrowserControlSessionService.SUSPENDED.equals(controlState)) {
                store.transition(session.id(), BrowserControlSessionService.SUSPENDED, "扩展报告已暂停");
            }
        }
        audit(requestId, session, action, status, errorCode, duration);

        if (!"OK".equalsIgnoreCase(status)) {
            return ExecResult.fail(errorCode == null ? "COMMAND_FAILED" : errorCode,
                    message == null ? "浏览器没有完成这个操作" : message);
        }
        JsonNode data = result.path("data");
        String text = data.path("text").asText(null);
        String origin = data.path("origin").asText(session.origin());
        String title = data.path("title").asText(session.title());
        if (origin != null || title != null) {
            store.updateTab(session.id(), data.path("tabHandle").asText(session.tabHandle()),
                    origin, title, pageRevision);
        }
        return new ExecResult(true, null, null, text, data, origin, pageRevision, session.state(), session.id());
    }

    /** 轮询等待扩展上报授权（自动接管时是毫秒级；手动授权则等到超时返回 AWAITING_TAB）。 */
    private BrowserExtensionStore.SessionRow awaitGranted(
            BrowserExtensionStore.SessionRow initial, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        BrowserExtensionStore.SessionRow current = initial;
        while (System.currentTimeMillis() < deadline) {
            if (!BrowserControlSessionService.AWAITING_TAB.equals(current.state())) {
                return current;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return current;
            }
            current = store.sessionById(initial.id()).orElse(current);
        }
        return current;
    }

    /** 请求扩展弹「选择网页」：不阻塞，用户操作完再继续。 */
    private void requestTabGrant(BrowserExtensionStore.SessionRow session, Map<String, Object> args) {
        String requestId = DeviceTokenService.newId("rq-");
        ObjectNode command = BrowserBridgeHub.frame("command");
        command.put("protocolVersion", properties.protocolVersion());
        command.put("requestId", requestId);
        command.put("controlSessionId", session.id());
        hub.connection(session.deviceId()).ifPresent(c -> command.put("connectionEpoch", c.epoch().get()));
        command.put("leaseVersion", session.leaseVersion());
        command.put("pageRevision", session.pageRevision());
        command.put("action", "request_tab_grant");
        command.set("args", BrowserBridgeHub.mapper().valueToTree(args));
        command.put("expiresAt", Instant.now().plusSeconds(30).toString());
        hub.send(session.deviceId(), command);
    }

    private void audit(String requestId, BrowserExtensionStore.SessionRow session, String action,
                       String status, String errorCode, long durationMs) {
        try {
            store.audit(requestId, session.id(), session.userId(), session.deviceId(), action,
                    session.origin(), WRITE_ACTIONS.contains(action) ? "WRITE" : "READ",
                    status, errorCode, (int) durationMs);
        } catch (RuntimeException e) {
            log.debug("写浏览器操作审计失败：{}", e.getMessage());
        }
    }
}
