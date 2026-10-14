package com.myagent.agui.context;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话上下文（tier1-upgrades ②）：无状态时的用量、清空的幂等性、摘要预览。
 *
 * <p>压缩本身依赖官方 ConversationCompactor + 真实模型，由浏览器/接口实测覆盖（见 verification.md）；
 * 这里覆盖的是**不依赖模型**的那部分：状态缺失、清空、预览截断。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SessionContextServiceTest {

    @Autowired
    private SessionContextService service;

    @Test
    void 没有状态的会话用量为0且不报错() {
        // 新建会话（或已清空上下文）时 agent_state 没有该 threadId 的行 —— 必须是 0 而不是抛异常
        SessionContextService.Usage usage = service.usage("no-such-thread-" + System.nanoTime(), null);
        assertThat(usage.estimatedTokens()).isZero();
        assertThat(usage.messages()).isZero();
        assertThat(usage.compacted()).isFalse();
        assertThat(usage.summaryPreview()).isNull();
    }

    @Test
    void 清空不存在的会话是幂等的() {
        // 用户连点两次「清空上下文」不该报错
        assertThat(service.clear("no-such-thread-" + System.nanoTime())).isZero();
    }

    @Test
    void 压缩没有状态的会话直接返回当前用量() {
        String threadId = "no-such-thread-" + System.nanoTime();
        SessionContextService.Usage usage = service.compact(threadId, null);
        assertThat(usage.estimatedTokens()).isZero();
        assertThat(usage.messages()).isZero();
    }

    @Test
    void 摘要预览去换行并截断() {
        // 摘要预览是给浮层看的一行字，不能带换行、也不能无限长
        String longSummary = "第一行\n第二行".repeat(60);
        String preview = SessionContextService.preview(longSummary);
        assertThat(preview).doesNotContain("\n");
        assertThat(preview.length()).isLessThanOrEqualTo(81); // 80 + 省略号
        assertThat(preview).endsWith("…");
    }

    @Test
    void 摘要为空时不产生预览() {
        assertThat(SessionContextService.preview(null)).isNull();
        assertThat(SessionContextService.preview("   ")).isNull();
        assertThat(SessionContextService.preview("短的摘要")).isEqualTo("短的摘要");
    }

}
