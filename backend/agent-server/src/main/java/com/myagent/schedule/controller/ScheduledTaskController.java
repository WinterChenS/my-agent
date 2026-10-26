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
import com.myagent.schedule.service.ScheduledTaskService;
import jakarta.validation.Valid;
import java.util.List;
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

    public ScheduledTaskController(
            ScheduledTaskService service,
            ScheduledTaskDispatcher dispatcher,
            com.myagent.agentconfig.mapper.AgentMapper agentMapper) {
        this.service = service;
        this.dispatcher = dispatcher;
        this.agentMapper = agentMapper;
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
