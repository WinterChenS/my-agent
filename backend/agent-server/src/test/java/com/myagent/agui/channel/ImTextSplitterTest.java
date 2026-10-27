package com.myagent.agui.channel;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 超长消息拆分：**分多条发，不截断**（用户反馈：内容长的时候会被截掉）。
 *
 * <p>以前平台侧统一截到 3000 字并追加"…（内容过长，已截断）"，等于主动丢内容；
 * 现在按 IM 单条上限拆成多条，每条带 {@code （i/N）} 前缀。
 */
class ImTextSplitterTest {

    private static ImTextSplitter splitter(int max) {
        return new ImTextSplitter(max);
    }

    @Test
    void 没超长就一条_原样返回() {
        assertThat(splitter(100).split("你好")).containsExactly("你好");
        assertThat(splitter(100).split("")).isEmpty();
        assertThat(splitter(100).split(null)).isEmpty();
    }

    @Test
    void 超长会拆成多条_并带序号前缀() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            text.append("第").append(i).append("行：这是一句用来凑长度的话。\n");
        }
        List<String> parts = splitter(200).split(text.toString());

        assertThat(parts).hasSizeGreaterThan(1);
        assertThat(parts.get(0)).startsWith("（1/" + parts.size() + "）");
        assertThat(parts.get(parts.size() - 1)).startsWith("（" + parts.size() + "/" + parts.size() + "）");
        // 每条都在上限内（前缀 10 + 预算）
        for (String part : parts) {
            assertThat(part.length()).isLessThanOrEqualTo(200);
        }
        // 内容不丢：去掉序号与换行后拼起来应覆盖原文的每一行
        String joined = String.join("", parts);
        assertThat(joined).contains("第0行").contains("第39行");
    }

    @Test
    void 优先在空行处分段() {
        String a = "A".repeat(120);
        String b = "B".repeat(120);
        List<String> parts = splitter(200).split(a + "\n\n" + b);

        assertThat(parts).hasSize(2);
        assertThat(parts.get(0)).contains("A").doesNotContain("B");
        assertThat(parts.get(1)).contains("B").doesNotContain("A");
    }

    @Test
    void 没有可切点也能硬切_且不切坏emoji() {
        // 单行、无标点、无空格；两端都是 emoji 代理对
        String emoji = "😀";
        String text = emoji.repeat(200);
        List<String> parts = splitter(120).split(text);

        assertThat(parts).hasSizeGreaterThan(1);
        for (String part : parts) {
            assertThat(part.length()).isLessThanOrEqualTo(120);
            // 每段都必须能被成对还原：末位不能是孤立的高位代理
            assertThat(Character.isHighSurrogate(part.charAt(part.length() - 1)))
                    .as("不能把 emoji 劈成两半")
                    .isFalse();
        }
    }

    @Test
    void Msg拆分_图片块只挂在最后一条() {
        Msg msg = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(List.of(
                        TextBlock.builder().text("X".repeat(300)).build(),
                        new io.agentscope.core.message.ImageBlock(
                                new io.agentscope.core.message.URLSource("https://example.com/a.png"))))
                .build();

        List<Msg> parts = splitter(120).splitMsg(msg);

        assertThat(parts).hasSizeGreaterThan(1);
        for (int i = 0; i < parts.size() - 1; i++) {
            assertThat(parts.get(i).getContent()).hasSize(1);
        }
        assertThat(parts.get(parts.size() - 1).getContent()).hasSize(2);
    }

    @Test
    void 未超长的Msg原样返回同一个对象() {
        Msg msg = Msg.builder().role(MsgRole.ASSISTANT).textContent("短的").build();
        assertThat(splitter(120).splitMsg(msg)).containsExactly(msg);
    }
}
