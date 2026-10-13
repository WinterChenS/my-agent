package com.myagent.chat.controller;

import com.myagent.agui.RunningRunRegistry;
import com.myagent.auth.JwtPrincipal;
import com.myagent.chat.entity.ChatSession;
import com.myagent.chat.service.ChatSessionService;
import com.myagent.common.BizException;
import com.myagent.common.Result;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行控制（Phase 12 / D25）：停止某个会话正在跑的 run。
 *
 * <p>停止走框架**官方中断入口**（{@code Agent.interrupt(RuntimeContext)}），不自研中断机制（门禁 G8）。
 * 没有运行中的 run 时返回 {@code interrupted=false}，而不是报错——用户的"点晚了"不该是错误。
 */
@RestController
public class ChatRunController {

    private final ChatSessionService chatSessionService;
    private final RunningRunRegistry runningRuns;
    private final com.myagent.run.service.AgentRunService agentRunService;

    public ChatRunController(
            ChatSessionService chatSessionService,
            RunningRunRegistry runningRuns,
            com.myagent.run.service.AgentRunService agentRunService) {
        this.chatSessionService = chatSessionService;
        this.runningRuns = runningRuns;
        this.agentRunService = agentRunService;
    }

    /** 返回体：是否确实发出了中断。 */
    public record InterruptResponse(boolean interrupted) {}

    /** 返回体：该会话当前是否在跑、已生成多少文本（供刷新后接续显示）。 */
    public record RunStateResponse(boolean running, String text, String runId, boolean stopped) {}

    /**
     * 查询该会话正在跑的这一轮（Phase 14）。
     *
     * <p>页面刷新/切页回来时前端会调它：若在跑就把已生成文本接上继续显示，
     * 并且这次访问会**取消宽限期**（等同于「客户端回来了」），让这一轮继续跑完。
     */
    @GetMapping("/api/sessions/{id}/run")
    public Result<RunStateResponse> runState(
            @AuthenticationPrincipal JwtPrincipal principal, @PathVariable Long id) {
        Long userId = requireUserId(principal);
        ChatSession session = chatSessionService.requireOwned(userId, id);
        RunningRunRegistry.RunSnapshot snapshot = runningRuns.snapshot(session.getThreadId());
        if (snapshot.running()) {
            runningRuns.markAttached(session.getThreadId());
        }
        return Result.ok(new RunStateResponse(
                snapshot.running(), snapshot.text(), snapshot.runId(), snapshot.stopped()));
    }

    /**
     * 该会话最近几轮的状态（tier1-upgrades ③，落库副本）。
     *
     * <p>后端重启后前端据此显示「上一轮：已完成 / 已中断」，不会只看到一片空白。
     * 只返回摘要字段，不回传整段文本。
     */
    @GetMapping("/api/sessions/{id}/runs")
    public Result<java.util.List<RunHistoryItem>> runHistory(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable Long id,
            @RequestParam(defaultValue = "5") int limit) {
        Long userId = requireUserId(principal);
        chatSessionService.requireOwned(userId, id);
        return Result.ok(agentRunService.recentBySession(id, limit).stream()
                .map(run -> new RunHistoryItem(
                        run.getId(),
                        run.getRunId(),
                        run.getStatus(),
                        run.getStartedAt() == null ? null : run.getStartedAt().toString(),
                        run.getEndedAt() == null ? null : run.getEndedAt().toString(),
                        run.getTextSummary(),
                        run.getError()))
                .toList());
    }

    /** 一轮 run 的历史条目（列表展示用）。 */
    public record RunHistoryItem(
            Long id,
            String runId,
            String status,
            String startedAt,
            String endedAt,
            String textSummary,
            String error) {}

    @PostMapping("/api/sessions/{id}/interrupt")
    public Result<InterruptResponse> interrupt(
            @AuthenticationPrincipal JwtPrincipal principal, @PathVariable Long id) {
        Long userId = requireUserId(principal);
        ChatSession session = chatSessionService.requireOwned(userId, id);
        boolean interrupted = runningRuns.interrupt(session.getThreadId());
        return Result.ok(new InterruptResponse(interrupted));
    }

    private Long requireUserId(JwtPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw BizException.unauthorized("未登录");
        }
        return principal.userId();
    }
}
