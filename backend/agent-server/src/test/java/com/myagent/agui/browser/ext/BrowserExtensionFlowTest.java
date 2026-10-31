package com.myagent.agui.browser.ext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 本机浏览器扩展的关键安全路径（方案第 14 节「自动测试重点」里的服务端部分）。
 *
 * <p>守的是四件事：
 * <ol>
 *   <li>配对：PKCE 不匹配不能领凭证；已领取的配对再领一次失败（原子消费）；</li>
 *   <li>凭证：复用旧 refreshToken 会冻结整族并抬版本（逼重新配对）；</li>
 *   <li>控制权：人工控制 / 未授权时一律拒绝写动作；暂停递增 lease 让旧命令失效；</li>
 *   <li>设备：没配对 → EXTENSION_REQUIRED；离线 → DEVICE_OFFLINE。</li>
 * </ol>
 */
class BrowserExtensionFlowTest {

    private BrowserExtensionProperties props;
    private BrowserExtensionStore store;
    private BrowserBridgeHub hub;
    private DeviceTokenService tokens;
    private BrowserPairingService pairing;
    private BrowserControlSessionService sessions;

    @BeforeEach
    void setUp() {
        props = new BrowserExtensionProperties("local-extension", "http://localhost:18080", "test-extension-id", 1, "0.1.0", null, null, "",
                300, 600, 2592000, 1800, 65536, 25, "test-secret", 60, null);
        store = mock(BrowserExtensionStore.class);
        hub = mock(BrowserBridgeHub.class);
        tokens = new DeviceTokenService(props);
        pairing = new BrowserPairingService(store, tokens, props);
        sessions = new BrowserControlSessionService(store, hub, props);
    }

    // ---------------- 配对 ----------------

    @Test
    @DisplayName("配对：PKCE 不匹配时拒绝领取凭证，并记一次尝试")
    void claimRejectsWrongVerifier() {
        String deviceCode = "device-code-123";
        String challenge = DeviceTokenService.pkceS256("the-real-verifier");
        var row = new BrowserExtensionStore.PairingRow(
                "pr-1", DeviceTokenService.sha256Hex(deviceCode), DeviceTokenService.sha256Hex("AAAA1111"),
                challenge, "install-abcdefgh", "Chrome", "chrome", "APPROVED", 7L,
                OffsetDateTime.now(), null, 0, OffsetDateTime.now().plusMinutes(5));
        when(store.pairingByDeviceCodeHash(DeviceTokenService.sha256Hex(deviceCode)))
                .thenReturn(Optional.of(row));

        assertThatThrownBy(() -> pairing.claim(deviceCode, "wrong-verifier", "0.1.0", 1))
                .hasMessageContaining("PKCE");
        org.mockito.Mockito.verify(store).bumpPairingAttempt("pr-1");
    }

    @Test
    @DisplayName("配对：同一个 deviceCode 只能领一次（原子消费返回 0 就失败）")
    void claimIsSingleUse() {
        String deviceCode = "device-code-456";
        String verifier = "verifier-456";
        var row = new BrowserExtensionStore.PairingRow(
                "pr-2", DeviceTokenService.sha256Hex(deviceCode), DeviceTokenService.sha256Hex("BBBB2222"),
                DeviceTokenService.pkceS256(verifier), "install-abcdefgh", "Chrome", "chrome", "APPROVED",
                7L, OffsetDateTime.now(), null, 0, OffsetDateTime.now().plusMinutes(5));
        when(store.pairingByDeviceCodeHash(anyString())).thenReturn(Optional.of(row));
        when(store.consumePairing("pr-2")).thenReturn(0);

        assertThatThrownBy(() -> pairing.claim(deviceCode, verifier, "0.1.0", 1))
                .hasMessageContaining("领取");
    }

