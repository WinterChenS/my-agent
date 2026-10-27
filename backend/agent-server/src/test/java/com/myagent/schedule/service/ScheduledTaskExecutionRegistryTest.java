package com.myagent.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

/** 执行登记表：取消 = 断流 + 官方中断，并放行等待线程。 */
class ScheduledTaskExecutionRegistryTest {

    private static RuntimeContext context() {
        return RuntimeContext.builder().sessionId("t1").userId("1").build();
    }

    @Test
    void 取消会dispose订阅并中断Agent() {
        ScheduledTaskExecutionRegistry registry = new ScheduledTaskExecutionRegistry(Runnable::run);
        HarnessAgent agent = mock(HarnessAgent.class);
        RuntimeContext context = context();
        ScheduledTaskExecutionRegistry.Handle handle =
                registry.register(7L, "喝水提醒", agent, context);

        AtomicBoolean disposed = new AtomicBoolean(false);
        handle.subscription.set(new Disposable() {
            @Override
            public void dispose() {
                disposed.set(true);
            }

            @Override
            public boolean isDisposed() {
                return disposed.get();
            }
        });

        assertThat(registry.cancel(7L)).isTrue();

        assertThat(disposed).as("必须 dispose 流订阅（否则上游还在产 token）").isTrue();
        verify(agent).interrupt(context);
        assertThat(handle.cancelled).isTrue();
        assertThat(registry.wasCancelled(7L)).isTrue();
        // dispose 不会触发 onComplete，必须自己放行等待中的执行线程
        assertThat(handle.done.getCount()).isZero();
    }

    @Test
    void 重复取消只生效一次() {
        ScheduledTaskExecutionRegistry registry = new ScheduledTaskExecutionRegistry(Runnable::run);
        HarnessAgent agent = mock(HarnessAgent.class);
        RuntimeContext ctx = context();
        ScheduledTaskExecutionRegistry.Handle handle = registry.register(8L, "任务", agent, ctx);
        AtomicBoolean disposed = new AtomicBoolean(false);
        handle.subscription.set(new Disposable() {
            @Override
            public void dispose() {
                disposed.set(true);
            }

            @Override
            public boolean isDisposed() {
                return disposed.get();
            }
        });

        assertThat(registry.cancel(8L)).isTrue();
        assertThat(registry.cancel(8L)).isTrue();

        // 同一个上下文实例：重复取消只中断一次
        verify(agent, times(1)).interrupt(ctx);
    }

    @Test
    void 取消了不存在或已结束的执行返回false() {
        ScheduledTaskExecutionRegistry registry = new ScheduledTaskExecutionRegistry(Runnable::run);

        assertThat(registry.cancel(999L)).isFalse();
        assertThat(registry.cancel(null)).isFalse();
        assertThat(registry.isRunning(999L)).isFalse();
    }

    @Test
    void 注销后不再可取消() {
        ScheduledTaskExecutionRegistry registry = new ScheduledTaskExecutionRegistry(Runnable::run);
        HarnessAgent agent = mock(HarnessAgent.class);
        registry.register(9L, "任务", agent, context());
        registry.unregister(9L);

        assertThat(registry.cancel(9L)).isFalse();
        verify(agent, never()).interrupt(context());
    }

    @Test
    void 正常结束时放行等待线程且不算取消() throws Exception {
        ScheduledTaskExecutionRegistry registry = new ScheduledTaskExecutionRegistry(Runnable::run);
        ScheduledTaskExecutionRegistry.Handle handle =
                registry.register(10L, "任务", mock(HarnessAgent.class), context());

        handle.finish();

        assertThat(handle.done.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(registry.wasCancelled(10L)).isFalse();
    }

    @Test
    void 无上下文时退回无参中断() {
        ScheduledTaskExecutionRegistry registry = new ScheduledTaskExecutionRegistry(Runnable::run);
        HarnessAgent agent = mock(HarnessAgent.class);
        registry.register(11L, "任务", agent, null);

        assertThat(registry.cancel(11L)).isTrue();

        verify(agent).interrupt();
    }

    @Test
    void 默认的异步中断也能生效() throws Exception {
        ScheduledTaskExecutionRegistry registry = new ScheduledTaskExecutionRegistry();
        HarnessAgent agent = mock(HarnessAgent.class);
        RuntimeContext ctx = context();
        registry.register(12L, "任务", agent, ctx);

        assertThat(registry.cancel(12L)).isTrue();

        // 默认实现用后台线程调 interrupt，给一点时间
        verify(agent, org.mockito.Mockito.timeout(2000)).interrupt(ctx);
    }
}
