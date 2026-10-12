package com.myagent.agui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Phase 12（停止执行）+ Phase 14（刷新接续）：运行登记表。
 *
 * <p>Phase 14 的关键行为：
 * <ul>
 *   <li>断开（刷新/切页）**不立刻中断**，进入宽限期；期间有人回来就继续跑，一直没人回来才中断；</li>
 *   <li>已生成的文本随时可查（供页面回来后接续显示）。</li>
 * </ul>
 */
class RunningRunRegistryTest {

    private static RuntimeContext context(String threadId) {
        return RuntimeContext.builder().sessionId(threadId).userId("1").build();
    }

    /** 设置「断开后最多再跑多久」上限的实例（0 = 不限制，即默认）。 */
    private static RunningRunRegistry withMaxDetached(long millis) {
        return new RunningRunRegistry(millis);
    }

    @Test
    void 没有运行中的run时中断返回false() {
        RunningRunRegistry registry = new RunningRunRegistry(60_000);

        assertThat(registry.interrupt("t-none")).isFalse();
        assertThat(registry.isRunning("t-none")).isFalse();
        assertThat(registry.consumeStopped("t-none")).isFalse();
        assertThat(registry.snapshot("t-none").running()).isFalse();
    }

    @Test
    void 登记后中断会调用官方interrupt并置停止标记() {
        RunningRunRegistry registry = new RunningRunRegistry(60_000);
        Agent agent = mock(Agent.class);
        registry.registerAgent("t1", agent);
        registry.registerContext("t1", context("t1"));

        assertThat(registry.isRunning("t1")).isTrue();
        assertThat(registry.interrupt("t1")).isTrue();

        verify(agent).interrupt();
        assertThat(registry.consumeStopped("t1")).isTrue();
    }

    @Test
    void 只有上下文没有实例时不误报中断() {
        RunningRunRegistry registry = new RunningRunRegistry(60_000);
        registry.registerContext("t2", context("t2"));

        assertThat(registry.interrupt("t2")).isFalse();
        assertThat(registry.isRunning("t2")).isFalse();
    }

    @Test
    void 清理后不再可中断() {
        RunningRunRegistry registry = new RunningRunRegistry(60_000);
        Agent agent = mock(Agent.class);
        registry.registerAgent("t3", agent);
        registry.registerContext("t3", context("t3"));

        registry.clear("t3");

        assertThat(registry.interrupt("t3")).isFalse();
        verify(agent, never()).interrupt();
        assertThat(registry.consumeStopped("t3")).isFalse();
    }

    @Test
    void 已生成的文本可随时查到_刷新接续用() {
        RunningRunRegistry registry = new RunningRunRegistry(60_000);
        registry.beginRun("t4", "run-4");
        registry.registerAgent("t4", mock(Agent.class));

        registry.syncText("t4", "stub-ok ");
        assertThat(registry.snapshot("t4").text()).isEqualTo("stub-ok ");

        registry.syncText("t4", "stub-ok abc");
        RunningRunRegistry.RunSnapshot snapshot = registry.snapshot("t4");
        assertThat(snapshot.running()).isTrue();
        assertThat(snapshot.text()).isEqualTo("stub-ok abc");
        assertThat(snapshot.runId()).isEqualTo("run-4");
    }

    @Test
    void 收尾后快照变为未在运行() {
        RunningRunRegistry registry = new RunningRunRegistry(60_000);
        registry.beginRun("t5", "run-5");
        registry.registerAgent("t5", mock(Agent.class));

        registry.markFinished("t5");

        assertThat(registry.snapshot("t5").running()).isFalse();
        assertThat(registry.isRunning("t5")).isFalse();
    }

    @Test
    void 断开后不会自动中断_后端继续跑完() throws Exception {
        // 默认（maxDetachedMillis=0）：无论前端在不在，后端都继续跑，只有显式停止才会中断
        RunningRunRegistry registry = new RunningRunRegistry(0);
        Agent agent = mock(Agent.class);
        registry.beginRun("t6", "run-6");
        registry.registerAgent("t6", agent);
        registry.registerContext("t6", context("t6"));
        registry.syncText("t6", "已生成一半");

        registry.markDetached("t6");
        TimeUnit.MILLISECONDS.sleep(400);

        verify(agent, never()).interrupt();
        assertThat(registry.isRunning("t6")).isTrue();
        // 期间生成的文本随时可查（前端进来就接上）
        assertThat(registry.snapshot("t6").text()).isEqualTo("已生成一半");
    }

    @Test
    void 可选上限_超过上限仍无人接续才中断并保留已生成文本() throws Exception {
        RunningRunRegistry registry = withMaxDetached(150);
        Agent agent = mock(Agent.class);
        registry.beginRun("t6", "run-6");
        registry.registerAgent("t6", agent);
        registry.registerContext("t6", context("t6"));
        registry.syncText("t6", "已生成一半");

        // 模拟刷新：客户端断开
        registry.markDetached("t6");
        // 宽限期内不该被中断
        assertThat(registry.consumeStopped("t6")).isFalse();

        // 等宽限期过去
        TimeUnit.MILLISECONDS.sleep(500);
        verify(agent).interrupt();
        assertThat(registry.consumeStopped("t6")).isTrue();
        // 答案不丢：已生成文本仍在，可供落库/展示
        assertThat(registry.snapshot("t6").text()).isEqualTo("已生成一半");
    }

    @Test
    void 宽限期内回来轮询接续则不会被中断() throws Exception {
        RunningRunRegistry registry = withMaxDetached(400);
        Agent agent = mock(Agent.class);
        registry.beginRun("t7", "run-7");
        registry.registerAgent("t7", agent);
        registry.registerContext("t7", context("t7"));

        registry.markDetached("t7");
        TimeUnit.MILLISECONDS.sleep(80);
        // 页面回来：查一次（markAttached）→ 取消宽限期
        registry.markAttached("t7");

        TimeUnit.MILLISECONDS.sleep(600);
        verify(agent, never()).interrupt();
        assertThat(registry.consumeStopped("t7")).isFalse();
        assertThat(registry.isRunning("t7")).isTrue();
    }
}