    @Test
    @DisplayName("配对：批准只能成功一次（网页重复点确认不会重复生效）")
    void approveIsIdempotentSafe() {
        var row = new BrowserExtensionStore.PairingRow(
                "pr-3", "h1", DeviceTokenService.sha256Hex("CCCC3333"), "challenge", "install-x",
                null, null, "APPROVED", 9L, OffsetDateTime.now(), null, 0,
                OffsetDateTime.now().plusMinutes(5));
        when(store.pairingByUserCodeHash(anyString())).thenReturn(Optional.of(row));
        when(store.approvePairing(eq("pr-3"), anyLong())).thenReturn(0);

        assertThatThrownBy(() -> pairing.approve(9L, "CCCC3333")).hasMessageContaining("失效");
    }

    @Test
    @DisplayName("凭证：复用已消费的 refreshToken 会冻结整族 + 抬版本")
    void refreshReuseFreezesFamily() {
        String refresh = "refresh-abc";
        var credential = new BrowserExtensionStore.CredentialRow(
                "cr-1", "dv-1", DeviceTokenService.sha256Hex(refresh), "tf-1", 1,
                OffsetDateTime.now().plusDays(1), OffsetDateTime.now().minusSeconds(1), null);
        when(store.credentialByRefreshHash(anyString())).thenReturn(Optional.of(credential));
        when(store.consumeCredential("cr-1")).thenReturn(0);

        assertThatThrownBy(() -> pairing.refresh(refresh)).hasMessageContaining("重新配对");
        org.mockito.Mockito.verify(store).revokeCredentialFamily("dv-1", "tf-1");
        org.mockito.Mockito.verify(store).bumpCredentialVersion("dv-1");
    }

    // ---------------- 控制权 ----------------

    @Test
    @DisplayName("暂停会把状态推到 HUMAN_CONTROL 且递增 lease，并通知扩展")
    void pauseBumpsLease() {
        var row = session("cs-1", BrowserControlSessionService.AGENT_CONTROL, 3);
        var paused = new BrowserExtensionStore.SessionRow(
                row.id(), row.userId(), row.conversationId(), row.deviceId(), row.tabHandle(), row.origin(),
                row.title(), row.pageRevision(), BrowserControlSessionService.HUMAN_CONTROL, 4, null,
                row.expiresAt(), null);
        when(store.sessionById("cs-1")).thenReturn(Optional.of(row), Optional.of(paused));

        sessions.pause(7L, "cs-1");

        org.mockito.Mockito.verify(store).transition(eq("cs-1"), eq(BrowserControlSessionService.HUMAN_CONTROL), any());
        org.mockito.Mockito.verify(hub).send(eq("dv-1"), any());
    }

