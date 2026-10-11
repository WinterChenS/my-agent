package com.myagent.agui;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.agui.event.AguiEvent;
import org.junit.jupiter.api.Test;

/**
 * AG-UI 终态保护：`RUN_ERROR` 之后不能再下发事件。
 *
 * <p>回归的是用户实测的真实报错：官方扩展在错误后又发 `RUN_FINISHED`，
 * 官方客户端因此抛出 "Cannot send event type 'RUN_FINISHED'..."。
 */
class AguiStreamGuardTest {

    /** 用真实事件对象（AguiEvent 是密封接口，Mockito 无法 mock）。 */
    private static AguiEvent started() {
        return new AguiEvent.RunStarted("t1", "r1");
    }

    private static AguiEvent error() {
        return new AguiEvent.RunError("t1", "r1", "boom", "INTERNAL_ERROR");
    }

    private static AguiEvent finished() {
        return new AguiEvent.RunFinished("t1", "r1");
    }

    @Test
    void 错误之前的事件正常下发() {
        AguiStreamGuard guard = new AguiStreamGuard();

        assertThat(guard.shouldSend(started())).isTrue();
        assertThat(guard.shouldSend(started())).isTrue();
        assertThat(guard.isErrored()).isFalse();
    }

    @Test
    void RUN_ERROR本身要下发并进入终态() {
        AguiStreamGuard guard = new AguiStreamGuard();

        assertThat(guard.shouldSend(error())).isTrue();
        assertThat(guard.isErrored()).isTrue();
    }

    @Test
    void RUN_ERROR之后的RUN_FINISHED必须被丢弃() {
        AguiStreamGuard guard = new AguiStreamGuard();
        guard.shouldSend(error());

        // 官方扩展就是这么发的：错误之后又来一个 RUN_FINISHED
        assertThat(guard.shouldSend(finished())).isFalse();
        // 之后任何事件都不应再下发
        assertThat(guard.shouldSend(started())).isFalse();
    }

    @Test
    void 官方已发过错误时不再重复发兜底错误() {
        AguiStreamGuard guard = new AguiStreamGuard();
        guard.shouldSend(error());

        assertThat(guard.shouldSendFallbackError()).isFalse();
    }

    @Test
    void 没有错误时允许发兜底错误() {
        AguiStreamGuard guard = new AguiStreamGuard();

        assertThat(guard.shouldSendFallbackError()).isTrue();
        assertThat(guard.isErrored()).isTrue();
    }
}
