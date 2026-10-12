package com.myagent.agui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Phase 17：会话标题的清洗与回退规则（模型输出不可控，必须有确定的兜底）。 */
class SessionTitleServiceTest {

    @Test
    void 清洗掉引号换行并限长() {
        assertThat(SessionTitleService.sanitize("「钉钉登录接入」")).isEqualTo("钉钉登录接入");
        assertThat(SessionTitleService.sanitize("\"Python 脚本\"\n")).isEqualTo("Python 脚本");
        assertThat(SessionTitleService.sanitize("  多   空格  ")).isEqualTo("多   空格");
        // 超长截断到 24 字
        String longText = "一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十";
        assertThat(SessionTitleService.sanitize(longText)).hasSize(24);
    }

    @Test
    void 空或空白返回空串() {
        assertThat(SessionTitleService.sanitize(null)).isEmpty();
        assertThat(SessionTitleService.sanitize("   ")).isEmpty();
    }

    @Test
    void 回退标题按首条消息截断() {
        assertThat(SessionTitleService.fallbackTitle("帮我写个脚本")).isEqualTo("帮我写个脚本");
        String longMsg = "请帮我分析这份钉钉登录接入手册的要点并给出改进建议";
        String title = SessionTitleService.fallbackTitle(longMsg);
        assertThat(title).endsWith("…").hasSize(21);
    }
}
