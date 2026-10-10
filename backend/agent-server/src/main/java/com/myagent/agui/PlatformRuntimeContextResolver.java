package com.myagent.agui;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.runtime.AguiRuntimeContextRequest;
import io.agentscope.core.agui.runtime.AguiRuntimeContextResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * 构建 AgentScope 的 {@link RuntimeContext}。
 *
 * <p>会话标识用 AG-UI 的 threadId（与平台 chat_session 一一对应）；用户身份由控制器写入
 * servlet 请求属性后在此读取（不经过可伪造的请求头）。
 */
@Component
public class PlatformRuntimeContextResolver implements AguiRuntimeContextResolver {

    /** 控制器在 nativeRequest 上设置的属性名。 */
    public static final String USER_ID_ATTRIBUTE = "myagent.userId";

    @Override
    public RuntimeContext resolve(AguiRuntimeContextRequest<?> request) {
        String sessionId = request.getInput() == null ? null : request.getInput().getThreadId();
        String userId = null;
        if (request.getNativeRequest() instanceof HttpServletRequest servletRequest) {
            Object attribute = servletRequest.getAttribute(USER_ID_ATTRIBUTE);
            if (attribute != null) {
                userId = String.valueOf(attribute);
            }
        }
        return RuntimeContext.builder().sessionId(sessionId).userId(userId).build();
    }
}
