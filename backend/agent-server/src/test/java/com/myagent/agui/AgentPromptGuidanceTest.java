package com.myagent.agui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 平台注入的系统提示片段（纯函数，不起 Spring）。
 *
 * <p>重点覆盖**长期记忆指引**：框架的 {@code memory_search} 是「整条 query 当一个字面量」匹配，
 * 模型传整句问句就会 0 命中，用户看到的就是「永久记忆不生效」。这段指引是平台侧唯一的修补点
 * （不改框架检索逻辑，门禁 G8），所以它的关键内容必须被测试钉住。
 */
class AgentPromptGuidanceTest {

    @Test
    void 记忆指引追加在原提示词之后且保留原文() {
        String out = AgentFactory.withMemoryGuidance("你是助手。");
        assertThat(out).startsWith("你是助手。");
        assertThat(out).contains("## 长期记忆（本平台能力，请正确使用）");
    }

    @Test
    void 记忆指引说清三件事_先看注入的上下文_只传短关键词_零命中要换词() {
        String out = AgentFactory.withMemoryGuidance("你是助手。");
        // ① 先看已注入的 memory_context
        assertThat(out).contains("<memory_context>");
        // ② 搜索只传短关键词、不要传整句问句（这是实测踩到的直接原因）
        assertThat(out).contains("短关键词");
        assertThat(out).contains("不要传整句问句");
        assertThat(out).contains("memory_search");
        // ③ 0 命中不等于没有记忆
        assertThat(out).contains("命中 0 条");
        // ④ 保存走官方 memory_save
        assertThat(out).contains("memory_save");
    }

    @Test
    void 没有原提示词时只给指引_不留空行() {
        assertThat(AgentFactory.withMemoryGuidance(null))
                .startsWith("## 长期记忆（本平台能力，请正确使用）");
        assertThat(AgentFactory.withMemoryGuidance("   "))
                .startsWith("## 长期记忆（本平台能力，请正确使用）");
    }

    @Test
    void 卡片指引与记忆指引可以叠加_两段都在() {
        String out = AgentFactory.withMemoryGuidance(AgentFactory.withCardGuidance("你是助手。"));
        assertThat(out).contains("## 交互卡片（本平台能力，请主动使用）");
        assertThat(out).contains("## 长期记忆（本平台能力，请正确使用）");
    }
}
