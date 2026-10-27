package com.myagent.schedule.service;

import com.myagent.schedule.entity.ScheduledTask;
import jakarta.annotation.PreDestroy;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时任务调度器：周期性 tick，抢到任务后丢给线程池执行。
 *
 * <p>分布式：**每个节点都跑 tick**，靠 {@link ScheduledTaskService#claimDue} 的
 * {@code SELECT … FOR UPDATE SKIP LOCKED} 保证一个任务只会被一个节点认领——
 * 不引入任何需要额外部署的组件（就用现有 PostgreSQL）。
 *
 * <p>隔离：执行用独立有界线程池，不占 Web 线程；池满时本轮 tick 跳过（下轮再试），
 * 避免任务堆积把服务拖垮。
 */
@Component
public class ScheduledTaskDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskDispatcher.class);

    private final ScheduledTaskService taskService;
    private final ScheduledTaskExecutor executor;
    private final boolean enabled;
    private final int batchSize;
    /** 节点标识：多副本排查/日志用（host + pid）。 */
    private final String nodeId;

    private final ThreadPoolExecutor workers;

    public ScheduledTaskDispatcher(
            ScheduledTaskService taskService,
            ScheduledTaskExecutor executor,
            @Value("${myagent.schedule.enabled:true}") boolean enabled,
            @Value("${myagent.schedule.batch-size:10}") int batchSize,
            @Value("${myagent.schedule.worker-threads:4}") int workerThreads,
            @Value("${myagent.schedule.queue-capacity:100}") int queueCapacity) {
        this.taskService = taskService;
        this.executor = executor;
        this.enabled = enabled;
        this.batchSize = Math.min(Math.max(batchSize, 1), 100);
        this.nodeId = nodeId();
        AtomicInteger seq = new AtomicInteger();
        this.workers = new ThreadPoolExecutor(
                Math.max(1, workerThreads),
                Math.max(1, workerThreads),
                60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(1, queueCapacity)),
                r -> {
                    Thread t = new Thread(r, "scheduled-task-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        if (enabled) {
            log.info("定时任务调度器已启用 node={} batch={} workers={}",
                    nodeId, this.batchSize, workerThreads);
        } else {
            log.info("定时任务调度器已禁用（myagent.schedule.enabled=false）");
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!enabled) {
            return;
        }
        // 启动时先回收上次异常退出留下的租约与 RUNNING 记录
        runGuarded(this::recover);
    }

    /** 每 10 秒 tick 一次（可用配置覆盖）。 */
    @Scheduled(fixedDelayString = "${myagent.schedule.tick-millis:10000}",
            initialDelayString = "${myagent.schedule.tick-millis:10000}")
    public void tick() {
        if (!enabled) {
            return;
        }
        runGuarded(() -> {
            List<ScheduledTask> due = taskService.claimDue(batchSize);
            if (due.isEmpty()) {
                return;
            }
            log.info("定时任务本轮认领 {} 条 node={}", due.size(), nodeId);
            for (ScheduledTask task : due) {
                submit(task);
            }
        });
    }

    /** 手动立即执行（管理页 / 工具）。 */
    public void runNow(ScheduledTask task) {
        submit(task, "MANUAL");
    }

    private void submit(ScheduledTask task) {
        submit(task, "CRON");
    }

    private void submit(ScheduledTask task, String triggerType) {
        try {
            workers.execute(() -> {
                try {
                    executor.execute(task, nodeId, triggerType);
                } catch (Exception ex) {
                    log.error("定时任务执行线程异常 taskId={}", task.getId(), ex);
                }
            });
        } catch (RejectedExecutionException ex) {
            // 池满：把任务放回去（清租约 + 下轮再抢），不要丢掉
            log.warn("定时任务线程池已满，稍后重试 taskId={} name={}", task.getId(), task.getName());
            runGuarded(() -> taskService.requeue(task.getId()));
        }
    }

    private void recover() {
        taskService.recoverStale();
    }

    private void runGuarded(Runnable action) {
        try {
            action.run();
        } catch (Exception ex) {
            log.warn("定时任务调度 tick 失败: {}", ex.toString());
        }
    }

    private static String nodeId() {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception ex) {
            host = "unknown";
        }
        long pid = 0;
        try {
            String name = ManagementFactory.getRuntimeMXBean().getName();
            int at = name.indexOf('@');
            pid = Long.parseLong(at > 0 ? name.substring(0, at) : name);
        } catch (Exception ignored) {
            // pid 拿不到就用 0
        }
        return host + ":" + pid;
    }

    public String nodeIdValue() {
        return nodeId;
    }

    @PreDestroy
    public void shutdown() {
        workers.shutdown();
        try {
            if (!workers.awaitTermination(10, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();
        }
    }
}
