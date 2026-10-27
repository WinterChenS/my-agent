package com.myagent.schedule.service;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

/**
 * 正在执行的定时任务登记表（支持「取消执行」）。
 *
 * <p>为什么需要它：定时任务在后台线程里 {@code agent.stream(...)} 跑着，
 * 想从界面上取消，必须拿到**真正在跑的那个 Agent 实例 + 它的流订阅**。
 *
 * <p>取消分两步（与 AG-UI「停止」同一套结论）：
 * <ol>
 *   <li>{@code dispose()} 掉流订阅 —— 立刻让上游停止产出（reactor 会走 cancel 路径）；</li>
 *   <li>调官方 {@code Agent.interrupt(context)} —— 让 Agent 内部那一步也收手
 *       （光调这个不够快：它要等下一次迭代才检查）。</li>
 * </ol>
 *
 * <p>节点崩溃时这些登记会自然消失；数据库里残留的 RUNNING 由
 * {@code ScheduledTaskService.recoverStale()} 标成 ABANDONED。
 */
@Component
public class ScheduledTaskExecutionRegistry {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskExecutionRegistry.class);

    /** 一次执行的句柄。 */
    static final class Handle {
        final String taskName;
        final HarnessAgent agent;
        final RuntimeContext context;
        final AtomicReference<Disposable> subscription = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicBoolean cancelled = new AtomicBoolean(false);

        Handle(String taskName, HarnessAgent agent, RuntimeContext context) {
            this.taskName = taskName;
            this.agent = agent;
            this.context = context;
        }

        void finish() {
            // 幂等：正常结束与取消可能同时到达
            if (done.getCount() > 0) {
                done.countDown();
            }
        }
    }

    private final Map<Long, Handle> running = new ConcurrentHashMap<>();

    /** 执行「官方中断」的线程池（异步，避免取消接口等十几秒）。单测可注入同步 Executor。 */
    private final java.util.concurrent.Executor interrupter;

    public ScheduledTaskExecutionRegistry() {
        this(runnable -> {
            Thread thread = new Thread(runnable, "sched-cancel");
            thread.setDaemon(true);
            thread.start();
        });
    }

    public ScheduledTaskExecutionRegistry(java.util.concurrent.Executor interrupter) {
        this.interrupter = interrupter;
    }

    public Handle register(Long runId, String taskName, HarnessAgent agent, RuntimeContext context) {
        Handle handle = new Handle(taskName, agent, context);
        running.put(runId, handle);
        return handle;
    }

    public void unregister(Long runId) {
        if (runId != null) {
            running.remove(runId);
        }
    }

    public boolean isRunning(Long runId) {
        return runId != null && running.containsKey(runId);
    }

    public java.util.List<Long> runningRunIds() {
        return java.util.List.copyOf(running.keySet());
    }

    /**
     * 取消一次执行。
     *
     * @return true = 确实取消了一个正在跑的执行；false = 它已经结束了（"点晚了"不是错误）
     */
    public boolean cancel(Long runId) {
        Handle handle = runId == null ? null : running.get(runId);
        if (handle == null) {
            return false;
        }
        boolean first = handle.cancelled.compareAndSet(false, true);
        if (!first) {
            return true; // 已经在取消中
        }
        // 先断流，让上游立刻停止；再让 Agent 自己收手
        Disposable disposable = handle.subscription.get();
        if (disposable != null && !disposable.isDisposed()) {
            try {
                disposable.dispose();
            } catch (Exception ex) {
                log.debug("取消时 dispose 失败 runId={}: {}", runId, ex.getMessage());
            }
        }
        // 官方中断可能要等当前这一步收手（实测同步调用会让取消接口等十几秒），
        // 放到独立线程里做：取消接口先快速返回，界面不用干等。
        interrupter.execute(() -> {
            try {
                if (handle.context != null) {
                    handle.agent.interrupt(handle.context);
                } else {
                    handle.agent.interrupt();
                }
            } catch (Exception ex) {
                log.warn("取消时中断 Agent 失败 runId={}: {}", runId, ex.toString());
            }
        });
        // dispose 不会触发 onComplete，必须自己放行等待中的执行线程
        handle.finish();
        log.info("已取消定时任务执行 runId={} task={}", runId, handle.taskName);
        return true;
    }

    /** 该次执行是否被取消过（执行线程据此决定落「已取消」而不是失败）。 */
    public boolean wasCancelled(Long runId) {
        Handle handle = runId == null ? null : running.get(runId);
        return handle != null && handle.cancelled.get();
    }
}
