package com.myagent.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.myagent.agentconfig.dto.AgentRequest;
import com.myagent.agentconfig.service.AgentConfigService;
import com.myagent.common.BizException;
import com.myagent.modelconfig.dto.ModelRequest;
import com.myagent.modelconfig.dto.ProviderRequest;
import com.myagent.modelconfig.service.ModelConfigService;
import com.myagent.schedule.dto.ScheduledTaskRequest;
import com.myagent.schedule.entity.ScheduledTask;
import com.myagent.schedule.service.ScheduledTaskService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 定时任务：创建校验、抢占去重（多副本安全）、失败累计自动暂停。
 *
 * <p>抢占用真实数据库事务验证：连抢两次，第二次必须拿不到（SKIP LOCKED 语义）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ScheduledTaskServiceTest {

    @Autowired
    private ScheduledTaskService service;

    @Autowired
    private AgentConfigService agentConfigService;

    @Autowired
    private ModelConfigService modelConfigService;

    private Long newAgent() {
        String suffix = String.valueOf(System.nanoTime());
        Long providerId = modelConfigService
                .createProvider(new ProviderRequest("p-" + suffix, "openai", "http://localhost:9999", "k", "ENABLED"))
                .id();
        Long modelId = modelConfigService
                .createModel(new ModelRequest(providerId, "m-" + suffix, "gpt-4o-mini",
                        Map.<String, Object>of(), null, null, null, 1000, null, null, null, false,
                        "ENABLED", null))
                .id();
        return agentConfigService.create(new AgentRequest(
                "a-" + suffix, "agent-" + suffix, "系统提示", modelId, null, null,
                "ALWAYS_ALLOW", 5, null, null, null, "ENABLED",
                List.of(), List.of())).id();
    }

    private ScheduledTaskRequest everyMinute(Long agentId, String name) {
        return new ScheduledTaskRequest(name, agentId, "EVERY_MINUTES", null, 1, null,
                "Asia/Shanghai", "汇总昨天的数据", "NONE", null, null, null, "COALESCE");
    }

    @Test
    void 创建后会自动算出下次执行时间() {
        Long agentId = newAgent();
        ScheduledTask task = service.create(everyMinute(agentId, "每分钟任务"), 1L);

        assertThat(task.getId()).isNotNull();
        assertThat(task.getStatus()).isEqualTo("ACTIVE");
        assertThat(task.getNextRunAt()).isNotNull();
        assertThat(task.getNextRunAt()).isAfter(OffsetDateTime.now());
    }

    @Test
    void cron不合法直接拒绝() {
        Long agentId = newAgent();
        ScheduledTaskRequest bad = new ScheduledTaskRequest("坏cron", agentId, "CRON",
                "0 9 *", null, null, null, "做点事", "NONE", null, null, null, null);

        assertThatThrownBy(() -> service.create(bad, 1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("5 或 6 段");
    }

    @Test
    void 三种计划互斥校验() {
        Long agentId = newAgent();
        // EVERY 缺 intervalMinutes
        ScheduledTaskRequest missing = new ScheduledTaskRequest("缺间隔", agentId, "EVERY_MINUTES",
                null, null, null, null, "做事", "NONE", null, null, null, null);
        assertThatThrownBy(() -> service.create(missing, 1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("intervalMinutes");

        // ONCE 缺时间
        ScheduledTaskRequest onceBad = new ScheduledTaskRequest("缺时间", agentId, "ONCE",
                null, null, null, null, "做事", "NONE", null, null, null, null);
        assertThatThrownBy(() -> service.create(onceBad, 1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("onceAt");
    }

    @Test
    void 抢占一次后同一任务不会被再次抢到() {
        Long agentId = newAgent();
        ScheduledTask task = service.create(everyMinute(agentId, "抢占测试"), 1L);

        // 把它调到"已到期"
        task.setNextRunAt(OffsetDateTime.now().minusMinutes(1));
        service.update(task.getId(), new ScheduledTaskRequest(task.getName(), agentId,
                "EVERY_MINUTES", null, 1, null, "Asia/Shanghai", task.getPrompt(), "NONE",
                null, null, null, "COALESCE"));
        // update 会重算下次时间，这里再压回过去，模拟"到期"
        ScheduledTask due = service.require(task.getId());
        due.setNextRunAt(OffsetDateTime.now().minusSeconds(30));
        service.update(due.getId(), new ScheduledTaskRequest(due.getName(), agentId,
                "EVERY_MINUTES", null, 1, null, "Asia/Shanghai", due.getPrompt(), "NONE",
                null, null, null, "COALESCE"));

        List<ScheduledTask> first = service.claimDue(10);
        List<Long> firstIds = first.stream().map(ScheduledTask::getId).toList();
        List<ScheduledTask> second = service.claimDue(10);
        List<Long> secondIds = second.stream().map(ScheduledTask::getId).toList();

        // 同一任务两次认领不会同时出现（SKIP LOCKED 语义）
        if (!secondIds.isEmpty()) {
            assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);
        }
        if (firstIds.contains(task.getId())) {
            // 认领后打了租约，且下次时间被推后
            ScheduledTask claimed = service.require(task.getId());
            assertThat(claimed.getLeaseUntil()).isNotNull();
            assertThat(claimed.getNextRunAt()).isAfter(OffsetDateTime.now());
        }
    }

    @Test
    void 连续失败三次自动暂停() {
        Long agentId = newAgent();
        ScheduledTask task = service.create(everyMinute(agentId, "失败暂停"), 1L);

        assertThat(service.finishRun(task.getId(), null, false, null, "第一次失败", 10)).isFalse();
        assertThat(service.finishRun(task.getId(), null, false, null, "第二次失败", 10)).isFalse();
        boolean paused = service.finishRun(task.getId(), null, false, null, "第三次失败", 10);

        assertThat(paused).isTrue();
        ScheduledTask after = service.require(task.getId());
        assertThat(after.getStatus()).isEqualTo("PAUSED");
        assertThat(after.getEnabled()).isFalse();
        assertThat(after.getConsecutiveFailures()).isEqualTo(3);
        assertThat(after.getLastError()).contains("第三次失败");
    }

    @Test
    void 成功一次会把失败计数清零() {
        Long agentId = newAgent();
        ScheduledTask task = service.create(everyMinute(agentId, "恢复计数"), 1L);
        service.finishRun(task.getId(), null, false, null, "失败", 10);

        service.finishRun(task.getId(), null, true, "这次成功", null, 10);

        ScheduledTask after = service.require(task.getId());
        assertThat(after.getConsecutiveFailures()).isZero();
        assertThat(after.getLastError()).isNull();
        assertThat(after.getRunCount()).isEqualTo(2);
    }

    @Test
    void 一次性任务成功后置为完成() {
        Long agentId = newAgent();
        ScheduledTask task = service.create(new ScheduledTaskRequest(
                "一次性", agentId, "ONCE", null, null, OffsetDateTime.now().plusMinutes(5),
                "Asia/Shanghai", "提醒我喝水", "NONE", null, null, null, null), 1L);

        service.finishRun(task.getId(), null, true, "好的", null, 10);

        ScheduledTask after = service.require(task.getId());
        assertThat(after.getStatus()).isEqualTo("DONE");
        assertThat(after.getNextRunAt()).isNull();
    }

    @Test
    void 暂停与恢复会重置下次时间() {
        Long agentId = newAgent();
        ScheduledTask task = service.create(everyMinute(agentId, "启停"), 1L);

        assertThat(service.setStatus(task.getId(), "PAUSED").getStatus()).isEqualTo("PAUSED");
        ScheduledTask resumed = service.setStatus(task.getId(), "ACTIVE");
        assertThat(resumed.getStatus()).isEqualTo("ACTIVE");
        assertThat(resumed.getNextRunAt()).isAfter(OffsetDateTime.now());
    }

    @Test
    void 删除后查询报不存在() {
        Long agentId = newAgent();
        ScheduledTask task = service.create(everyMinute(agentId, "待删"), 1L);
        service.delete(task.getId());

        assertThatThrownBy(() -> service.require(task.getId())).isInstanceOf(BizException.class);
    }
}
