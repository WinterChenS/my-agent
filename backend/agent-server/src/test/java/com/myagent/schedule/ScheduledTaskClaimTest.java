package com.myagent.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.myagent.agentconfig.dto.AgentRequest;
import com.myagent.agentconfig.service.AgentConfigService;
import com.myagent.modelconfig.dto.ModelRequest;
import com.myagent.modelconfig.dto.ProviderRequest;
import com.myagent.modelconfig.service.ModelConfigService;
import com.myagent.schedule.dto.ScheduledTaskRequest;
import com.myagent.schedule.entity.ScheduledTask;
import com.myagent.schedule.service.ScheduleMath;
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
 * 抢占与推进：三种计划类型都要覆盖，防止「cron 能跑、一次性跑飞」这类漏测。
 *
 * <p>做法：把任务的 {@code next_run_at} 手工压到过去 → {@code claimDue} → 断言推进结果。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ScheduledTaskClaimTest {

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
                "a-" + suffix, "agent-" + suffix, "提示", modelId, null, null,
                "ALWAYS_ALLOW", 5, null, null, null, "ENABLED", List.of(), List.of())).id();
    }

    /** 建任务后把 next_run_at 压到指定过去时间（模拟"到期"）。 */
    private ScheduledTask forceDue(ScheduledTask task, OffsetDateTime dueAt) {
        task.setNextRunAt(dueAt);
        // 用 updateById 直接写，绕过 update() 的重算
        return task;
    }

    private ScheduledTask createCron(Long agentId, String name, String cron, String misfire) {
        return service.create(new ScheduledTaskRequest(name, agentId, "CRON", cron, null, null,
                "Asia/Shanghai", "做点事", "NONE", null, null, null, misfire), 1L);
    }

    private ScheduledTask createEvery(Long agentId, String name, int minutes) {
        return service.create(new ScheduledTaskRequest(name, agentId, "EVERY_MINUTES", null, minutes,
                null, "Asia/Shanghai", "做点事", "NONE", null, null, null, "COALESCE"), 1L);
    }

    private ScheduledTask createOnce(Long agentId, String name, OffsetDateTime at) {
        return service.create(new ScheduledTaskRequest(name, agentId, "ONCE", null, null, at,
                "Asia/Shanghai", "做点事", "NONE", null, null, null, "COALESCE"), 1L);
    }

    @Autowired
    private com.myagent.schedule.mapper.ScheduledTaskMapper taskMapper;

    private void setDue(ScheduledTask task, OffsetDateTime dueAt) {
        task.setNextRunAt(dueAt);
        // 一次性任务的 next_run_at 与 once_at 本来相等；压时间时一并改，才符合真实语义
        if (ScheduleMath.TYPE_ONCE.equalsIgnoreCase(task.getScheduleType())) {
            task.setOnceAt(dueAt);
        }
        taskMapper.updateById(task);
    }

    private boolean claimed(ScheduledTask task) {
        return service.claimDue(50).stream().anyMatch(t -> t.getId().equals(task.getId()));
    }

    // ===================== CRON =====================

    @Test
    void cron任务到期_被认领且下次时间往前推进() {
        Long agentId = newAgent();
        ScheduledTask task = createCron(agentId, "cron-每分钟", "* * * * *", "COALESCE");
        OffsetDateTime now = OffsetDateTime.now();
        setDue(task, now.minusSeconds(20));

        assertThat(claimed(task)).isTrue();

        ScheduledTask after = service.require(task.getId());
        // cron 精度是秒：nextRunAt 会被截断到整秒，所以"不早于当前时间（截断到秒）"才是稳定断言
        // （之前写 isAfter(now) 会在整秒边界随机红，属于测试的时间精度问题，不是逻辑缺陷）
        assertThat(after.getNextRunAt()).isAfterOrEqualTo(now.truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        assertThat(after.getLeaseUntil()).isAfter(now);
        assertThat(after.getLastRunAt()).isNotNull();
        // cron 任务可以被多次执行
        assertThat(after.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void cron任务未到期_不会被认领() {
        Long agentId = newAgent();
        ScheduledTask task = createCron(agentId, "cron-明天", "0 9 * * *", "COALESCE");
        setDue(task, OffsetDateTime.now().plusHours(5));

        assertThat(claimed(task)).isFalse();
        assertThat(service.require(task.getId()).getLeaseUntil()).isNull();
    }

    // ===================== EVERY_MINUTES =====================

    @Test
    void 间隔任务到期_按间隔推进() {
        Long agentId = newAgent();
        ScheduledTask task = createEvery(agentId, "every-15", 15);
        OffsetDateTime now = OffsetDateTime.now();
        setDue(task, now.minusSeconds(30));

        assertThat(claimed(task)).isTrue();

        ScheduledTask after = service.require(task.getId());
        // 下次 = 认领时刻 + 15 分钟（允许几秒误差）
        long minutes = java.time.Duration.between(now, after.getNextRunAt()).toMinutes();
        assertThat(minutes).isBetween(14L, 15L);
    }

    @Test
    void 间隔任务连续两轮都能被认领() {
        Long agentId = newAgent();
        ScheduledTask task = createEvery(agentId, "every-反复", 1);

        setDue(task, OffsetDateTime.now().minusSeconds(5));
        assertThat(claimed(task)).isTrue();
        // 清租约后再次到期 → 还能被认领
        ScheduledTask mid = service.require(task.getId());
        mid.setLeaseUntil(null);
        setDue(mid, OffsetDateTime.now().minusSeconds(5));

        assertThat(claimed(task)).isTrue();
    }

    // ===================== ONCE =====================

    @Test
    void 一次性任务到期_认领后不再有下次且停用() {
        Long agentId = newAgent();
        ScheduledTask task = createOnce(agentId, "once-马上", OffsetDateTime.now().plusMinutes(2));
        setDue(task, OffsetDateTime.now().minusSeconds(5));

        assertThat(claimed(task)).isTrue();

        ScheduledTask after = service.require(task.getId());
        assertThat(after.getNextRunAt()).isNull();
        assertThat(after.getEnabled()).isFalse();
        // 认领阶段还不算完成，执行成功后才 DONE
        assertThat(after.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void 一次性任务认领后再跑一轮不会被重复认领() {
        Long agentId = newAgent();
        ScheduledTask task = createOnce(agentId, "once-不重复", OffsetDateTime.now().plusMinutes(2));
        setDue(task, OffsetDateTime.now().minusSeconds(5));
        assertThat(claimed(task)).isTrue();

        // 第二轮 tick：next_run_at 已清空，不会再被认领
        assertThat(claimed(task)).isFalse();
    }

    @Test
    void 一次性任务成功后被标记完成() {
        Long agentId = newAgent();
        ScheduledTask task = createOnce(agentId, "once-完成", OffsetDateTime.now().plusMinutes(2));
        setDue(task, OffsetDateTime.now().minusSeconds(5));
        assertThat(claimed(task)).isTrue();

        service.finishRun(task.getId(), null, true, "已经提醒了", null, 100);

        ScheduledTask after = service.require(task.getId());
        assertThat(after.getStatus()).isEqualTo("DONE");
        assertThat(after.getEnabled()).isFalse();
    }

    // ===================== 错过策略 =====================

    @Test
    void 错过很久_策略SKIP会真的跳过本次() {
        Long agentId = newAgent();
        ScheduledTask task = createCron(agentId, "skip-错过", "*/5 * * * *", "SKIP");
        OffsetDateTime now = OffsetDateTime.now();
        setDue(task, now.minusHours(2));

        boolean wasClaimed = claimed(task);

        assertThat(wasClaimed).as("SKIP 策略下不应执行本次").isFalse();
        ScheduledTask after = service.require(task.getId());
        assertThat(after.getNextRunAt()).isAfter(now);
        assertThat(after.getLastError()).contains("SKIP");
        assertThat(after.getLeaseUntil()).isNull();
        assertThat(after.getRunCount()).isZero();
    }

    @Test
    void 错过很久_策略COALESCE会补跑一次() {
        Long agentId = newAgent();
        ScheduledTask task = createCron(agentId, "coalesce-错过", "*/5 * * * *", "COALESCE");
        OffsetDateTime now = OffsetDateTime.now();
        setDue(task, now.minusHours(2));

        assertThat(claimed(task)).as("COALESCE 策略下应补跑").isTrue();
        ScheduledTask after = service.require(task.getId());
        assertThat(after.getLeaseUntil()).isNotNull();
        assertThat(after.getNextRunAt()).isAfter(now);
    }

    @Test
    void 落后在宽限内_SKIP也会正常执行() {
        Long agentId = newAgent();
        ScheduledTask task = createCron(agentId, "skip-宽限内", "*/5 * * * *", "SKIP");
        setDue(task, OffsetDateTime.now().minusSeconds(30));

        // 只落后 30 秒（< 5 分钟宽限）→ 不算错过，照常执行
        assertThat(claimed(task)).isTrue();
    }

    // ===================== 租约与并发 =====================

    @Test
    void 有租约的任务不会被别人抢走() {
        Long agentId = newAgent();
        ScheduledTask task = createEvery(agentId, "租约中", 5);
        setDue(task, OffsetDateTime.now().minusSeconds(5));
        assertThat(claimed(task)).isTrue();

        // 模拟"另一节点"的 tick：租约还没过期 → 抢不到
        assertThat(claimed(task)).isFalse();
    }

    @Test
    void 租约过期后可被重新认领() {
        Long agentId = newAgent();
        ScheduledTask task = createEvery(agentId, "租约过期", 5);
        setDue(task, OffsetDateTime.now().minusSeconds(5));
        assertThat(claimed(task)).isTrue();

        // 让租约过期（节点崩溃场景），同时把 next_run_at 压回过去
        ScheduledTask mid = service.require(task.getId());
        mid.setLeaseUntil(OffsetDateTime.now().minusMinutes(1));
        mid.setNextRunAt(OffsetDateTime.now().minusSeconds(5));
        taskMapper.updateById(mid);

        assertThat(claimed(task)).isTrue();
    }

    @Test
    void 恢复时按策略跳过_不返回给执行器() {
        Long agentId = newAgent();
        ScheduledTask task = createCron(agentId, "跳过列表", "*/5 * * * *", "SKIP");
        setDue(task, OffsetDateTime.now().minusHours(3));

        List<ScheduledTask> claimedList = service.claimDue(50);

        assertThat(claimedList.stream().map(ScheduledTask::getId)).doesNotContain(task.getId());
    }
}
