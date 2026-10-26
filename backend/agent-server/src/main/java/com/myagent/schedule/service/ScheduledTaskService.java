package com.myagent.schedule.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.myagent.agentconfig.entity.Agent;
import com.myagent.agentconfig.mapper.AgentMapper;
import com.myagent.chat.entity.ChatSession;
import com.myagent.chat.mapper.ChatSessionMapper;
import com.myagent.chat.service.ChatSessionService;
import com.myagent.common.BizException;
import com.myagent.schedule.dto.ScheduledTaskRequest;
import com.myagent.schedule.entity.ScheduledTask;
import com.myagent.schedule.entity.ScheduledTaskRun;
import com.myagent.schedule.mapper.ScheduledTaskMapper;
import com.myagent.schedule.mapper.ScheduledTaskRunMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 定时任务的定义维护 + 分布式抢占 + 收尾记账。
 *
 * <p>分工：本类只管"任务定义与抢占"，真正跑 agent 在 {@code ScheduledTaskExecutor}。
 */
@Service
public class ScheduledTaskService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskService.class);

    /** 连续失败多少次自动暂停（并通知创建人）。 */
    static final int AUTO_PAUSE_AFTER_FAILURES = 3;

    /** 落后超过多少分钟算「错过」触发（超过后按 misfirePolicy 决定补跑还是跳过）。 */
    static final int MISFIRE_GRACE_MINUTES = 5;

    private final ScheduledTaskMapper taskMapper;
    private final ScheduledTaskRunMapper runMapper;
    private final AgentMapper agentMapper;
    private final ChatSessionMapper sessionMapper;
    private final ChatSessionService sessionService;
    private final com.myagent.schedule.mapper.ScheduledTaskRunQueryMapper runQueryMapper;
    private final long leaseMinutes;

    public ScheduledTaskService(
            ScheduledTaskMapper taskMapper,
            ScheduledTaskRunMapper runMapper,
            AgentMapper agentMapper,
            ChatSessionMapper sessionMapper,
            ChatSessionService sessionService,
            com.myagent.schedule.mapper.ScheduledTaskRunQueryMapper runQueryMapper,
            @Value("${myagent.schedule.lease-minutes:10}") long leaseMinutes) {
        this.taskMapper = taskMapper;
        this.runMapper = runMapper;
        this.agentMapper = agentMapper;
        this.sessionMapper = sessionMapper;
        this.sessionService = sessionService;
        this.runQueryMapper = runQueryMapper;
        this.leaseMinutes = Math.max(1, leaseMinutes);
    }

    // ===================== 查询 =====================

    public List<ScheduledTask> list(Long createdByUserId) {
        return taskMapper.selectList(Wrappers.<ScheduledTask>lambdaQuery()
                .eq(createdByUserId != null, ScheduledTask::getCreatedByUserId, createdByUserId)
                .orderByDesc(ScheduledTask::getId));
    }

    public ScheduledTask require(Long id) {
        ScheduledTask task = id == null ? null : taskMapper.selectById(id);
        if (task == null) {
            throw BizException.notFound("定时任务不存在: " + id);
        }
        return task;
    }

    public List<ScheduledTaskRun> runs(Long taskId, int limit) {
        return runMapper.selectList(Wrappers.<ScheduledTaskRun>lambdaQuery()
                .eq(ScheduledTaskRun::getTaskId, taskId)
                .orderByDesc(ScheduledTaskRun::getId)
                .last("limit " + Math.min(Math.max(limit, 1), 100)));
    }

    // ===================== 写入 =====================

    @Transactional
    public ScheduledTask create(ScheduledTaskRequest request, Long userId) {
        ScheduledTask task = new ScheduledTask();
        apply(task, request, userId);
        task.setCreatedByUserId(userId);
        task.setStatus("ACTIVE");
        task.setEnabled(true);
        task.setRunCount(0);
        task.setConsecutiveFailures(0);
        task.setCreatedAt(OffsetDateTime.now());
        task.setUpdatedAt(OffsetDateTime.now());
        task.setNextRunAt(computeNext(task, OffsetDateTime.now()));
        taskMapper.insert(task);
        log.info("已创建定时任务 id={} name={} 计划={} 下次={}", task.getId(), task.getName(),
                ScheduleMath.describe(task.getScheduleType(), task.getCronExpr(),
                        task.getIntervalMinutes(), task.getOnceAt(), task.getTimezone()),
                task.getNextRunAt());
        return task;
    }

    @Transactional
    public ScheduledTask update(Long id, ScheduledTaskRequest request) {
        ScheduledTask task = require(id);
        Long owner = task.getCreatedByUserId();
        apply(task, request, owner);
        task.setUpdatedAt(OffsetDateTime.now());
        task.setNextRunAt(computeNext(task, OffsetDateTime.now()));
        taskMapper.updateById(task);
        return task;
    }

    @Transactional
    public ScheduledTask setStatus(Long id, String status) {
        ScheduledTask task = require(id);
        task.setStatus(status);
        task.setEnabled("ACTIVE".equals(status));
        task.setUpdatedAt(OffsetDateTime.now());
        if ("ACTIVE".equals(status)) {
            task.setConsecutiveFailures(0);
            task.setNextRunAt(computeNext(task, OffsetDateTime.now()));
        }
        taskMapper.updateById(task);
        return task;
    }

    @Transactional
    public void delete(Long id) {
        require(id);
        taskMapper.deleteById(id);
    }

    /**
     * 「重新执行」某条历史执行记录：按 runId 找回任务，校验状态后交给调度器立刻再跑一次。
     *
     * <p>与 {@link #prepareManualRun} 的区别：这里允许 {@code DONE}（一次性任务跑完还能再跑一次），
     * 但同样不允许 {@code PAUSED}（用户明确暂停的任务不该被一次点击偷偷激活）。
     */
    @Transactional
    public ScheduledTask prepareRerun(Long runId) {
        Long taskId = runId == null ? null : runQueryMapper.taskIdOfRun(runId);
        if (taskId == null) {
            throw BizException.notFound("执行记录不存在: " + runId);
        }
        ScheduledTask task = require(taskId);
        if ("PAUSED".equals(task.getStatus())) {
            throw BizException.badRequest("任务「" + task.getName()
                    + "」当前是暂停状态，请先恢复再重新执行");
        }
        return task;
    }

    /** 手动立即执行：不改 next_run_at，只清出错状态，交给执行器跑一次。 */
    @Transactional
    public ScheduledTask prepareManualRun(Long id) {
        ScheduledTask task = require(id);
        if (!"ACTIVE".equals(task.getStatus()) && !"ERROR".equals(task.getStatus())) {
            throw BizException.badRequest("任务当前状态为 " + task.getStatus() + "，不能手动执行");
        }
        return task;
    }

    // ===================== 分布式抢占 =====================

    /**
     * 抢占到期的任务（多副本安全）。
     *
     * <p>在一个事务里：锁行（SKIP LOCKED）→ 算下次时间 → 打租约 → 返回。
     * 返回的任务已属于本节点，其它节点不会再拿到。
     */
    @Transactional
    public List<ScheduledTask> claimDue(int limit) {
        List<ScheduledTask> due = taskMapper.lockDueTasks(limit);
        if (due.isEmpty()) {
            return List.of();
        }
        OffsetDateTime now = OffsetDateTime.now();
        List<ScheduledTask> claimed = new java.util.ArrayList<>(due.size());
        for (ScheduledTask task : due) {
            OffsetDateTime scheduledAt = task.getNextRunAt();
            // 错过太久（超过 #MISFIRE_GRACE_MINUTES）且策略是 SKIP：**真的跳过**这次，
            // 只把 next_run_at 推到下一个周期。否则恢复瞬间会被积压的任务同时打爆。
            if ("SKIP".equalsIgnoreCase(task.getMisfirePolicy())
                    && ScheduleMath.isOverdue(scheduledAt, now, MISFIRE_GRACE_MINUTES)) {
                OffsetDateTime next = computeNext(task, now);
                task.setNextRunAt(next);
                task.setLeaseUntil(null);
                task.setUpdatedAt(now);
                task.setLastError("错过计划时间（" + scheduledAt + "），按策略 SKIP 跳过本次");
                taskMapper.updateById(task);
                log.info("定时任务错过触发已跳过 id={} 计划={} 下次={}", task.getId(), scheduledAt, next);
                continue;
            }
            OffsetDateTime next = computeNext(task, now.minusSeconds(1));
            task.setLastRunAt(now);
            task.setNextRunAt(next);
            task.setLeaseUntil(now.plusMinutes(leaseMinutes));
            task.setUpdatedAt(now);
            if (next == null) {
                // 一次性任务：等执行完再由 finishRun 决定 DONE / ERROR
                task.setEnabled(false);
            }
            taskMapper.updateById(task);
            claimed.add(task);
        }
        return claimed;
    }

    /** 回收租约过期与残留的 RUNNING 记录（节点崩溃留下的）。 */
    @Transactional
    public void recoverStale() {
        int released = taskMapper.releaseExpiredLeases();
        int abandoned = taskMapper.abandonStaleRuns(60);
        if (released > 0 || abandoned > 0) {
            log.info("定时任务回收：释放租约 {} 条，标记放弃执行 {} 条", released, abandoned);
        }
    }

    /** 线程池满时把任务放回去：清租约、恢复下一个计划时间，等下一轮再抢。 */
    @Transactional
    public void requeue(Long taskId) {
        ScheduledTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        task.setLeaseUntil(null);
        task.setNextRunAt(computeNext(task, OffsetDateTime.now().minusSeconds(1)));
        task.setUpdatedAt(OffsetDateTime.now());
        taskMapper.updateById(task);
    }

    public ScheduledTaskRun startRun(Long taskId, String nodeId, String triggerType) {
        ScheduledTaskRun run = new ScheduledTaskRun();
        run.setTaskId(taskId);
        run.setNodeId(nodeId);
        run.setTriggerType(triggerType);
        run.setStatus("RUNNING");
        run.setStartedAt(OffsetDateTime.now());
        run.setCreatedAt(OffsetDateTime.now());
        runMapper.insert(run);
        return run;
    }

    /**
     * 收尾记账：成功清零失败计数；失败累加，达到阈值自动暂停。
     *
     * @return true 表示本次失败触发了自动暂停
     */
    @Transactional
    public boolean finishRun(Long taskId, Long runId, Boolean success, String output, String error,
            long durationMs) {
        boolean cancelled = success == null;
        ScheduledTask task = taskMapper.selectById(taskId);
        ScheduledTaskRun run = runId == null ? null : runMapper.selectById(runId);
        OffsetDateTime now = OffsetDateTime.now();
        if (run != null) {
            run.setStatus(cancelled ? "CANCELLED" : (Boolean.TRUE.equals(success) ? "SUCCESS" : "FAILED"));
            run.setFinishedAt(now);
            run.setDurationMs(durationMs);
            run.setOutputPreview(truncate(output, 2000));
            run.setError(truncate(error, 2000));
            runMapper.updateById(run);
        }
        if (task == null) {
            return false;
        }
        task.setLeaseUntil(null);
        task.setLastRunAt(now);
        task.setRunCount((task.getRunCount() == null ? 0 : task.getRunCount()) + 1);
        task.setUpdatedAt(now);
        boolean paused = false;
        if (cancelled) {
            // 取消：清掉租约与错误即可；不改连续失败计数（取消不是故障），一次性任务也不再跑
            task.setLastError(null);
            if (ScheduleMath.TYPE_ONCE.equalsIgnoreCase(task.getScheduleType())) {
                task.setStatus("DONE");
                task.setEnabled(false);
                task.setNextRunAt(null);
            }
        } else if (success) {
            task.setConsecutiveFailures(0);
            task.setLastError(null);
            if (ScheduleMath.TYPE_ONCE.equalsIgnoreCase(task.getScheduleType())) {
                task.setStatus("DONE");
                task.setEnabled(false);
                task.setNextRunAt(null);
            }
        } else {
            int failures = (task.getConsecutiveFailures() == null ? 0 : task.getConsecutiveFailures()) + 1;
            task.setConsecutiveFailures(failures);
            task.setLastError(truncate(error, 2000));
            if (failures >= AUTO_PAUSE_AFTER_FAILURES) {
                task.setStatus("PAUSED");
                task.setEnabled(false);
                paused = true;
                log.warn("定时任务连续失败 {} 次，已自动暂停 id={} name={}",
                        failures, task.getId(), task.getName());
            }
        }
        taskMapper.updateById(task);
        return paused;
    }

    // ===================== 内部 =====================

    private void apply(ScheduledTask task, ScheduledTaskRequest request, Long owner) {
        if (request.name() == null || request.name().isBlank()) {
            throw BizException.badRequest("任务名称不能为空");
        }
        if (request.prompt() == null || request.prompt().isBlank()) {
            throw BizException.badRequest("prompt 不能为空（定时执行时要告诉 agent 做什么）");
        }
        Agent agent = agentMapper.selectById(request.agentId());
        if (agent == null) {
            throw BizException.badRequest("Agent 不存在: " + request.agentId());
        }
        String type = request.scheduleType() == null
                ? "" : request.scheduleType().trim().toUpperCase(java.util.Locale.ROOT);
        if (!List.of(ScheduleMath.TYPE_CRON, ScheduleMath.TYPE_EVERY, ScheduleMath.TYPE_ONCE)
                .contains(type)) {
            throw BizException.badRequest("scheduleType 只能是 CRON / EVERY_MINUTES / ONCE");
        }
        switch (type) {
            case ScheduleMath.TYPE_CRON -> {
                try {
                    ScheduleMath.validateCron(request.cronExpr());
                } catch (IllegalArgumentException ex) {
                    throw BizException.badRequest(ex.getMessage());
                }
            }
            case ScheduleMath.TYPE_EVERY -> {
                if (request.intervalMinutes() == null || request.intervalMinutes() <= 0) {
                    throw BizException.badRequest("EVERY_MINUTES 需要 intervalMinutes > 0");
                }
            }
            case ScheduleMath.TYPE_ONCE -> {
                if (request.onceAt() == null) {
                    throw BizException.badRequest("ONCE 需要 onceAt（执行时间）");
                }
                if (request.onceAt().isBefore(OffsetDateTime.now().minusSeconds(5))) {
                    throw BizException.badRequest(
                            "一次性任务的执行时间必须晚于当前时间（收到 " + request.onceAt() + "）");
                }
            }
            default -> {
                // 上面已校验
            }
        }
        String target = request.targetType() == null
                ? "SESSION" : request.targetType().trim().toUpperCase(java.util.Locale.ROOT);
        if (!List.of("SESSION", "IM", "NONE").contains(target)) {
            throw BizException.badRequest("targetType 只能是 SESSION / IM / NONE");
        }
        if ("IM".equals(target) && request.channelAppId() == null
                && request.chatSessionId() == null) {
            throw BizException.badRequest("推送到 IM 需要指定渠道应用，或从 IM 会话里创建任务");
        }
        if (request.chatSessionId() != null) {
            ChatSession session = sessionMapper.selectById(request.chatSessionId());
            if (session == null) {
                throw BizException.badRequest("会话不存在: " + request.chatSessionId());
            }
            if (owner != null && !owner.equals(session.getUserId())) {
                throw BizException.badRequest("不能使用别人的会话：" + request.chatSessionId());
            }
        }
        task.setName(request.name().trim());
        task.setAgentId(request.agentId());
        task.setScheduleType(type);
        task.setCronExpr(ScheduleMath.TYPE_CRON.equals(type)
                ? ScheduleMath.normalizeCron(request.cronExpr()) : null);
        task.setIntervalMinutes(ScheduleMath.TYPE_EVERY.equals(type) ? request.intervalMinutes() : null);
        task.setOnceAt(ScheduleMath.TYPE_ONCE.equals(type) ? request.onceAt() : null);
        task.setTimezone(request.timezone() == null || request.timezone().isBlank()
                ? "Asia/Shanghai" : request.timezone().trim());
        task.setPrompt(request.prompt().trim());
        task.setTargetType(target);
        task.setChatSessionId(request.chatSessionId());
        task.setChannelAppId(request.channelAppId());
        task.setPeerKey(request.peerKey());
        task.setMisfirePolicy(request.misfirePolicy() == null
                ? "COALESCE" : request.misfirePolicy().trim().toUpperCase(java.util.Locale.ROOT));
    }

    private OffsetDateTime computeNext(ScheduledTask task, OffsetDateTime base) {
        return ScheduleMath.next(task.getScheduleType(), task.getCronExpr(),
                task.getIntervalMinutes(), task.getOnceAt(), task.getTimezone(), base);
    }

    /** 任务运行用的会话：有就用，没有就建一个（保证记忆/上下文连续）。 */
    @Transactional
    public ChatSession ensureSession(ScheduledTask task) {
        if (task.getChatSessionId() != null) {
            ChatSession existing = sessionMapper.selectById(task.getChatSessionId());
            if (existing != null) {
                return existing;
            }
        }
        ChatSession created = sessionService.createForThread(
                task.getCreatedByUserId(), UUID.randomUUID().toString(), task.getAgentId());
        created.setTitle("定时 · " + task.getName());
        created.setOrigin("SCHEDULE");
        created.setOriginRef("定时任务 #" + task.getId());
        sessionMapper.updateById(created);
        task.setChatSessionId(created.getId());
        task.setUpdatedAt(OffsetDateTime.now());
        taskMapper.updateById(task);
        return created;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
