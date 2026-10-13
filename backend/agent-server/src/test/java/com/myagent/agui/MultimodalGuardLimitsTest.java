package com.myagent.agui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.ImageInputContent;
import io.agentscope.core.agui.model.InputContent;
import io.agentscope.core.agui.model.InputContentDataSource;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.model.TextInputContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** tier1-upgrades ①：图片限制（张数 / 大小 / 格式）。 */
class MultimodalGuardLimitsTest {

    private final MultimodalGuard guard = new MultimodalGuard();

    private static RunAgentInput inputWith(List<String> mimesAndValues) {
        List<InputContent> parts = new ArrayList<>();
        for (String mime : mimesAndValues) {
            parts.add(new ImageInputContent(new InputContentDataSource("AAAA", mime), Map.of()));
        }
        return RunAgentInput.builder()
                .threadId("t")
                .runId("r")
                .messages(List.of(AguiMessage.userMessage("m1", parts)))
                .build();
    }

    @Test
    void 合法图片无问题() {
        assertTrue(guard.validateImages(inputWith(List.of("image/png", "image/jpeg"))).isEmpty());
    }

    @Test
    void 格式不支持会被指出() {
        List<String> problems = guard.validateImages(inputWith(List.of("image/bmp")));
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("格式不支持"), problems.get(0));
    }

    @Test
    void 超过五张会被指出() {
        List<String> problems = guard.validateImages(
                inputWith(List.of("image/png", "image/png", "image/png", "image/png", "image/png", "image/png")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("最多发送 5 张")), problems.toString());
    }

    @Test
    void 过大图片会被指出() {
        // 约 5.3MB 的 base64（长度 /4*3 估算）
        String huge = "A".repeat((int) (5.5 * 1024 * 1024 / 3 * 4));
        RunAgentInput input = RunAgentInput.builder()
                .threadId("t")
                .runId("r")
                .messages(List.of(AguiMessage.userMessage("m1", List.of(
                        new ImageInputContent(new InputContentDataSource(huge, "image/png"), Map.of())))))
                .build();
        List<String> problems = guard.validateImages(input);
        assertTrue(problems.stream().anyMatch(p -> p.contains("过大")), problems.toString());
    }

    @Test
    void 剥离图片时连附件路径一起去掉_否则模型会去读图撑爆上下文() {
        // 复现实测事故：消息里留着「[已上传附件] uploads/x.png」→ 模型 read_file 读 1.3MB 照片
        // → 184 万字符进上下文 → 127 万 tokens → 厂商 context_length_exceeded。
        String text = "看看这张图\n[已上传附件] uploads/陈思成.png";
        String out = MultimodalGuard.dropImageAttachmentLines(text);
        org.junit.jupiter.api.Assertions.assertFalse(out.toLowerCase().contains("uploads/"), out);
        org.junit.jupiter.api.Assertions.assertFalse(out.contains("已上传附件"), out);
        org.junit.jupiter.api.Assertions.assertTrue(out.contains("看看这张图"), out);
    }

    @Test
    void 非图片附件路径要保留_模型仍需要它们() {
        String text = "看下这个\n[已上传附件] uploads/报表.xlsx\n[已上传附件] uploads/图.png";
        String out = MultimodalGuard.dropImageAttachmentLines(text);
        org.junit.jupiter.api.Assertions.assertTrue(out.contains("报表.xlsx"), out);
        org.junit.jupiter.api.Assertions.assertFalse(out.toLowerCase().contains(".png"), out);
    }

    @Test
    void 剥离后的提示要明确告知且劝止读图() {
        RunAgentInput input = RunAgentInput.builder()
                .threadId("t")
                .runId("r")
                .messages(java.util.List.of(AguiMessage.userMessage("m1", java.util.List.of(
                        new TextInputContent("看看这张图\n[已上传附件] uploads/x.png"),
                        new ImageInputContent(new InputContentDataSource("AAAA", "image/png"), Map.of())))))
                .build();
        MultimodalGuard.Result result = guard.apply(input, false);
        io.agentscope.core.agui.model.AguiMessage msg = result.input().getMessages().get(0);
        // block 形态的消息 getTextContent() 为 null，直接从内容块里取文本
        StringBuilder buf = new StringBuilder();
        for (InputContent part : ((io.agentscope.core.agui.model.MessageContent.Blocks) msg.getContent()).parts()) {
            if (part instanceof TextInputContent textPart) {
                buf.append(textPart.text());
            }
        }
        String text = buf.toString();
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("不支持图片输入"), text);
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("不要去读取"), text);
        org.junit.jupiter.api.Assertions.assertFalse(text.toLowerCase().contains("x.png"), text);
    }
}
