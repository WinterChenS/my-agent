package com.myagent.agui.browser.ext;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * 浏览器执行器抽象（方案 12.1）。
 *
 * <p>工具层只依赖这个接口，不直接碰 {@code BrowserSessionRegistry} 或 WebSocket 连接。
 * 这样「本机扩展」与「服务端无头 Chromium」可以按配置切换，Agent 侧的工具名与参数不变。
 */
public interface BrowserExecutionGateway {

    String mode();

    ExecResult execute(Ctx ctx, String action, Map<String, Object> args);

    /** 调用上下文：谁、哪个会话、哪台设备。设备为空表示"用该用户默认在线设备"。 */
    record Ctx(long userId, String conversationId, String deviceId, String sessionId) {}

    record ExecResult(
            boolean ok,
            String errorCode,
            String message,
            String text,
            JsonNode data,
            String origin,
            int pageRevision,
            String controlState,
            String sessionId) {

        public static ExecResult fail(String errorCode, String message) {
            return new ExecResult(false, errorCode, message, null, null, null, 0, null, null);
        }

        public static ExecResult ok(String text, JsonNode data, String sessionId) {
            return new ExecResult(true, null, null, text, data, null, 0, null, sessionId);
        }
    }
}
