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

    @org.junit.jupiter.api.Test
    void 剥离附件说明块_标题不被路径提示污染() {
        String withBlock = "评价一下这张图片\n[已上传附件] uploads/a.png\n[已上传附件] uploads/b.png";
        org.junit.jupiter.api.Assertions.assertEquals(
                "评价一下这张图片", SessionTitleService.stripAttachmentBlock(withBlock));
        org.junit.jupiter.api.Assertions.assertEquals(
                "普通消息", SessionTitleService.stripAttachmentBlock("普通消息"));
        org.junit.jupiter.api.Assertions.assertEquals(
                "", SessionTitleService.stripAttachmentBlock("[已上传附件] uploads/a.png"));
    }

    @org.junit.jupiter.api.Test
    void 回退标题不含附件说明块() {
        String title = SessionTitleService.fallbackTitle("看一下这个\n[已上传附件] uploads/x.pdf\n...");
        org.junit.jupiter.api.Assertions.assertFalse(title.contains("已上传附件"), title);
        org.junit.jupiter.api.Assertions.assertTrue(title.startsWith("看一下这个"), title);
    }

    @org.junit.jupiter.api.Test
    void 标题里不能漏出技能标记() {
        // 实测问题：会话标题出现过「请用 [[skill:P5-Skill]…」——标题是纯文本，得把标记换成人能读的
        org.junit.jupiter.api.Assertions.assertEquals(
                "请用 /P5-Skill 帮我看看",
                SessionTitleService.stripSkillMarkers("请用 [[skill:P5-Skill]] 帮我看看"));
        org.junit.jupiter.api.Assertions.assertEquals(
                "/订单查询",
                SessionTitleService.stripSkillMarkers("[[skill:订单查询]]"));
        org.junit.jupiter.api.Assertions.assertEquals(
                "普通一句话", SessionTitleService.stripSkillMarkers("普通一句话"));
    }

    @org.junit.jupiter.api.Test
    void 回退标题也不含技能标记() {
        String title = SessionTitleService.fallbackTitle("请用 [[skill:订单查询]] 帮我看看");
        org.junit.jupiter.api.Assertions.assertFalse(title.contains("[[skill:"), title);
        org.junit.jupiter.api.Assertions.assertTrue(title.contains("/订单查询"), title);
    }
}
