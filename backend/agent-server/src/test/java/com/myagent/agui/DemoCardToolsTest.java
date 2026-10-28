package com.myagent.agui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 选择卡的候选答案：**由 Agent 给**（用户反馈"每次弹卡都只有继续/取消，选不了语气/方案"）。
 *
 * <p>这里锁住契约：候选原样变成按钮、回传给模型的是中文原话、没给候选时才退回继续/取消。
 */
class DemoCardToolsTest {

    @Test
    void 字符串候选直接变成按钮() {
        List<Map<String, Object>> options = DemoCardTools.ConfirmCardTool.toOptions(
                List.of("更正式", "更亲切", "更简短"));

        assertThat(options).hasSize(3);
        assertThat(options.get(0)).containsEntry("label", "更正式").containsEntry("value", "更正式");
    }

    @Test
    void 对象候选取label_回传值也是人话() {
        List<Map<String, Object>> options = DemoCardTools.ConfirmCardTool.toOptions(
                List.of(Map.of("label", "就这样发送"), Map.of("value", "改得更客气")));

        assertThat(options).extracting(option -> option.get("label"))
                .containsExactly("就这样发送", "改得更客气");
        // 回传给模型的必须是用户看到的文字，不能是英文枚举值
        assertThat(options).extracting(option -> option.get("value"))
                .containsExactly("就这样发送", "改得更客气");
    }

    @Test
    void 没给候选时退回继续取消() {
        assertThat(DemoCardTools.ConfirmCardTool.toOptions(null))
                .extracting(option -> option.get("label"))
                .containsExactly("继续", "取消");
        assertThat(DemoCardTools.ConfirmCardTool.toOptions(List.of()))
                .extracting(option -> option.get("label"))
                .containsExactly("继续", "取消");
        assertThat(DemoCardTools.ConfirmCardTool.toOptions(List.of("  ", "")))
                .extracting(option -> option.get("label"))
                .containsExactly("继续", "取消");
    }

    @Test
    void 候选太多时截断到8个_防止卡片挤成一坨() {
        List<Map<String, Object>> options = DemoCardTools.ConfirmCardTool.toOptions(
                List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10"));

        assertThat(options).hasSize(8);
    }

    @Test
    void 工具声明确实描述了options() {
        DemoCardTools.ConfirmCardTool tool = new DemoCardTools.ConfirmCardTool();
        Map<String, Object> properties = (Map<String, Object>) tool.getParameters().get("properties");

        assertThat(properties).containsKeys("question", "options", "title", "allowCustom");
        assertThat(tool.getDescription()).contains("options").contains("候选");
    }
}
