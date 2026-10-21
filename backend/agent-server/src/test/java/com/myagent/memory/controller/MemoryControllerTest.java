package com.myagent.memory.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.myagent.common.BizException;
import org.junit.jupiter.api.Test;

/**
 * 「这次操作谁的记忆」的判定。
 *
 * <p>这是 `/api/memory/**` 唯一的权限闸口（该路径不在 SecurityConfig 的 CONFIG_PATHS 里，
 * 安全配置兜不住），所以单独钉住三条：默认自己 / 超管可指定别人 / 普通用户指定别人 403。
 */
class MemoryControllerTest {

    private static final Long ME = 7L;
    private static final Long OTHER = 9L;

    @Test
    void 不传userId就是自己() {
        assertThat(MemoryController.resolveTargetUser(ME, "USER", null)).isEqualTo(ME);
        assertThat(MemoryController.resolveTargetUser(ME, "ADMIN", null)).isEqualTo(ME);
    }

    @Test
    void 传自己的id还是自己_普通用户也不报错() {
        assertThat(MemoryController.resolveTargetUser(ME, "USER", ME)).isEqualTo(ME);
        assertThat(MemoryController.resolveTargetUser(ME, "ADMIN", ME)).isEqualTo(ME);
    }

    @Test
    void 超管可以看别人的记忆() {
        assertThat(MemoryController.resolveTargetUser(ME, "ADMIN", OTHER)).isEqualTo(OTHER);
    }

    @Test
    void 普通用户看别人的记忆被拒() {
        assertThatThrownBy(() -> MemoryController.resolveTargetUser(ME, "USER", OTHER))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只能查看自己的记忆");
    }

    @Test
    void 角色缺失时按普通用户处理() {
        assertThatThrownBy(() -> MemoryController.resolveTargetUser(ME, null, OTHER))
                .isInstanceOf(BizException.class);
    }
}
