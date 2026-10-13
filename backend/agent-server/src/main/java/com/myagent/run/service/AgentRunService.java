package com.myagent.run.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.myagent.chat.entity.ChatSession;
import com.myagent.chat.mapper.ChatSessionMapper;
import com.myagent.run.entity.AgentRun;
import com.myagent.run.mapper.AgentRunMapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * run 状态落库（tier1-upgrades ③）。
 *
 * <p>内存里的实时态由 {@code RunningRunRegistry} 维护；本服务是它的**落库副本**，
 * 让「上一轮跑到哪」在后端重启后依然可见（spec：重启后状态可见，但不自动续跑）。
 *
 * <p>不做分布式队列、不做跨进程恢复——那是 spec 明确排除的范围（已登记 backlog）。
 */
@Service
public class AgentRunService {

    private static final Logger log = LoggerFactory.getLogger(AgentRunService.class);

    /** 文本摘要最长保留多少字符（够在列表上认出"上一轮是什么"即可）。 */
    private static final int SUMMARY_CHARS = 200;

    private final AgentRunMapper runMapper;
    private final ChatSessionMapper sessionMapper;

    public AgentRunService(AgentRunMapper runMapper, ChatSessionMapper sessionMapper) {
        this.runMapper = runMapper;
        this.sessionMapper = sessionMapper;
    }

    /** 一轮开始：记一行 RUNNING。 */
    public void start(String threadId, String runId) {
        if (threadId == null) {
            return;
        }
        try {
            AgentRun run = new AgentRun();
            run.setThreadId(threadId);
            run.setRunId(runId);
            run.setSessionId(sessionIdOf(threadId));
            run.setStatus(AgentRun.RUNNING);
            run.setStartedAt(OffsetDateTime.now());
            runMapper.insert(run);
        } catch (Exception ex) {
            // 落库失败不该影响这一轮对话本身
            log.warn("记录 run 开始失败 threadId={}: {}", threadId, ex.toString());
        }
    }

    /** 一轮收尾：把该 thread 最近一条 RUNNING 更新为终态。 */
    public void finish(String threadId, boolean stopped, String text, String error) {
        if (threadId == null) {
            return;
        }
        try {
            AgentRun latest = latestRunning(threadId);
            if (latest == null) {
                return;
            }
            latest.setStatus(
                    error != null && !error.isBlank()
                            ? AgentRun.ERROR
                            : stopped ? AgentRun.INTERRUPTED : AgentRun.FINISHED);
            latest.setEndedAt(OffsetDateTime.now());
            latest.setTextSummary(summarize(text));
            latest.setError(error);
            runMapper.updateById(latest);
        } catch (Exception ex) {
            log.warn("记录 run 结束失败 threadId={}: {}", threadId, ex.toString());
        }
    }

    /** 某会话最近几轮（默认 5），新的在前——后端重启后前端据此显示「上一轮状态」。 */
    public List<AgentRun> recentBySession(Long sessionId, int limit) {
        if (sessionId == null) {
            return List.of();
        }
        return runMapper.selectList(Wrappers.<AgentRun>lambdaQuery()
                .eq(AgentRun::getSessionId, sessionId)
                .orderByDesc(AgentRun::getStartedAt, AgentRun::getId)
                .last("limit " + Math.max(1, Math.min(50, limit))));
    }

    /**
     * 启动时归置残留：进程被重启时仍在 RUNNING 的行不可能再自己跑完，记为 INTERRUPTED。
     *
     * <p>这样用户重启后会看到「上一轮：已中断」，而不是永远停在「进行中」的假象。
     */
    public int reconcileStaleRuns() {
        int updated = runMapper.update(
                null,
                Wrappers.<AgentRun>lambdaUpdate()
                        .set(AgentRun::getStatus, AgentRun.INTERRUPTED)
                        .set(AgentRun::getEndedAt, OffsetDateTime.now())
                        .set(AgentRun::getError, "服务重启，这一轮未跑完")
                        .eq(AgentRun::getStatus, AgentRun.RUNNING));
        if (updated > 0) {
            log.info("启动归置：把 {} 条残留的 RUNNING 记为 INTERRUPTED", updated);
        }
        return updated;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            reconcileStaleRuns();
        } catch (Exception ex) {
            log.warn("启动归置 run 状态失败: {}", ex.toString());
        }
    }

    private AgentRun latestRunning(String threadId) {
        return runMapper.selectOne(Wrappers.<AgentRun>lambdaQuery()
                .eq(AgentRun::getThreadId, threadId)
                .eq(AgentRun::getStatus, AgentRun.RUNNING)
                .orderByDesc(AgentRun::getStartedAt, AgentRun::getId)
                .last("limit 1"));
    }

    private Long sessionIdOf(String threadId) {
        ChatSession session = sessionMapper.selectOne(Wrappers.<ChatSession>lambdaQuery()
                .eq(ChatSession::getThreadId, threadId)
                .last("limit 1"));
        return session == null ? null : session.getId();
    }

    static String summarize(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String flat = text.replace('\n', ' ').strip();
        return flat.length() <= SUMMARY_CHARS ? flat : flat.substring(0, SUMMARY_CHARS) + "…";
    }
}
