package com.myagent.agui.skillimport;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 技能文件目录定位：官方的 {@code <available_skills>} 不带 files-root，
 * 模型要跑 {@code bin/xxx.mjs} 得知道真实路径。
 */
class SkillFileLocatorTest {

    private static Path makeSkill(Path cache, String userId, String namespace, String skill,
            String... relativeFiles) throws Exception {
        Path dir = cache.resolve(userId).resolve(namespace).resolve(skill);
        Files.createDirectories(dir);
        for (String rel : relativeFiles) {
            Path file = dir.resolve(rel);
            Files.createDirectories(file.getParent() == null ? dir : file.getParent());
            Files.writeString(file, "x");
        }
        return dir;
    }

    @Test
    void 按官方缓存结构定位到技能目录(@TempDir Path workspace) throws Exception {
        Path cache = workspace.resolve(".skills-cache");
        makeSkill(cache, "1", "postgresql_agentscope_agentscope_skills", "archify", "bin/archify.mjs");
        makeSkill(cache, "1", "postgresql_agentscope_agentscope_skills", "theme-factory", "themes/a.json");

        Map<String, Path> located = new SkillFileLocator(workspace).locate("1");

        assertThat(located).containsKeys("archify", "theme-factory");
        assertThat(located.get("archify").getFileName().toString()).isEqualTo("archify");
    }

    @Test
    void 不写死命名空间_不同仓库前缀都能扫到(@TempDir Path workspace) throws Exception {
        Path cache = workspace.resolve(".skills-cache");
        makeSkill(cache, "1", "repo-a", "skill-a", "SKILL.md");
        makeSkill(cache, "1", "repo-b", "skill-b", "SKILL.md");

        assertThat(new SkillFileLocator(workspace).locate("1"))
                .containsKeys("skill-a", "skill-b");
    }

    @Test
    void 不同用户互不干扰(@TempDir Path workspace) throws Exception {
        Path cache = workspace.resolve(".skills-cache");
        makeSkill(cache, "1", "ns", "mine", "a.txt");
        makeSkill(cache, "2", "ns", "other", "a.txt");

        SkillFileLocator locator = new SkillFileLocator(workspace);
        assertThat(locator.locate("1")).containsOnlyKeys("mine");
        assertThat(locator.locate("2")).containsOnlyKeys("other");
    }

    @Test
    void 没有缓存目录时返回空而不是报错(@TempDir Path workspace) {
        SkillFileLocator locator = new SkillFileLocator(workspace);

        assertThat(locator.locate("1")).isEmpty();
        assertThat(locator.locate(null)).isEmpty();
        assertThat(locator.locate("1", "archify")).isEmpty();
    }

    @Test
    void 能猜出bin下的可执行入口(@TempDir Path workspace) throws Exception {
        Path dir = makeSkill(workspace.resolve(".skills-cache"), "1", "ns", "archify",
                "bin/archify.mjs", "bin/helper.js");

        assertThat(SkillFileLocator.guessEntrypoint(dir, "archify")).contains("bin/archify.mjs");
    }

    @Test
    void 没有bin时尝试根目录同名脚本(@TempDir Path workspace) throws Exception {
        Path dir = makeSkill(workspace.resolve(".skills-cache"), "1", "ns", "mytool", "mytool.py");

        assertThat(SkillFileLocator.guessEntrypoint(dir, "mytool")).contains("mytool.py");
    }
}
