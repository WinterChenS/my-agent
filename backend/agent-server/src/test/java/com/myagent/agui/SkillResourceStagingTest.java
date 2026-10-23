package com.myagent.agui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.myagent.skillconfig.dto.SkillImportRequest;
import com.myagent.skillconfig.importer.SkillRepoFetcher;
import com.myagent.skillconfig.service.SkillImportService;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.harness.agent.skill.runtime.MarketplaceStager;
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
 * 渐进式披露的「第二层」确实能落到磁盘：数据库 → 官方仓库 → 工作区 {@code .skills-cache}。
 *
 * <p>为什么必须测这一段：模型拿到的系统提示里只有技能的 name/description 与一个
 * {@code <files-root>}；正文和 {@code references/}、{@code scripts/} 都是**运行时按需**用
 * {@code load_skill_through_path} 读的。如果资源没被 stage 到磁盘，技能"看起来装好了"，
 * 实际一用就找不到文件 —— 这正是「管理页只看到一层 markdown」时最容易被误判的地方。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SkillResourceStagingTest {

    @Autowired
    private SkillImportService importService;

    @Autowired
    private SkillRepositorySync skillRepositorySync;

    @MockBean
    private SkillRepoFetcher repoFetcher;

    private static String skillMd(String name) {
        return "---\nname: " + name + "\ndescription: 两层技能\n---\n\n# 正文\n\n细节见 references/quick-reference.md\n";
    }

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

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void 资源会被官方stager落到工作区_模型才能按需读取() throws Exception {
        String name = "stage-" + System.nanoTime();
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("repo-main/skills/" + name + "/SKILL.md", text(skillMd(name)));
        files.put("repo-main/skills/" + name + "/references/quick-reference.md", text("# 完整规则"));
        files.put("repo-main/skills/" + name + "/scripts/search.py", text("print('search')"));
        when(repoFetcher.fetch(any(), any()))
                .thenReturn(new SkillRepoFetcher.Fetched(zip(files), "https://example.com/x.zip"));

        importService.install(
                new SkillImportRequest("https://example.com/stage.zip", null, false, List.of(), true, true));

        // 官方仓库（运行时真正读取的来源）里应当有正文 + 资源
        AgentSkill skill;
        try (AgentSkillRepository repository = skillRepositorySync.newRepository()) {
            skill = repository.getSkill(name);
        }
        assertThat(skill).isNotNull();
        assertThat(skill.getSkillContent()).contains("references/quick-reference.md");
        assertThat(skill.getResources())
                .containsKeys("references/quick-reference.md", "scripts/search.py");

        // 官方 MarketplaceStager 把资源写到工作区（系统提示里的 <files-root> 指向这里）
        Path workspace = Files.createTempDirectory("skill-stage-");
        try {
            MarketplaceStager stager = new MarketplaceStager(workspace);
            Map<AgentSkillRepository, String> namespaces;
            try (AgentSkillRepository repository = skillRepositorySync.newRepository()) {
                namespaces = MarketplaceStager.resolveSourceNamespaces(List.of(repository));
                stager.stage(
                        List.of(new MarketplaceStager.RepoBound(skill, repository)), namespaces);
            }

            List<Path> staged = Files.walk(workspace)
                    .filter(Files::isRegularFile)
                    .toList();
            assertThat(staged)
                    .as("资源文件应被 stage 到工作区")
                    .anySatisfy(path -> assertThat(path.getFileName().toString()).isEqualTo("quick-reference.md"))
                    .anySatisfy(path -> assertThat(path.getFileName().toString()).isEqualTo("search.py"));
            assertThat(staged)
                    .anySatisfy(path -> assertThat(readQuietly(path)).contains("print('search')"));
        } finally {
            deleteRecursively(workspace);
        }
    }

    private static String readQuietly(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return "";
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
