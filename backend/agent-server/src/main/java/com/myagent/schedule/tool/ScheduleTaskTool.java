package com.myagent.schedule.tool;

import com.myagent.channelconfig.entity.ChannelPeer;
import com.myagent.chat.entity.ChatSession;
import com.myagent.chat.mapper.ChatSessionMapper;
import com.myagent.schedule.dto.ScheduledTaskRequest;
import com.myagent.schedule.entity.ScheduledTask;
import com.myagent.schedule.service.ScheduleMath;
import com.myagent.schedule.service.ScheduledTaskService;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 聊天里创建定时任务：{@code schedule_task}。
 *
 * <p>典型用法：「每天 9 点把昨天的销售汇总发我钉钉」「30 分钟后提醒我喝水」。
 *
 * <p>默认目标按创建位置决定（用户要求）：IM 会话里创建 → 推回这个 IM；Web 会话里创建 → 写回这个会话。
 * 显式 {@code target} 可覆盖。
 *
 * <p>写操作：Agent 处于 {@code REQUIRE_APPROVAL} 时平台会自动弹审批卡。
 */
@Component
public class ScheduleTaskTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(ScheduleTaskTool.class);

    private final ScheduledTaskService taskService;
    private final ChatSessionMapper sessionMapper;
    private final com.myagent.agui.channel.ChannelSessionBridge sessionBridge;

    public ScheduleTaskTool(
            ScheduledTaskService taskService,
            ChatSessionMapper sessionMapper,
            com.myagent.agui.channel.ChannelSessionBridge sessionBridge) {
        this.taskService = taskService;
        this.sessionMapper = sessionMapper;
        this.sessionBridge = sessionBridge;
    }

    @Override
    public String getName() {
        return "schedule_task";
    }

    @Override
    public String getDescription() {
        return "创建一个定时任务：到点让 agent 自动执行你写的 prompt，并把结果发到你指定的地方。"
                + "用户说「每天/每周/每隔多久…帮我做…」「X 分钟后提醒我」时用它。"
                + "三种计划：cron（5 或 6 段，如每天 9 点 = \"0 9 * * *\"）"
                + "、everyMinutes（每 N 分钟）、onceInMinutes（N 分钟后一次性）。"
                + "target 默认按当前位置决定：在 IM 会话里创建就发回 IM，在网页里创建就写回本会话；"
                + "可选 IM / SESSION / NONE 显式指定。"
                + "prompt 要写成**独立可执行**的指令（到点没人补充上下文），例如"
                + "「把昨天 uploads 里的销售表汇总成 3 条要点」。"
                + "任务执行时如果你生成了文件（报告文档、图表等），平台会自动把它们随结果一起发出去。"
                + "创建成功后告诉用户任务 id、计划与下次执行时间。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", Map.of("type", "string", "description", "任务名称，简短可读"));
        properties.put("prompt", Map.of("type", "string",
                "description", "到点执行的指令（要独立可执行，不依赖当前对话）"));
        properties.put("cron", Map.of("type", "string",
                "description", "cron 表达式（5 段标准或 6 段带秒），如 0 9 * * * 表示每天 9 点"));
        properties.put("everyMinutes", Map.of("type", "integer",
                "description", "每隔多少分钟执行一次（与 cron 二选一）"));
        properties.put("onceInMinutes", Map.of("type", "integer",
                "description", "多少分钟后执行一次（一次性任务，与 cron/everyMinutes 三选一）"));
        properties.put("timezone", Map.of("type", "string",
                "description", "cron 的时区，默认 Asia/Shanghai"));
        properties.put("target", Map.of("type", "string",
                "enum", List.of("IM", "SESSION", "NONE"),
                "description", "结果发到哪里：IM=推送到钉钉等，SESSION=写回当前会话，NONE=只记录"));
        properties.put("misfirePolicy", Map.of("type", "string",
                "enum", List.of("COALESCE", "SKIP"),
                "description", "错过触发时的处理：COALESCE=恢复后补跑一次（默认），SKIP=跳过"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("name", "prompt"));
        return schema;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> create(param))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(ex -> Mono.just(text("创建定时任务失败：" + ex.getMessage())));
    }

    private ToolResultBlock create(ToolCallParam param) {
        Map<String, Object> input = param.getInput();
        String threadId = param.getRuntimeContext() == null
                ? null : param.getRuntimeContext().getSessionId();
        String userId = param.getRuntimeContext() == null
                ? null : param.getRuntimeContext().getUserId();
        if (threadId == null || userId == null) {
            return text("当前没有会话上下文，无法创建定时任务");
        }
        ChatSession session = sessionMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<ChatSession>lambdaQuery()
                        .eq(ChatSession::getThreadId, threadId)
                        .last("limit 1"));
        if (session == null) {
            return text("找不到当前会话，无法创建定时任务");
        }

        String cron = asString(input.get("cron"));
        Integer everyMinutes = asInt(input.get("everyMinutes"));
        Integer onceInMinutes = asInt(input.get("onceInMinutes"));
        String scheduleType;
        OffsetDateTime onceAt = null;
        if (cron != null && !cron.isBlank()) {
            scheduleType = ScheduleMath.TYPE_CRON;
        } else if (everyMinutes != null) {
            scheduleType = ScheduleMath.TYPE_EVERY;
        } else if (onceInMinutes != null) {
            scheduleType = ScheduleMath.TYPE_ONCE;
            onceAt = OffsetDateTime.now().plusMinutes(Math.max(1, onceInMinutes));
        } else {
            return text("需要指定计划：cron、everyMinutes 或 onceInMinutes 三者之一");
        }

        // 默认目标：IM 会话 → 推回该 IM；Web 会话 → 写回本会话
        String target = asString(input.get("target"));
        if (target == null || target.isBlank()) {
            ChannelPeer peer = sessionBridge.peerOfSession(session.getId());
            target = peer == null ? "SESSION" : "IM";
        }

        ScheduledTaskRequest request = new ScheduledTaskRequest(
                asString(input.get("name")),
                session.getAgentId(),
                scheduleType,
                cron,
                everyMinutes,
                onceAt,
                asString(input.get("timezone")),
                asString(input.get("prompt")),
                target,
                session.getId(),
                null,
                null,
                asString(input.get("misfirePolicy")));
        ScheduledTask task = taskService.create(request, Long.valueOf(userId));
        log.info("会话里创建了定时任务 id={} 会话={} 目标={} 计划={}",
                task.getId(), session.getId(), target, scheduleType);

        StringBuilder out = new StringBuilder("已创建定时任务：\n");
        out.append("- id: ").append(task.getId()).append('\n');
        out.append("- 名称: ").append(task.getName()).append('\n');
        out.append("- 计划: ").append(ScheduleMath.describe(task.getScheduleType(),
                task.getCronExpr(), task.getIntervalMinutes(), task.getOnceAt(), task.getTimezone())).append('\n');
        out.append("- 下次执行: ").append(task.getNextRunAt() == null ? "-" : task.getNextRunAt()).append('\n');
        out.append("- 结果发送到: ").append("IM".equalsIgnoreCase(task.getTargetType())
                ? "当前 IM 会话" : "当前网页会话").append('\n');
        for (ScheduledTask existing : taskService.list(Long.valueOf(userId))) {
            if (!existing.getId().equals(task.getId())
                    && existing.getName().equals(task.getName())
                    && "ACTIVE".equals(existing.getStatus())) {
                out.append("\n注意：已有同名同任务 id=").append(existing.getId())
                        .append("，如果不想要可以让我删掉其中一个。\n");
                break;
            }
        }
        return text(out.toString());
    }

    private static ToolResultBlock text(String content) {
        return ToolResultBlock.builder().output(TextBlock.builder().text(content).build()).build();
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
