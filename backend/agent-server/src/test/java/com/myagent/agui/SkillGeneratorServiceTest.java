package com.myagent.agui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * tier1 技能 AI 生成：解析模型输出的容错。
 *
 * <p>只测纯函数 {@code parse}（不联网、不需要模型）——这是最容易出问题的一环：
 * 模型经常把 JSON 包在 ``` 里，或在前后加一句解释。
 */
class SkillGeneratorServiceTest {

    private final SkillGeneratorService service =
            new SkillGeneratorService(null, null, new ObjectMapper());

    @Test
    void 解析标准JSON() {
        SkillGeneratorService.Draft draft = service.parse(
                "{\"name\":\"order-inquiry\",\"description\":\"用户问订单时用\",\"content\":\"# 步骤\\n1. 查订单\"}");
        assertThat(draft.name()).isEqualTo("order-inquiry");
        assertThat(draft.description()).isEqualTo("用户问订单时用");
        assertThat(draft.content()).contains("1. 查订单");
        assertThat(draft.notice()).isNull();
    }

    @Test
    void 容忍代码块包裹与前后废话() {
        String raw = "好的，这是结果：\n```json\n"
                + "{\"name\":\"a-b\",\"description\":\"说明\",\"content\":\"正文\"}\n"
                + "```\n希望有帮助！";
        SkillGeneratorService.Draft draft = service.parse(raw);
        assertThat(draft.name()).isEqualTo("a-b");
        assertThat(draft.content()).isEqualTo("正文");
    }

    @Test
    void 说明缺失时填占位并给出提示() {
        // 平台强制要求 description 非空（官方 AgentSkill 校验），所以这里兜底 + 明确提示
        SkillGeneratorService.Draft draft = service.parse(
                "{\"name\":\"a-b\",\"description\":\"  \",\"content\":\"正文\"}");
        assertThat(draft.description()).isNotBlank();
        assertThat(draft.notice()).contains("说明");
    }

    @Test
    void 没有JSON时报可读的错误() {
        assertThatThrownBy(() -> service.parse("我觉得这个技能应该叫订单查询"))
                .hasMessageContaining("不是预期格式");
    }

    @Test
    void 空输出报可读的错误() {
        assertThatThrownBy(() -> service.parse("   ")).hasMessageContaining("没有返回内容");
    }

    @Test
    void 缺少name或content要报错() {
        assertThatThrownBy(() -> service.parse("{\"name\":\"\",\"content\":\"正文\"}"))
                .hasMessageContaining("缺少");
        assertThatThrownBy(() -> service.parse("{\"name\":\"a-b\",\"content\":\"\"}"))
                .hasMessageContaining("缺少");
    }
}
