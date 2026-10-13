package com.myagent.agui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.ImageInputContent;
import io.agentscope.core.agui.model.InputContent;
import io.agentscope.core.agui.model.InputContentDataSource;
import io.agentscope.core.agui.model.RunAgentInput;
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
}
