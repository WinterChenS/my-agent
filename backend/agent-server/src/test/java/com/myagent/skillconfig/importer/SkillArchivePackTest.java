package com.myagent.skillconfig.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.myagent.common.BizException;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/**
 * 技能包（一个仓库多个技能 / tar.gz / 包内套包 / 子目录链接 / 插件分组）的拆包规则。
 *
 * <p>与 {@link SkillArchiveTest} 分开：那边钉基础拆包与安全边界，这里钉「技能包」这一层。
 */
class SkillArchivePackTest {

    private static final String SKILL_MD = """
            ---
            name: %s
            description: %s
            ---

            # %s

            正文说明。
            """;

    private static String md(String name) {
        return SKILL_MD.formatted(name, name + " 的说明", name);
    }

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] zip(Map<String, byte[]> files) {
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

    /** 最小 ustar 打包（技能仓库里的 tar 都是普通文件，测试够用）。 */
    static byte[] tar(Map<String, byte[]> files) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                byte[] name = file.getKey().getBytes(StandardCharsets.UTF_8);
                byte[] header = new byte[512];
                System.arraycopy(name, 0, header, 0, Math.min(name.length, 100));
                byte[] size = String.format("%011o", file.getValue().length).getBytes(StandardCharsets.US_ASCII);
                System.arraycopy(size, 0, header, 124, size.length);
                header[156] = '0';
                byte[] magic = "ustar".getBytes(StandardCharsets.US_ASCII);
                System.arraycopy(magic, 0, header, 257, magic.length);
                for (int i = 148; i < 156; i++) {
                    header[i] = ' ';
                }
                int sum = 0;
                for (byte b : header) {
                    sum += (b & 0xFF);
                }
                byte[] checksum = String.format("%06o", sum).getBytes(StandardCharsets.US_ASCII);
                System.arraycopy(checksum, 0, header, 148, Math.min(checksum.length, 8));
                out.write(header);
                out.write(file.getValue());
                int padding = (512 - (file.getValue().length % 512)) % 512;
                out.write(new byte[padding]);
            }
            out.write(new byte[1024]);
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    static byte[] gzip(byte[] raw) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
                OutputStream gz = new GZIPOutputStream(out)) {
            gz.write(raw);
            gz.flush();
            gz.close();
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void tar_gz技能包也能拆() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("repo-main/skills/pdf/SKILL.md", text(md("pdf")));
        files.put("repo-main/skills/pdf/scripts/a.py", text("print(1)"));
        files.put("repo-main/skills/xlsx/SKILL.md", text(md("xlsx")));

        List<SkillArchive.Bundle> bundles =
                SkillArchive.read(gzip(tar(files)), SkillArchive.Limits.defaults());

        assertThat(bundles).extracting(SkillArchive.Bundle::root).containsExactly("skills/pdf", "skills/xlsx");
    }

    @Test
    void 裸tar技能包也能拆() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", text(md("solo")));
        files.put("scripts/run.sh", text("echo hi"));

        List<SkillArchive.Bundle> bundles = SkillArchive.read(tar(files), SkillArchive.Limits.defaults());

        assertThat(bundles).hasSize(1);
        assertThat(bundles.get(0).root()).isEmpty();
        assertThat(bundles.get(0).resources()).containsOnlyKeys("scripts/run.sh");
    }

    @Test
    void 只装子目录时其它技能被过滤掉() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("repo-main/skills/pdf/SKILL.md", text(md("pdf")));
        files.put("repo-main/skills/pdf/scripts/a.py", text("print(1)"));
        files.put("repo-main/skills/xlsx/SKILL.md", text(md("xlsx")));

        List<SkillArchive.Bundle> bundles =
                SkillArchive.read(zip(files), "skills/pdf", SkillArchive.Limits.defaults());

        assertThat(bundles).hasSize(1);
        assertThat(bundles.get(0).root()).isEqualTo("skills/pdf");
        assertThat(bundles.get(0).resources()).containsOnlyKeys("scripts/a.py");
    }

    @Test
    void 子目录链接指错时报明确错误() {
        Map<String, byte[]> files = Map.of("repo-main/skills/pdf/SKILL.md", text(md("pdf")));

        assertThatThrownBy(() -> SkillArchive.read(zip(files), "skills/nope", SkillArchive.Limits.defaults()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("没有文件");
    }

    @Test
    void 包内再套一个技能包也能识别() {
        byte[] inner = zip(Map.of(
                "pdf/SKILL.md", text(md("pdf")),
                "pdf/scripts/a.py", text("print(1)")));
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("repo-main/dist/pdf.skill", inner);
        files.put("repo-main/README.md", text("说明"));

        List<SkillArchive.Bundle> bundles = SkillArchive.read(zip(files), SkillArchive.Limits.defaults());

        assertThat(bundles).hasSize(1);
        assertThat(bundles.get(0).root()).isEqualTo("dist/pdf");
        assertThat(bundles.get(0).resources()).containsOnlyKeys("scripts/a.py");
    }

    @Test
    void 包里的普通zip附件不影响安装() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("repo-main/SKILL.md", text(md("a")));
        files.put("repo-main/assets/data.zip", zip(Map.of("plain.txt", text("不是技能"))));

        List<SkillArchive.Bundle> bundles = SkillArchive.read(zip(files), SkillArchive.Limits.defaults());

        assertThat(bundles).hasSize(1);
        assertThat(bundles.get(0).resources()).containsKey("assets/data.zip");
    }

    @Test
    void 技能包的插件清单给出包名() {
        String manifest = "{\n"
                + "  \"name\": \"demo-marketplace\",\n"
                + "  \"plugins\": [\n"
                + "    {\"name\": \"document-skills\", \"skills\": [\"./skills/pdf\", \"./skills/docx\"]},\n"
                + "    {\"name\": \"example-skills\", \"skills\": [\"./skills/theme-factory\"]}\n"
                + "  ]\n"
                + "}\n";
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("repo-main/.claude-plugin/marketplace.json", text(manifest));
        files.put("repo-main/skills/pdf/SKILL.md", text(md("pdf")));
        files.put("repo-main/skills/docx/SKILL.md", text(md("docx")));
        files.put("repo-main/skills/theme-factory/SKILL.md", text(md("theme-factory")));
        files.put("repo-main/skills/loose/SKILL.md", text(md("loose")));

        List<SkillArchive.Bundle> bundles = SkillArchive.read(zip(files), SkillArchive.Limits.defaults());

        Map<String, String> packByRoot = new LinkedHashMap<>();
        for (SkillArchive.Bundle bundle : bundles) {
            packByRoot.put(bundle.root(), bundle.pack());
        }
        assertThat(packByRoot)
                .containsEntry("skills/pdf", "document-skills")
                .containsEntry("skills/docx", "document-skills")
                .containsEntry("skills/theme-factory", "example-skills")
                .containsEntry("skills/loose", null);
    }

    @Test
    void 清单坏掉不影响装技能() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("repo-main/.claude-plugin/marketplace.json", text("{ 这不是 json"));
        files.put("repo-main/skills/pdf/SKILL.md", text(md("pdf")));

        List<SkillArchive.Bundle> bundles = SkillArchive.read(zip(files), SkillArchive.Limits.defaults());

        assertThat(bundles).hasSize(1);
        assertThat(bundles.get(0).pack()).isNull();
    }

    @Test
    void 不认识的格式给出明确错误() {
        assertThatThrownBy(() -> SkillArchive.read(text("这不是归档"), SkillArchive.Limits.defaults()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无法识别的归档格式");
    }
}
