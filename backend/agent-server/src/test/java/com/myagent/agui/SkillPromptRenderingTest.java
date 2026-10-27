package com.myagent.agui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.myagent.skillconfig.dto.SkillImportRequest;
import com.myagent.skillconfig.importer.SkillRepoFetcher;
import com.myagent.skillconfig.service.SkillImportService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.middleware.HarnessSkillMiddleware;
import io.agentscope.harness.agent.skill.runtime.MarketplaceStager;
import io.agentscope.harness.agent.skill.runtime.ShellPathPolicy;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 回归测试：绑定的技能必须真的出现在系统提示的 {@code <available_skills>} 里。
 *
 * <p>背景（2026-10-23 用户实测）：导入并启用技能、也绑到了 Agent，但对话里模型说
 * 「看不到 available_skills」、调用 {@code load_skill_through_path} 报 {@code Skill not found}。
 *
 * <p>这里直接调官方 {@link HarnessSkillMiddleware#onSystemPrompt} 把渲染结果拿出来看，
 * 把「技能到底有没有进提示词」钉死——这是整条链路上最容易静默失败的一环
 * （没有异常、没有日志，只是模型什么都看不到）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SkillPromptRenderingTest {

    @Autowired
    private SkillRepositorySync skillRepositorySync;

    @Autowired
    private SkillImportService importService;

    @MockBean
    private SkillRepoFetcher repoFetcher;

    private static final String SKILL_MD_TEMPLATE = """
            ---
            name: %s
            description: 渲染测试
            ---

            # 正文

            说明。
            """;

    private static byte[] zip(Map<String, byte[]> files) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
            zip.finish();
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String importOneSkill() {
        String name = "render-" + System.nanoTime();
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("repo-main/skills/" + name + "/SKILL.md",
                SKILL_MD_TEMPLATE.formatted(name).getBytes(StandardCharsets.UTF_8));
        when(repoFetcher.fetch(any(), any()))
                .thenReturn(new SkillRepoFetcher.Fetched(zip(files), "https://example.com/x.zip"));
        importService.install(
                new SkillImportRequest("https://example.com/render.zip", null, false, List.of(), true, true));
        return name;
    }

    @Test
    void 绑定的技能会出现在available_skills里且skill_id是框架的id格式() throws Exception {
        String name = importOneSkill();

        Path workspace = Files.createTempDirectory("skill-prompt-");
        try (AgentSkillRepository repository = skillRepositorySync.newRepository()) {
            HarnessSkillMiddleware middleware = new HarnessSkillMiddleware(
                    List.of(repository),
                    new Toolkit(),
                    SkillFilter.only(name),
                    null,
                    new MarketplaceStager(workspace),
                    ShellPathPolicy.noShell());

            String prompt = middleware
                    .onSystemPrompt(null, RuntimeContext.empty(), "BASE-PROMPT")
                    .block();

            assertThat(prompt).as("提示词里必须有技能清单").contains("<available_skills>");
            assertThat(prompt).as("清单里要有绑定的技能").contains(name);
            // 关键：模型要用的 skill-id 是「名字_source」，不是裸名字（实测模型按裸名字调必然 404）
            assertThat(prompt).as("skill-id 必须是框架的 id 格式").contains(name + "_myagent");
        } finally {
            deleteRecursively(workspace);
        }
    }

    @Test
    void 没绑定的技能不会出现在提示词里_这正是用户遇到Skill_not_found的原因() throws Exception {
        String bound = importOneSkill();
        String unbound = importOneSkill();

        Path workspace = Files.createTempDirectory("skill-prompt-");
        try (AgentSkillRepository repository = skillRepositorySync.newRepository()) {
            HarnessSkillMiddleware middleware = new HarnessSkillMiddleware(
                    List.of(repository),
                    new Toolkit(),
                    SkillFilter.only(bound),
                    null,
                    new MarketplaceStager(workspace),
                    ShellPathPolicy.noShell());

            String prompt = middleware
                    .onSystemPrompt(null, RuntimeContext.empty(), "BASE-PROMPT")
                    .block();

            assertThat(prompt).contains(bound);
            assertThat(prompt).as("没绑定的技能不该出现").doesNotContain(unbound);
        } finally {
            deleteRecursively(workspace);
        }
    }

    @Test
    void 带会话上下文的调用同样能渲染技能清单() throws Exception {
        String name = importOneSkill();
        Path workspace = Files.createTempDirectory("skill-prompt-");
        try (AgentSkillRepository repository = skillRepositorySync.newRepository()) {
            HarnessSkillMiddleware middleware = new HarnessSkillMiddleware(
                    List.of(repository),
                    new Toolkit(),
                    SkillFilter.only(name),
                    null,
                    new MarketplaceStager(workspace),
                    ShellPathPolicy.noShell());

            RuntimeContext context = RuntimeContext.builder()
                    .sessionId("s-" + name)
                    .userId("1")
                    .build();
            String prompt = middleware.onSystemPrompt(null, context, "BASE-PROMPT").block();

            assertThat(prompt).contains("<available_skills>").contains(name + "_myagent");
        } finally {
            deleteRecursively(workspace);
        }
    }

    private static void deleteRecursively(Path root) {
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // 临时目录清理失败不影响断言
                }
            });
        } catch (Exception ignored) {
            // 同上
        }
    }
}
