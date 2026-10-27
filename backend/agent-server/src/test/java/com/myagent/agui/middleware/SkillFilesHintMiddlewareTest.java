package com.myagent.agui.middleware;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.agent.RuntimeContext;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 技能文件位置提示：把落盘目录写进系统提示，避免模型全盘搜索。
 */
class SkillFilesHintMiddlewareTest {

    private static void makeSkill(Path workspace, String userId, String skill, String... files)
            throws Exception {
        Path dir = workspace.resolve(".skills-cache").resolve(userId)
                .resolve("postgresql_agentscope_agentscope_skills").resolve(skill);
        Files.createDirectories(dir);
        for (String rel : files) {
            Path file = dir.resolve(rel);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "x");
        }
    }

    @Test
    void 注入目录与可执行入口(@TempDir Path workspace) throws Exception {
        makeSkill(workspace, "1", "archify", "bin/archify.mjs", "schemas/a.json");
        SkillFilesHintMiddleware middleware = new SkillFilesHintMiddleware(workspace);

        String hint = middleware.hintFor("1");

        assertThat(hint).contains(SkillFilesHintMiddleware.TITLE)
                .contains("archify")
                .contains("bin/archify.mjs")
                .contains("不要用 dir /s");
    }

    @Test
    void 没有缓存时不注入_保持提示词原样(@TempDir Path workspace) {
        SkillFilesHintMiddleware middleware = new SkillFilesHintMiddleware(workspace);

        String prompt = "base prompt";
        String result = middleware.onSystemPrompt(null,
                RuntimeContext.builder().sessionId("t").userId("9").build(), prompt).block();

        assertThat(result).isEqualTo(prompt);
        assertThat(middleware.hintFor("9")).isNull();
    }

    @Test
    void 有缓存时追加到系统提示末尾(@TempDir Path workspace) throws Exception {
        makeSkill(workspace, "1", "archify", "bin/archify.mjs");
        SkillFilesHintMiddleware middleware = new SkillFilesHintMiddleware(workspace);

        String result = middleware.onSystemPrompt(null,
                RuntimeContext.builder().sessionId("t").userId("1").build(), "base").block();

        assertThat(result).startsWith("base").contains(SkillFilesHintMiddleware.TITLE);
    }
}
