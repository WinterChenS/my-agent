package com.myagent.schedule.controller;

import com.myagent.auth.JwtPrincipal;
import com.myagent.common.BizException;
import com.myagent.common.Result;
import com.myagent.schedule.dto.ScheduledTaskRequest;
import com.myagent.schedule.dto.ScheduledTaskResponse;
import com.myagent.schedule.dto.ScheduledTaskRunResponse;
import com.myagent.schedule.entity.ScheduledTask;
import com.myagent.schedule.service.ScheduleMath;
import com.myagent.schedule.service.ScheduledTaskDispatcher;
import com.myagent.schedule.service.ScheduledTaskExecutionRegistry;
import com.myagent.schedule.service.ScheduledTaskService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 定时任务管理（管理页 + 排障）。 */
@RestController
@RequestMapping("/api/scheduled-tasks")
public class ScheduledTaskController {

    private final ScheduledTaskService service;
    private final ScheduledTaskDispatcher dispatcher;
    private final com.myagent.agentconfig.mapper.AgentMapper agentMapper;
    private final ScheduledTaskExecutionRegistry executionRegistry;
    private final com.myagent.schedule.mapper.ScheduledTaskRunQueryMapper runQueryMapper;

    public ScheduledTaskController(
            ScheduledTaskService service,
            ScheduledTaskDispatcher dispatcher,
            com.myagent.agentconfig.mapper.AgentMapper agentMapper,
            ScheduledTaskExecutionRegistry executionRegistry,
            com.myagent.schedule.mapper.ScheduledTaskRunQueryMapper runQueryMapper) {
        this.service = service;
        this.dispatcher = dispatcher;
        this.agentMapper = agentMapper;
        this.executionRegistry = executionRegistry;
        this.runQueryMapper = runQueryMapper;
    }

    // ===================== 执行记录（日历 + 时间轴） =====================

