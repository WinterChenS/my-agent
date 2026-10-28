package com.myagent.agui.connector.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** HTML → 纯文本：只要求"模型能读懂"，不追求还原度。 */
class HtmlTextTest {

    @Test
    void 去掉脚本样式与标签_保留段落结构() {
        String html = "<html><head><style>p{color:red}</style></head><body>"
                + "<p>第一段</p><script>alert('x')</script><p>第二段<br>换行</p>"
                + "<ul><li>项目一</li><li>项目二</li></ul></body></html>";

        String text = HtmlText.toPlain(html);

        assertThat(text).doesNotContain("<p>", "<script", "alert('x')", "color:red");
        assertThat(text).contains("第一段", "第二段", "换行", "- 项目一", "- 项目二");
    }

    @Test
    void 常见实体被解码() {
        assertThat(HtmlText.toPlain("a &amp; b &lt;c&gt; &nbsp;d &quot;e&quot;"))
                .isEqualTo("a & b <c> d \"e\"");
    }

    @Test
    void 连续空白压缩_但保留换行() {
        String text = HtmlText.toPlain("<p>a     b</p><p>c</p>");
        assertThat(text).isEqualTo("a b\nc");
    }

    @Test
    void 空输入返回空串() {
        assertThat(HtmlText.toPlain(null)).isEmpty();
        assertThat(HtmlText.toPlain("  ")).isEmpty();
    }
}
