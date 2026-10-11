package com.myagent.agui;

import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.event.AguiEventType;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AG-UI 出站流量的「终态」保护（Phase 12 修复）。
 *
 * <p>协议规定：一旦发出 `RUN_ERROR` 就是本轮终态，**之后不能再下发任何事件**。
 * 实测 AgentScope 官方扩展在错误时会先发 `RUN_ERROR`（code=INTERNAL_ERROR）、紧接着又发 `RUN_FINISHED`，
 * 而官方客户端 `@ag-ui/client` 会直接抛错：
 * <pre>Cannot send event type 'RUN_FINISHED': The run has already errored with 'RUN_ERROR'. No further events can be sent.</pre>
 * 用户看到的就是这条莫名其妙的消息（真实原因是模型端点不可达）。
 *
 * <p>这里把判断集中到一个小类里，便于单测；控制器只用它决定「这个事件要不要发」。
 */
public class AguiStreamGuard {

    private final AtomicBoolean errored = new AtomicBoolean(false);

    /**
     * 是否应该下发该事件。
     *
     * @return true=可以发；false=已进入错误终态，应丢弃
     */
    public boolean shouldSend(AguiEvent event) {
        if (errored.get()) {
            return false;
        }
        if (event != null && event.getType() == AguiEventType.RUN_ERROR) {
            errored.set(true);
        }
        return true;
    }

    /**
     * 我们自己的兜底错误事件是否还要发（官方已发过就不再重复）。
     *
     * @return true=我们还没发过错误，可以发；false=已有错误事件，跳过
     */
    public boolean shouldSendFallbackError() {
        return !errored.getAndSet(true);
    }

    /** 本轮是否已经进入错误终态。 */
    public boolean isErrored() {
        return errored.get();
    }
}