    /** 日历：按天聚合的执行次数（count 越多颜色越深）。 */
    @GetMapping("/runs/summary")
    public Result<List<Map<String, Object>>> runSummary(
            @AuthenticationPrincipal JwtPrincipal principal,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "false") boolean all) {
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Shanghai");
        java.time.OffsetDateTime start = parseTime(from)
                .orElse(java.time.OffsetDateTime.now(zone).minusDays(60).with(
                        java.time.LocalTime.MIN.atOffset(java.time.OffsetDateTime.now(zone).getOffset())));
        java.time.OffsetDateTime end = parseTime(to)
                .orElse(java.time.OffsetDateTime.now(zone).plusDays(2));
        return Result.ok(runQueryMapper.summaryByDay(start, end, zone.getId(), ownerScope(principal, all)));
    }

    /** 时间轴：时间窗内的执行记录（默认最近 24 小时）。 */
    @GetMapping("/runs")
    public Result<List<Map<String, Object>>> runTimeline(
            @AuthenticationPrincipal JwtPrincipal principal,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "200") int limit,
            @RequestParam(defaultValue = "false") boolean all) {
        java.time.OffsetDateTime start = parseTime(from)
                .orElse(java.time.OffsetDateTime.now().minusHours(24));
        java.time.OffsetDateTime end = parseTime(to).orElse(java.time.OffsetDateTime.now().plusDays(1));
        return Result.ok(runQueryMapper.timeline(
                start, end, ownerScope(principal, all), Math.min(Math.max(limit, 1), 500)));
    }

    /** 取消一次正在跑的执行（"点晚了"不算错误，返回 cancelled=false）。 */
    @PostMapping("/runs/{runId}/cancel")
    public Result<Map<String, Object>> cancelRun(@PathVariable Long runId) {
        boolean cancelled = executionRegistry.cancel(runId);
        String taskName = runQueryMapper.taskNameOfRun(runId);
        return Result.ok(Map.of(
                "runId", runId,
                "cancelled", cancelled,
                "taskName", taskName == null ? "" : taskName));
    }

    private Long ownerScope(JwtPrincipal principal, boolean all) {
        boolean admin = principal != null && "ADMIN".equalsIgnoreCase(principal.role());
        return admin && all ? null : (principal == null ? null : principal.userId());
    }

    private static java.util.Optional<java.time.OffsetDateTime> parseTime(String value) {
        if (value == null || value.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(java.time.OffsetDateTime.parse(value));
        } catch (Exception ex) {
            return java.util.Optional.empty();
        }
    }

    /** 计划预览请求（管理页可视化配置时实时调用）。 */
    public record PreviewRequest(
            String scheduleType,
            String cronExpr,
            Integer intervalMinutes,
            java.time.OffsetDateTime onceAt,
            String timezone,
            Integer count) {}

    /** 计划预览响应：人类可读摘要 + 接下来 N 次执行时间。 */
    public record PreviewResponse(String scheduleText, List<java.time.OffsetDateTime> nextRuns) {}

    @PostMapping("/preview")
    public Result<PreviewResponse> preview(@RequestBody PreviewRequest request) {
        int count = request.count() == null ? 5 : request.count();
        List<java.time.OffsetDateTime> runs;
        try {
            runs = ScheduleMath.nextRuns(request.scheduleType(), request.cronExpr(),
                    request.intervalMinutes(), request.onceAt(), request.timezone(),
                    java.time.OffsetDateTime.now(), count);
        } catch (IllegalArgumentException ex) {
            throw BizException.badRequest(ex.getMessage());
        }
        return Result.ok(new PreviewResponse(
                ScheduleMath.describe(request.scheduleType(), request.cronExpr(),
                        request.intervalMinutes(), request.onceAt(), request.timezone()),
                runs));
    }

    @GetMapping
    public Result<List<ScheduledTaskResponse>> list(
            @AuthenticationPrincipal JwtPrincipal principal,
            @RequestParam(defaultValue = "false") boolean all) {
        boolean admin = principal != null && "ADMIN".equalsIgnoreCase(principal.role());
        Long owner = admin && all ? null : (principal == null ? null : principal.userId());
        return Result.ok(service.list(owner).stream().map(this::toResponse).toList());
    }

    @GetMapping("/{id}")
    public Result<ScheduledTaskResponse> get(@PathVariable Long id) {
        return Result.ok(toResponse(service.require(id)));
    }

    @GetMapping("/{id}/runs")
    public Result<List<ScheduledTaskRunResponse>> runs(
            @PathVariable Long id, @RequestParam(defaultValue = "20") int limit) {
        return Result.ok(service.runs(id, limit).stream()
                .map(r -> new ScheduledTaskRunResponse(r.getId(), r.getTaskId(), r.getNodeId(),
                        r.getTriggerType(), r.getStatus(), r.getStartedAt(), r.getFinishedAt(),
                        r.getDurationMs(), r.getOutputPreview(), r.getError()))
                .toList());
    }

    @PostMapping
    public Result<ScheduledTaskResponse> create(
            @Valid @RequestBody ScheduledTaskRequest request,
            @AuthenticationPrincipal JwtPrincipal principal) {
        Long userId = principal == null ? null : principal.userId();
        if (userId == null) {
            throw BizException.badRequest("缺少当前用户");
        }
        return Result.ok(toResponse(service.create(request, userId)));
    }

    @PutMapping("/{id}")
    public Result<ScheduledTaskResponse> update(
            @PathVariable Long id, @Valid @RequestBody ScheduledTaskRequest request) {
        return Result.ok(toResponse(service.update(id, request)));
    }

    /** 暂停/恢复。 */
    @PostMapping("/{id}/status")
    public Result<ScheduledTaskResponse> setStatus(
            @PathVariable Long id, @RequestParam String value) {
        return Result.ok(toResponse(service.setStatus(id, value.toUpperCase())));
    }

    /** 立刻跑一次（不影响下次计划）。 */
    @PostMapping("/{id}/run")
    public Result<Void> runNow(@PathVariable Long id) {
        dispatcher.runNow(service.prepareManualRun(id));
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }

    /** 结果发到哪，写成人话（含渠道/接收人），避免"配了 IM 却不知道发给谁"。 */
    private String targetText(ScheduledTask task) {
        String type = task.getTargetType() == null ? "SESSION" : task.getTargetType();
        if ("NONE".equalsIgnoreCase(type)) {
            return "只记录";
        }
        if ("IM".equalsIgnoreCase(type)) {
            String peer = task.getPeerKey() == null ? "（用运行会话的绑定）" : task.getPeerKey();
            String channel = task.getChannelAppId() == null
                    ? "" : "渠道#" + task.getChannelAppId() + " ";
            return "IM · " + channel + peer;
        }
        return task.getChatSessionId() == null ? "会话（自动创建）" : "会话 #" + task.getChatSessionId();
    }

    private ScheduledTaskResponse toResponse(ScheduledTask task) {
        var agent = agentMapper.selectById(task.getAgentId());
        return new ScheduledTaskResponse(
                task.getId(),
                task.getName(),
                task.getEnabled(),
                task.getAgentId(),
                agent == null ? null : agent.getName(),
                task.getScheduleType(),
                task.getCronExpr(),
                task.getIntervalMinutes(),
                task.getOnceAt(),
                task.getTimezone(),
                ScheduleMath.describe(task.getScheduleType(), task.getCronExpr(),
                        task.getIntervalMinutes(), task.getOnceAt(), task.getTimezone()),
                task.getPrompt(),
                task.getTargetType(),
                targetText(task),
                task.getChatSessionId(),
                task.getChannelAppId(),
                task.getPeerKey(),
                task.getStatus(),
                task.getMisfirePolicy(),
                task.getNextRunAt(),
                task.getLastRunAt(),
                task.getLastError(),
                task.getRunCount(),
                task.getConsecutiveFailures(),
                task.getCreatedAt(),
                task.getUpdatedAt());
    }
}
