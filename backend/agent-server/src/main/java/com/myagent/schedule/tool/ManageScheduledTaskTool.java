package com.myagent.schedule.tool;

import com.myagent.schedule.dto.ScheduledTaskRunResponse;
import com.myagent.schedule.entity.ScheduledTask;
import com.myagent.schedule.service.ScheduleMath;
import com.myagent.schedule.service.ScheduledTaskDispatcher;
import com.myagent.schedule.service.ScheduledTaskService;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 查看与管理定时任务：{@code manage_scheduled_task}。
 *
 * <p>一个工具覆盖五种动作（避免工具列表膨胀，模型也更好学）：
 * {@code list}（列出）、{@code pause}/{@code resume}（启停）、{@code cancel}（删除）、
 * {@code run_now}（立刻跑一次）、{@code history}（最近执行记录）。
 */
@Component
public class ManageScheduledTaskTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(ManageScheduledTaskTool.class);

    private final ScheduledTaskService taskService;

    /**
     * 用 {@link ObjectProvider} 延迟解析：Dispatcher → Executor → AgentFactory → McpToolkitFactory → 本工具，
     * 构造期直接注入会形成循环依赖。
     */
    private final org.springframework.beans.factory.ObjectProvider<ScheduledTaskDispatcher> dispatcher;

    public ManageScheduledTaskTool(
            ScheduledTaskService taskService,
            org.springframework.beans.factory.ObjectProvider<ScheduledTaskDispatcher> dispatcher) {
        this.taskService = taskService;
        this.dispatcher = dispatcher;
    }

    @Override
    public String getName() {
        return "manage_scheduled_task";
    }

    @Override
    public String getDescription() {
        return "查看和管理定时任务。"
                + "action=list 列出全部任务（含计划与下次执行时间）；"
                + "pause/resume 暂停或恢复；cancel 删除任务；run_now 立刻执行一次；"
                + "history 查看最近执行记录。"
                + "用户问「我有哪些定时任务」「把那个任务停了」「现在就跑一次看看」时用它。"
                + "除 list/history 外都需要 taskId（可从 list 的结果里拿）。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", Map.of("type", "string",
                "enum", List.of("list", "pause", "resume", "cancel", "run_now", "history"),
                "description", "要执行的动作"));
        properties.put("taskId", Map.of("type", "integer",
                "description", "任务 id（action 不是 list 时必填）"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("action"));
        return schema;
    }

    @Override
    public boolean isReadOnly() {
        // list/history 是只读，但同一工具含写操作 → 标记为写，让审批模式能拦住
        return false;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> run(param))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(ex -> Mono.just(text("操作失败：" + ex.getMessage())));
    }

    private ToolResultBlock run(ToolCallParam param) {
        String action = asString(param.getInput().get("action"));
        Long taskId = asLong(param.getInput().get("taskId"));
        Long userId = param.getRuntimeContext() == null
                ? null : asLong(param.getRuntimeContext().getUserId());
        if (action == null) {
            return text("action 不能为空");
        }
        switch (action.toLowerCase(java.util.Locale.ROOT)) {
            case "list" -> {
                return text(listText(userId));
            }
            case "pause" -> {
                ScheduledTask task = serviceTask(taskId, userId);
                taskService.setStatus(task.getId(), "PAUSED");
                return text("已暂停任务 #" + task.getId() + "「" + task.getName() + "」");
            }
            case "resume" -> {
                ScheduledTask task = serviceTask(taskId, userId);
                taskService.setStatus(task.getId(), "ACTIVE");
                return text("已恢复任务 #" + task.getId() + "「" + task.getName() + "」");
            }
            case "cancel" -> {
                ScheduledTask task = serviceTask(taskId, userId);
                taskService.delete(task.getId());
                log.info("删除定时任务 id={} by user={}", task.getId(), userId);
                return text("已删除任务 #" + task.getId() + "「" + task.getName() + "」");
            }
            case "run_now" -> {
                ScheduledTask task = taskService.prepareManualRun(serviceTask(taskId, userId).getId());
                ScheduledTaskDispatcher runner = dispatcher.getIfAvailable();
                if (runner == null) {
                    return text("调度器未启用（myagent.schedule.enabled=false），无法立即执行");
                }
                runner.runNow(task);
                return text("已触发任务 #" + task.getId() + " 立即执行，结果稍后发到"
                        + ("IM".equalsIgnoreCase(task.getTargetType()) ? "IM" : "会话") + "。");
            }
            case "history" -> {
                ScheduledTask task = serviceTask(taskId, userId);
                return text(historyText(task.getId()));
            }
            default -> {
                return text("不支持的 action: " + action);
            }
        }
    }

    private ScheduledTask serviceTask(Long taskId, Long userId) {
        if (taskId == null) {
            throw new IllegalArgumentException("这个动作需要 taskId（可以先 action=list 看看有哪些）");
        }
        ScheduledTask task = taskService.require(taskId);
        if (userId != null && task.getCreatedByUserId() != null
                && !userId.equals(task.getCreatedByUserId())) {
            throw new IllegalArgumentException("这不是你的任务（id=" + taskId + "）");
        }
        return task;
    }

    private String listText(Long userId) {
        List<ScheduledTask> tasks = taskService.list(userId);
        if (tasks.isEmpty()) {
            return "还没有定时任务。可以让我创建一个，例如「每天 9 点把昨天的数据汇总发我」。";
        }
        StringBuilder out = new StringBuilder("当前有 " + tasks.size() + " 个定时任务：\n");
        for (ScheduledTask task : tasks) {
            out.append("\n#").append(task.getId()).append(' ').append(task.getName())
                    .append("  [").append(task.getStatus()).append("]\n")
                    .append("  计划: ").append(ScheduleMath.describe(task.getScheduleType(),
                            task.getCronExpr(), task.getIntervalMinutes(), task.getOnceAt(),
                            task.getTimezone())).append('\n')
                    .append("  下次: ").append(task.getNextRunAt() == null ? "-" : task.getNextRunAt())
                    .append("  已跑: ").append(task.getRunCount()).append(" 次\n")
                    .append("  发到: ").append("IM".equalsIgnoreCase(task.getTargetType()) ? "IM" : "会话")
                    .append('\n')
                    .append("  指令: ").append(abbreviate(task.getPrompt(), 80)).append('\n');
            if (task.getLastError() != null) {
                out.append("  最近错误: ").append(abbreviate(task.getLastError(), 100)).append('\n');
            }
        }
        return out.toString();
    }

    private String historyText(Long taskId) {
        List<ScheduledTaskRunResponse> runs = taskService.runs(taskId, 10).stream()
                .map(r -> new ScheduledTaskRunResponse(r.getId(), r.getTaskId(), r.getNodeId(),
                        r.getTriggerType(), r.getStatus(), r.getStartedAt(), r.getFinishedAt(),
                        r.getDurationMs(), r.getOutputPreview(), r.getError()))
                .toList();
        if (runs.isEmpty()) {
            return "任务 #" + taskId + " 还没有执行记录。";
        }
        StringBuilder out = new StringBuilder("任务 #" + taskId + " 最近 " + runs.size() + " 次执行：\n");
        for (ScheduledTaskRunResponse run : runs) {
            out.append("- ").append(run.startedAt()).append("  ").append(run.status())
                    .append("  耗时=").append(run.durationMs() == null ? "-" : run.durationMs() + "ms")
                    .append("  触发=").append(run.triggerType()).append('\n');
            if (run.error() != null && !run.error().isBlank()) {
                out.append("    错误: ").append(abbreviate(run.error(), 120)).append('\n');
            } else if (run.outputPreview() != null) {
                out.append("    输出: ").append(abbreviate(run.outputPreview(), 120)).append('\n');
            }
        }
        return out.toString();
    }

    private static ToolResultBlock text(String content) {
        return ToolResultBlock.builder().output(TextBlock.builder().text(content).build()).build();
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replace('\n', ' ');
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }
}
