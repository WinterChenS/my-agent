package com.myagent.agui.middleware;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 记忆提醒中间件：把记忆里的关键事实提到系统提示最前面。
 *
 * <p>起因（实测）：框架的 {@code <memory_context>} 靠后且体量大，真模型会忽略它、改用
 * {@code memory_search}（整串字面量匹配 → 多词查询 0 命中），然后回答“没有记录”。
 */
class MemoryHintMiddlewareTest {

    private static final String MEMORY_MD = """
            # MEMORY.md

            ## 用户偏好
            - 用户希望被称呼为「主人」（后续对话中一律使用该称呼）。
            - 喜欢简洁、先给结论的回答。

            ## 项目环境
            - 后端项目目录：`D:/project/my-agent/backend/agent-server`。
            - 常用语言：中文。

            ## 待办
            - 用户常驻城市：杭州。
            """;

    @Test
    void 优先取用户偏好小节_再按关键词补充() {
        List<String> lines = MemoryHintMiddleware.pickLines(MEMORY_MD);
        assertThat(lines).isNotEmpty();
        // 偏好小节的两条在最前
        assertThat(lines.get(0)).contains("主人");
        assertThat(lines.get(1)).contains("简洁");
        // 关键词补充：语言/城市
        assertThat(String.join(" | ", lines)).contains("中文").contains("杭州");
        // 纯项目路径这类无关行不进来（除非命中关键词）
        assertThat(lines).noneMatch(line -> line.startsWith("后端项目目录"));
    }

    @Test
    void 限量_最多12条且总长有限() {
        StringBuilder md = new StringBuilder("## 用户偏好\n");
        for (int i = 0; i < 40; i++) {
            md.append("- 偏好条目 number ").append(i).append('\n');
        }
        List<String> lines = MemoryHintMiddleware.pickLines(md.toString());
        assertThat(lines).hasSizeLessThanOrEqualTo(MemoryHintMiddleware.MAX_LINES);
    }

    @Test
    void 去重_相同条目只出现一次() {
        String md = "## 用户偏好\n- 用户希望被称呼为「主人」。\n- 用户希望被称呼为「主人」。\n";
        assertThat(MemoryHintMiddleware.pickLines(md)).hasSize(1);
    }

    @Test
    void 读到该用户的MEMORY_并把事实拼在最前面(@TempDir Path root) throws Exception {
        Path userDir = root.resolve("1");
        Files.createDirectories(userDir);
        Files.writeString(userDir.resolve("MEMORY.md"), MEMORY_MD, StandardCharsets.UTF_8);
        MemoryHintMiddleware middleware = new MemoryHintMiddleware(root);

        String systemPrompt = "你是用户的个人助手。";
        String merged = middleware
                .onSystemPrompt(null, ctx("1"), systemPrompt)
                .block();

        assertThat(merged).startsWith(MemoryHintMiddleware.TITLE);
        assertThat(merged).contains("主人");
        assertThat(merged).endsWith(systemPrompt);
        // 明确禁止再声称没有记录（这是用户看到的错误回答）
        assertThat(merged).contains("不要再声称“没有记录”");
        // 关键：称呼要**主动生效**（用户要求“以后都叫我主人”，不是被问了才叫）
        assertThat(merged).contains("每次回答都要用这里的称呼称呼用户");
    }

    @Test
    void 没有MEMORY_文件时原样放行(@TempDir Path root) {
        MemoryHintMiddleware middleware = new MemoryHintMiddleware(root);
        assertThat(middleware.onSystemPrompt(null, ctx("1"), "原提示").block()).isEqualTo("原提示");
    }

    @Test
    void 拿不到userId时原样放行(@TempDir Path root) {
        MemoryHintMiddleware middleware = new MemoryHintMiddleware(root);
        assertThat(middleware.onSystemPrompt(null, ctx(null), "原提示").block()).isEqualTo("原提示");
    }

    /** 只用到 getUserId() 的最小 RuntimeContext。 */
    private static io.agentscope.core.agent.RuntimeContext ctx(String userId) {
        return io.agentscope.core.agent.RuntimeContext.builder().userId(userId).build();
    }
}
