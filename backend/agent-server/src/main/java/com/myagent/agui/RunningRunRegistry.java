package com.myagent.agui;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 运行中的 run 登记表（Phase 12 停止执行 + Phase 14 刷新接续）。
 *
 * <p>回答两个问题：
 * <ol>
 *   <li><b>停止</b>：停止必须打到**真正在跑的那个 Agent 实例**上（平台按 (agentDbId, 会话级技能) 缓存实例，
 *       不能重新 build 去中断），所以由官方管线实际使用的两个 resolver 在解析时把「实例 + RuntimeContext」登记进来。</li>
 *   <li><b>刷新接续</b>：页面刷新/切换时 SSE 会断开，但这一轮**不应该被丢掉**。这里保存「已生成的文本」，
 *       并区分「还有客户端在跟」与「已断开」；断开后进入**宽限期**，宽限期内有人回来（轮询 /run）就继续跑，
 *       一直没人回来才中断，并且中断后仍会把已生成内容落库（不丢答案）。</li>
 * </ol>
 *
 * <p>中断一律走框架官方入口 {@code Agent.interrupt(...)}（门禁 G8：不自研中断逻辑）。
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
        /** 已生成的助手文本（供刷新后接续显示）。 */
        final StringBuilder text = new StringBuilder();
        volatile String runId;
        /** run 是否已进入收尾（落库完成）。 */
        volatile boolean finished;
        /** 0 = 有客户端在跟；>0 = 最后一次断开的时间戳。 */
        volatile long detachedAt;
        /** 宽限期定时任务（断线后启动，回来时取消）。 */
        volatile ScheduledFuture<?> graceTask;
    }

    /** 给前端轮询用的快照。 */
    public record RunSnapshot(boolean running, String text, String runId, boolean stopped) {}

    private final Map<String, RunHandle> runs = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "run-grace");
                t.setDaemon(true);
                return t;
            });

    /**
     * 「断开后最多再跑多久」的可选上限（毫秒）。**默认 0 = 不限制**：
     * 后端把这一轮跑完为止，前端只是"观众"，随时可以进来接上（用户明确要求这个语义）。
     * 想省钱时可设成正值（如 600000 = 断开 10 分钟后仍未有人回来才中断并落库）。
     */
    private final long maxDetachedMillis;

    public RunningRunRegistry(
            @Value("${myagent.run.max-detached-millis:0}") long maxDetachedMillis) {
        this.maxDetachedMillis = Math.max(0L, maxDetachedMillis);
    }

    /** 开始一轮 run：登记实例与上下文，并清空上一轮的残留状态。 */
    public void beginRun(String threadId, String runId) {
        if (threadId == null) {
            return;
        }
        RunHandle handle = new RunHandle();
        handle.runId = runId;
        runs.put(threadId, handle);
    }

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

    /** 同步已生成的助手文本（每收到一个事件后调用，供刷新后接续显示）。 */
    public void syncText(String threadId, CharSequence text) {
        RunHandle handle = threadId == null ? null : runs.get(threadId);
        if (handle != null && text != null) {
            handle.text.setLength(0);
            handle.text.append(text);
        }
    }

    /** 有客户端在跟（轮询 /run 或重新发起 SSE）：取消宽限期。 */
    public void markAttached(String threadId) {
        RunHandle handle = threadId == null ? null : runs.get(threadId);
        if (handle == null) {
            return;
        }
        handle.detachedAt = 0L;
        ScheduledFuture<?> task = handle.graceTask;
        if (task != null) {
            task.cancel(false);
            handle.graceTask = null;
        }
    }

    /**
     * 客户端断开（刷新 / 切页 / 关标签页）。
     *
     * <p>**默认不中断**：这一轮由后端负责跑完（用户明确要求：无论前端在不在，后端都继续跑），
     * 前端只是观众，随时可以再进来接上。只有在显式配置了 {@code myagent.run.max-detached-millis}
     * （> 0）时才在超过该时长且仍无人回来时中断，并且中断后照样落库。
     */
    public void markDetached(String threadId) {
        RunHandle handle = threadId == null ? null : runs.get(threadId);
        if (handle == null || handle.finished) {
            return;
        }
        handle.detachedAt = System.currentTimeMillis();
        if (maxDetachedMillis <= 0 || handle.graceTask != null) {
            return; // 默认不设上限：让后端把这一轮跑完
        }
        handle.graceTask = scheduler.schedule(
                () -> expireIfStillDetached(threadId), maxDetachedMillis, TimeUnit.MILLISECONDS);
    }

    private void expireIfStillDetached(String threadId) {
        RunHandle handle = runs.get(threadId);
        if (handle == null || handle.finished || handle.detachedAt == 0L) {
            return;
        }
        long idle = System.currentTimeMillis() - handle.detachedAt;
        if (idle < maxDetachedMillis) {
            // 期间有人回来又走了：再等一轮
            handle.graceTask = scheduler.schedule(
                    () -> expireIfStillDetached(threadId), maxDetachedMillis - idle, TimeUnit.MILLISECONDS);
            return;
        }
        log.info("run 断开已超过配置上限（{}ms）且无人接续，中断并落库: threadId={}", maxDetachedMillis, threadId);
        interrupt(threadId);
    }

    /** run 收尾完成（已落库）：清理登记。 */
    public void markFinished(String threadId) {
        RunHandle handle = threadId == null ? null : runs.get(threadId);
        if (handle != null) {
            handle.finished = true;
            ScheduledFuture<?> task = handle.graceTask;
            if (task != null) {
                task.cancel(false);
            }
        }
    }

    /** run 结束（正常/出错）时清理。 */
    public void clear(String threadId) {
        RunHandle handle = threadId == null ? null : runs.remove(threadId);
        if (handle != null && handle.graceTask != null) {
            handle.graceTask.cancel(false);
        }
    }

    /** 本次 run 是否被用户停止过（供落库用）。 */
    public boolean consumeStopped(String threadId) {
        RunHandle handle = threadId == null ? null : runs.get(threadId);
        return handle != null && handle.stopped.get();
    }

    /** 供前端轮询：当前是否在跑、已生成多少文本。 */
    public RunSnapshot snapshot(String threadId) {
        RunHandle handle = threadId == null ? null : runs.get(threadId);
        if (handle == null || handle.finished || handle.agent == null) {
            return new RunSnapshot(false, "", null, false);
        }
        return new RunSnapshot(true, handle.text.toString(), handle.runId, handle.stopped.get());
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
        return handle != null && handle.agent != null && !handle.finished;
    }

    Optional<Agent> agentOf(String threadId) {
        RunHandle handle = runs.get(threadId);
        return handle == null ? Optional.empty() : Optional.ofNullable(handle.agent);
    }
}