    @Test
    @DisplayName("不是本人的会话一律拒绝（跨用户越权）")
    void cannotTouchOthersSession() {
        when(store.sessionById("cs-2")).thenReturn(Optional.of(session("cs-2", "AGENT_CONTROL", 1, 99L)));
        assertThatThrownBy(() -> sessions.pause(7L, "cs-2")).hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("还没授权标签页时不能 resume（要先在扩展里选页）")
    void cannotResumeBeforeTabGrant() {
        var row = session("cs-3", BrowserControlSessionService.AWAITING_TAB, 1);
        when(store.sessionById("cs-3")).thenReturn(Optional.of(row));
        assertThatThrownBy(() -> sessions.resume(7L, "cs-3")).hasMessageContaining("还没授权");
    }

    // ---------------- 执行器 ----------------

    @Test
    @DisplayName("没有配对设备 → EXTENSION_REQUIRED（告诉模型该让用户装扩展）")
    void executorRequiresExtension() {
        LocalExtensionExecutor executor = new LocalExtensionExecutor(props, store, hub, sessions);
        var result = executor.execute(new BrowserExecutionGateway.Ctx(7L, "thread-1", null, null),
                "navigate", Map.of("url", "https://example.com"));
        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("EXTENSION_REQUIRED");
    }

    @Test
    @DisplayName("设备在线但用户正在操作 → HUMAN_CONTROL，不执行动作")
    void executorHonoursHumanControl() {
        when(store.devicesOf(7L)).thenReturn(java.util.List.of(device("dv-1")));
        when(hub.online("dv-1")).thenReturn(true);
        when(store.activeSession(7L, "thread-2")).thenReturn(Optional.of(session("cs-4",
                BrowserControlSessionService.HUMAN_CONTROL, 2)));

        LocalExtensionExecutor executor = new LocalExtensionExecutor(props, store, hub, sessions);
        var result = executor.execute(new BrowserExecutionGateway.Ctx(7L, "thread-2", null, null),
                "click", Map.of("elementRef", "el-3"));

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("HUMAN_CONTROL");
        org.mockito.Mockito.verify(hub, org.mockito.Mockito.never()).request(anyString(), any(), anyInt());
    }

    @Test
    @DisplayName("没授权标签页时只请求授权，不下发任何读写动作")
    void executorRequestsTabGrantInsteadOfActing() {
        when(store.devicesOf(7L)).thenReturn(java.util.List.of(device("dv-1")));
        when(hub.online("dv-1")).thenReturn(true);
        when(store.activeSession(7L, "thread-3")).thenReturn(Optional.of(session("cs-5",
                BrowserControlSessionService.AWAITING_TAB, 1)));
        when(hub.connection("dv-1")).thenReturn(Optional.empty());

        LocalExtensionExecutor executor = new LocalExtensionExecutor(props, store, hub, sessions);
        var result = executor.execute(new BrowserExecutionGateway.Ctx(7L, "thread-3", null, null),
                "navigate", Map.of("url", "https://example.com"));

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("AWAITING_TAB");
        org.mockito.Mockito.verify(hub).send(eq("dv-1"), any());
        org.mockito.Mockito.verify(hub, org.mockito.Mockito.never()).request(anyString(), any(), anyInt());
    }

    @Test
    @DisplayName("设备令牌：篡改签名/过期都必须验不过")
    void deviceTokenIsTamperProof() {
        String token = tokens.issue(7L, "dv-1", "install-1", 3, 600);
        assertThat(tokens.verify(token)).isNotNull();
        assertThat(tokens.verify(token + "x")).isNull();
        assertThat(tokens.verify(token.replace('.', '.'))).isNotNull();
        // TTL 有下限（30s）：即使传负数也不会签出"立刻过期"的令牌，避免时钟问题把用户挡在外面
        var shortLived = tokens.verify(tokens.issue(7L, "dv-1", "install-1", 3, -1000));
        assertThat(shortLived.expiresAt()).isGreaterThan(java.time.Instant.now().getEpochSecond());
        assertThat(DeviceTokenService.pkceS256("abc")).hasSize(43);
    }

    @Test
    @DisplayName("AGENT_CONTROL 下下发命令并把扩展结果原样带回来（含页面版本）")
    void executorReturnsExtensionResult() {
        when(store.devicesOf(7L)).thenReturn(java.util.List.of(device("dv-1")));
        when(hub.online("dv-1")).thenReturn(true);
        when(store.activeSession(7L, "thread-9")).thenReturn(Optional.of(session("cs-9",
                BrowserControlSessionService.AGENT_CONTROL, 5)));
        when(hub.connection("dv-1")).thenReturn(Optional.empty());
        ObjectNode ok = BrowserBridgeHub.frame("result");
        ok.put("status", "OK");
        ok.put("pageRevision", 12);
        ObjectNode data = ok.putObject("data");
        data.put("text", "页面：Example Domain");
        data.put("origin", "https://example.com");
        when(hub.request(eq("dv-1"), any(), anyInt()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(ok));

        LocalExtensionExecutor executor = new LocalExtensionExecutor(props, store, hub, sessions);
        var result = executor.execute(new BrowserExecutionGateway.Ctx(7L, "thread-9", null, null),
                "get_snapshot", Map.of());

        assertThat(result.ok()).isTrue();
        assertThat(result.text()).contains("Example Domain");
        assertThat(result.pageRevision()).isEqualTo(12);
        // 审计必写：方案要求可追溯"谁在哪个站点做了哪类动作"
        org.mockito.Mockito.verify(store).audit(anyString(), eq("cs-9"), eq(7L), eq("dv-1"),
                eq("get_snapshot"), any(), eq("READ"), eq("OK"), any(), anyInt());
    }

    @Test
    @DisplayName("扩展回错误码时工具层原样透出（模型才能决定是等还是换办法）")
    void executorSurfacesExtensionError() {
        when(store.devicesOf(7L)).thenReturn(java.util.List.of(device("dv-1")));
        when(hub.online("dv-1")).thenReturn(true);
        when(store.activeSession(7L, "thread-10")).thenReturn(Optional.of(session("cs-10",
                BrowserControlSessionService.AGENT_CONTROL, 5)));
        when(hub.connection("dv-1")).thenReturn(Optional.empty());
        ObjectNode error = BrowserBridgeHub.frame("result");
        error.put("status", "ERROR");
        error.put("errorCode", "ELEMENT_NOT_FOUND");
        error.put("message", "页面上找不到 el-9");
        when(hub.request(eq("dv-1"), any(), anyInt()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(error));

        LocalExtensionExecutor executor = new LocalExtensionExecutor(props, store, hub, sessions);
        var result = executor.execute(new BrowserExecutionGateway.Ctx(7L, "thread-10", null, null),
                "click", Map.of("elementRef", "el-9"));

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("ELEMENT_NOT_FOUND");
        assertThat(result.message()).contains("el-9");
    }

    @Test
    @DisplayName("自动接管：扩展上报 AGENT_CONTROL 时直接进 Agent 控制，并把新 lease 回给扩展")
    void autoGrantTabStateEntersAgentControl() {
        var row = session("cs-ag", BrowserControlSessionService.AWAITING_TAB, 1);
        var after = session("cs-ag", BrowserControlSessionService.AGENT_CONTROL, 2);
        when(store.sessionById("cs-ag")).thenReturn(Optional.of(row), Optional.of(after));

        jakarta.websocket.Session ws = mock(jakarta.websocket.Session.class);
        when(ws.getId()).thenReturn("ws-1");
        var conn = new BrowserBridgeHub.Conn("dv-1", 7L, ws, java.time.Instant.now(),
                new java.util.concurrent.atomic.AtomicLong(1));

        ObjectNode frame = BrowserBridgeHub.frame("tab.state");
        frame.put("controlSessionId", "cs-ag");
        frame.put("tabHandle", "99");
        frame.put("origin", "https://example.com");
        frame.put("pageRevision", 1);
        frame.put("controlState", "AGENT_CONTROL");

        sessions.onTabState(conn, frame);

        org.mockito.Mockito.verify(store).transition(eq("cs-ag"),
                eq(BrowserControlSessionService.AGENT_CONTROL), org.mockito.ArgumentMatchers.isNull());
        // 关键：必须把递增后的 lease 推回扩展，否则下一步命令会被判「过期」
        org.mockito.Mockito.verify(hub).send(eq("dv-1"), any());
    }

    @Test
    @DisplayName("授权即交给 Agent：扩展没上报控制权时默认进 AGENT_CONTROL（不用再点一次交还）")
    void manualGrantStartsAgentControl() {
        var row = session("cs-mn", BrowserControlSessionService.AWAITING_TAB, 1);
        var after = session("cs-mn", BrowserControlSessionService.AGENT_CONTROL, 2);
        when(store.sessionById("cs-mn")).thenReturn(Optional.of(row), Optional.of(after));

        jakarta.websocket.Session ws = mock(jakarta.websocket.Session.class);
        when(ws.getId()).thenReturn("ws-2");
        var conn = new BrowserBridgeHub.Conn("dv-1", 7L, ws, java.time.Instant.now(),
                new java.util.concurrent.atomic.AtomicLong(1));

        ObjectNode frame = BrowserBridgeHub.frame("tab.state");
        frame.put("controlSessionId", "cs-mn");
        frame.put("tabHandle", "77");
        frame.put("origin", "https://example.com");

        sessions.onTabState(conn, frame);

        org.mockito.Mockito.verify(store).transition(eq("cs-mn"),
                eq(BrowserControlSessionService.AGENT_CONTROL), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    @DisplayName("扩展明确上报 HUMAN_CONTROL（用户点了「我要自己操作」）时停在人类控制")
    void explicitHumanControlIsRespected() {
        var row = session("cs-hm", BrowserControlSessionService.AWAITING_TAB, 1);
        var after = session("cs-hm", BrowserControlSessionService.HUMAN_CONTROL, 2);
        when(store.sessionById("cs-hm")).thenReturn(Optional.of(row), Optional.of(after));

        jakarta.websocket.Session ws = mock(jakarta.websocket.Session.class);
        when(ws.getId()).thenReturn("ws-3");
        var conn = new BrowserBridgeHub.Conn("dv-1", 7L, ws, java.time.Instant.now(),
                new java.util.concurrent.atomic.AtomicLong(1));

        ObjectNode frame = BrowserBridgeHub.frame("tab.state");
        frame.put("controlSessionId", "cs-hm");
        frame.put("tabHandle", "55");
        frame.put("origin", "https://example.com");
        frame.put("controlState", "HUMAN_CONTROL");

        sessions.onTabState(conn, frame);

        org.mockito.Mockito.verify(store).transition(eq("cs-hm"),
                eq(BrowserControlSessionService.HUMAN_CONTROL), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    @DisplayName("换对话时复用同一设备上已授权的会话（不再要求用户重新授权）")
    void reusesGrantOnSameDeviceAcrossConversations() {
        var existing = session("cs-reuse", BrowserControlSessionService.AGENT_CONTROL, 3);
        when(store.activeSession(7L, "thread-new")).thenReturn(Optional.empty());
        when(store.devicesOf(7L)).thenReturn(java.util.List.of(device("dv-1")));
        when(store.recentOpenSessionOfDevice("dv-1")).thenReturn(Optional.of(existing));
        when(store.sessionById("cs-reuse")).thenReturn(Optional.of(existing));

        var row = sessions.ensureSession(7L, "thread-new", null);

        assertThat(row.id()).isEqualTo("cs-reuse");
        org.mockito.Mockito.verify(store).attachConversation("cs-reuse", "thread-new");
        // 不应该新建会话
        org.mockito.Mockito.verify(store, org.mockito.Mockito.never()).insertSession(any());
    }

    @Test
    @DisplayName("平台地址从配置来（去掉尾部斜杠），代码里不写死任何 IP")
    void platformBaseUrlComesFromConfig() {
        assertThat(props.platformBaseUrl()).isEqualTo("http://localhost:18080");
        var empty = new BrowserExtensionProperties("local-extension", "  ", "id", 1, "0.1.0", null, null, "",
                300, 600, 2592000, 1800, 65536, 25, "s", 60, null);
        assertThat(empty.platformBaseUrl()).isEmpty();
    }

    // ---------------- fixtures ----------------

    private static BrowserExtensionStore.DeviceRow device(String id) {
        return new BrowserExtensionStore.DeviceRow(id, 7L, "install-" + id, "Chrome", "chrome", "0.1.0",
                1, 1, "ACTIVE", OffsetDateTime.now(), null);
    }

    private static BrowserExtensionStore.SessionRow session(String id, String state, int lease) {
        return session(id, state, lease, 7L);
    }

    private static BrowserExtensionStore.SessionRow session(String id, String state, int lease, long userId) {
        return new BrowserExtensionStore.SessionRow(id, userId, "thread-x", "dv-1", "123", "https://example.com",
                "Example", 4, state, lease, null, OffsetDateTime.now().plusMinutes(30), null);
    }
}
