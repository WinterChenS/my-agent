package com.myagent.agui;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 运行中的 run 登记表（Phase 12 / D25：停止执行）。
 *
 * <p>停止必须打到**真正在跑的那个 Agent 实例**上：平台按 (agentDbId, 会话级技能) 做实例缓存，
 * 同一个 Agent 可能有不同的缓存键，所以不能"按 agent_id 再 build 一个"来中断（拿到的可能是另一个实例）。
 * 因此由官方管线实际使用的两个 resolver 在解析时把「实例 + RuntimeContext」登记进来，停止入口按 threadId 取。
 *
 * <p>中断一律走框架官方入口 {@code Agent.interrupt(RuntimeContext)}（门禁 G8：不自研中断逻辑）。
 */
@Component
public class RunningRunRegistry {

    private static final Logger log = LoggerFactory.getLogger(RunningRunRegistry.class);

    /** 单个 run 的句柄。 */
    private static final class RunHandle {
        volatile Agent agent;
        volatile RuntimeContext context;
        /** 本次 run 是否已被用户手动停止（用于落库「已停止」标记）。 */
        final AtomicBoolean stopped = new AtomicBoolean(false);
    }

    private final Map<String, RunHandle> runs = new ConcurrentHashMap<>();

    /** 官方 AgentResolver 解析出实例时登记。 */
    public void registerAgent(String threadId, Agent agent) {
        if (threadId == null || agent == null) {
            return;
        }
        runs.computeIfAbsent(threadId, key -> new RunHandle()).agent = agent;
    }

    /** 官方 RuntimeContextResolver 解析出上下文时登记。 */
    public void registerContext(String threadId, RuntimeContext context) {
        if (threadId == null || context == null) {
            return;
        }
        runs.computeIfAbsent(threadId, key -> new RunHandle()).context = context;
    }

    /** run 结束（正常/出错）时清理。 */
    public void clear(String threadId) {
        if (threadId != null) {
            runs.remove(threadId);
        }
    }

    /** 本次 run 是否被用户停止过（读取并清除标记，供落库用）。 */
    public boolean consumeStopped(String threadId) {
        RunHandle handle = threadId == null ? null : runs.get(threadId);
        return handle != null && handle.stopped.get();
    }

    /**
     * 中断某个会话正在跑的 run。
     *
     * @return true=确实发出了中断（有运行中的 run）；false=当前没有运行中的 run
     */
    public boolean interrupt(String threadId) {
        RunHandle handle = threadId == null ? null : runs.get(threadId);
        if (handle == null || handle.agent == null) {
            return false;
        }
        handle.stopped.set(true);
        RuntimeContext context = handle.context;
        try {
            // Agent 接口只有 interrupt()/interrupt(Msg)；带上下文的 interrupt 在 ReActAgent 上（HarnessAgent 继承它）
            if (context != null && handle.agent instanceof io.agentscope.core.ReActAgent reactAgent) {
                reactAgent.interrupt(context);
            } else {
                handle.agent.interrupt();
            }
            log.info("已请求中断 run threadId={}（官方 Agent.interrupt）", threadId);
            return true;
        } catch (Exception ex) {
            log.warn("中断 run 失败 threadId={}: {}", threadId, ex.toString());
            return false;
        }
    }

    /** 仅供测试/诊断：当前是否有登记在案的 run。 */
    public boolean isRunning(String threadId) {
        RunHandle handle = threadId == null ? null : runs.get(threadId);
        return handle != null && handle.agent != null;
    }

    Optional<Agent> agentOf(String threadId) {
        RunHandle handle = runs.get(threadId);
        return handle == null ? Optional.empty() : Optional.ofNullable(handle.agent);
    }
}
