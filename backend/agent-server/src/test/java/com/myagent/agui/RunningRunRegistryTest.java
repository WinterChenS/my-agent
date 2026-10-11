package com.myagent.agui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Test;

/** Phase 12 / D25：运行登记表与「停止」入口（中断走官方 Agent.interrupt）。 */
class RunningRunRegistryTest {

    private static RuntimeContext context(String threadId) {
        return RuntimeContext.builder().sessionId(threadId).userId("1").build();
    }

    @Test
    void 没有运行中的run时中断返回false() {
        RunningRunRegistry registry = new RunningRunRegistry();

        assertThat(registry.interrupt("t-none")).isFalse();
        assertThat(registry.isRunning("t-none")).isFalse();
        assertThat(registry.consumeStopped("t-none")).isFalse();
    }

    @Test
    void 登记后中断会调用官方interrupt并置停止标记() {
        RunningRunRegistry registry = new RunningRunRegistry();
        Agent agent = mock(Agent.class);
        registry.registerAgent("t1", agent);
        registry.registerContext("t1", context("t1"));

        assertThat(registry.isRunning("t1")).isTrue();
        assertThat(registry.interrupt("t1")).isTrue();

        // 中断必须真的打到 Agent 上（官方入口），不能只是改个标志位
        verify(agent).interrupt();
        // 停止标记供落库「已停止」用
        assertThat(registry.consumeStopped("t1")).isTrue();
    }

    @Test
    void 只有上下文没有实例时不误报中断() {
        RunningRunRegistry registry = new RunningRunRegistry();
        registry.registerContext("t2", context("t2"));

        assertThat(registry.interrupt("t2")).isFalse();
        assertThat(registry.isRunning("t2")).isFalse();
    }

    @Test
    void 清理后不再可中断() {
        RunningRunRegistry registry = new RunningRunRegistry();
        Agent agent = mock(Agent.class);
        registry.registerAgent("t3", agent);
        registry.registerContext("t3", context("t3"));

        registry.clear("t3");

        assertThat(registry.interrupt("t3")).isFalse();
        verify(agent, never()).interrupt();
        assertThat(registry.consumeStopped("t3")).isFalse();
    }
}
