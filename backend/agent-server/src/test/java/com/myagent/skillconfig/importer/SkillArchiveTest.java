package com.myagent.skillconfig.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.myagent.common.BizException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/**
 * 仓库归档拆包：多技能仓库、资源归组、二进制、zip-slip、体量限制。
 *
 * <p>纯单元测试（不联网、不连库）：这是导入链路上最容易出错的一环，必须先在这里钉死。
 */
class SkillArchiveTest {

    private static final String SKILL_MD = """
            ---
            name: %s
            description: %s
            ---

            # %s

            正文说明。
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

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void 多技能仓库按SKILL_md拆成多个技能并归组资源() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("repo-main/README.md", text("仓库说明（不是技能）"));
        files.put("repo-main/skills/pdf/SKILL.md", text(SKILL_MD.formatted("pdf", "处理 PDF", "PDF")));
        files.put("repo-main/skills/pdf/scripts/run.py", text("print('pdf')"));
        files.put("repo-main/skills/pdf/references/doc.md", text("# 参考"));
        files.put("repo-main/skills/excel/SKILL.md", text(SKILL_MD.formatted("excel", "处理表格", "Excel")));

        List<SkillArchive.Bundle> bundles = SkillArchive.read(zip(files), SkillArchive.Limits.defaults());

        assertThat(bundles).hasSize(2);
        SkillArchive.Bundle pdf = bundles.stream()
                .filter(b -> b.root().equals("skills/pdf"))
                .findFirst()
                .orElseThrow();
        assertThat(pdf.resources())
                .containsOnlyKeys("scripts/run.py", "references/doc.md");
        assertThat(pdf.markdown()).contains("name: pdf");

        SkillArchive.Bundle excel = bundles.stream()
                .filter(b -> b.root().equals("skills/excel"))
                .findFirst()
                .orElseThrow();
        assertThat(excel.resources()).isEmpty();
    }

    @Test
    void 单技能仓库_SKILL_md在根目录() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("my-skill-main/SKILL.md", text(SKILL_MD.formatted("my-skill", "演示", "演示")));
        files.put("my-skill-main/assets/logo.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2});

        List<SkillArchive.Bundle> bundles = SkillArchive.read(zip(files), SkillArchive.Limits.defaults());

        assertThat(bundles).hasSize(1);
        assertThat(bundles.get(0).root()).isEmpty();
        // 二进制资源按官方约定以 base64: 前缀存文本
        assertThat(bundles.get(0).resources()).containsKey("assets/logo.png");
        assertThat(bundles.get(0).resources().get("assets/logo.png")).startsWith("base64:");
    }

    @Test
    void 内层技能的资源不会被外层吞并() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("repo-main/SKILL.md", text(SKILL_MD.formatted("outer", "外层", "外层")));
        files.put("repo-main/notes.md", text("外层资源"));
        files.put("repo-main/inner/SKILL.md", text(SKILL_MD.formatted("inner", "内层", "内层")));
        files.put("repo-main/inner/scripts/x.sh", text("echo x"));

        List<SkillArchive.Bundle> bundles = SkillArchive.read(zip(files), SkillArchive.Limits.defaults());

        SkillArchive.Bundle outer = bundles.stream().filter(b -> b.root().isEmpty()).findFirst().orElseThrow();
        SkillArchive.Bundle inner = bundles.stream().filter(b -> b.root().equals("inner")).findFirst().orElseThrow();
        assertThat(outer.resources()).containsOnlyKeys("notes.md");
        assertThat(inner.resources()).containsOnlyKeys("scripts/x.sh");
    }

    @Test
    void zip_slip路径被丢弃() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("repo-main/SKILL.md", text(SKILL_MD.formatted("a", "a", "A")));
        files.put("repo-main/../../etc/passwd", text("root:x:0:0"));
        files.put("/abs/path.txt", text("x"));

        List<SkillArchive.Bundle> bundles = SkillArchive.read(zip(files), SkillArchive.Limits.defaults());

        assertThat(bundles.get(0).resources()).isEmpty();
    }

    @Test
    void 没有SKILL_md时报明确错误() {
        Map<String, byte[]> files = Map.of("repo-main/README.md", text("只有说明"));

        assertThatThrownBy(() -> SkillArchive.read(zip(files), SkillArchive.Limits.defaults()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("SKILL.md");
    }

    @Test
    void 单个文件超过上限直接报错() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("repo-main/SKILL.md", text(SKILL_MD.formatted("a", "a", "A")));
        files.put("repo-main/big.txt", new byte[2048]);

        SkillArchive.Limits tiny = new SkillArchive.Limits(1024 * 1024, 100, 1024, 1024 * 1024, 100);
        assertThatThrownBy(() -> SkillArchive.read(zip(files), tiny))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("超过上限");
    }

    @Test
    void 归档整体超过上限直接报错() {
        byte[] archive = zip(Map.of("repo-main/SKILL.md", text(SKILL_MD.formatted("a", "a", "A"))));
        SkillArchive.Limits tiny = new SkillArchive.Limits(10, 100, 1024, 1024, 10);

        assertThatThrownBy(() -> SkillArchive.read(archive, tiny))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("归档超过上限");
    }
}
