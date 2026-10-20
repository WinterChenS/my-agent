package com.myagent.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import com.myagent.agui.AguiProperties;
import com.myagent.artifact.service.WorkspaceArtifactScanner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 产物扫描：只收用户可见文件，排除框架内部状态（Task 10.3 门禁）。
 */
class WorkspaceArtifactScannerTest {

    private WorkspaceArtifactScanner scanner(Path root) {
        return new WorkspaceArtifactScanner(
                new AguiProperties(root.toString(), 120_000, 32_000, 30_000, 1_500, 128_000));
    }

    @Test
    void 排除框架内部目录() {
        assertThat(WorkspaceArtifactScanner.isExcluded("memory/2026-10-11.md")).isTrue();
        assertThat(WorkspaceArtifactScanner.isExcluded("agents/foo/sessions/s.jsonl")).isTrue();
        assertThat(WorkspaceArtifactScanner.isExcluded(".skills-cache/x/skills/SKILL.md")).isTrue();
        assertThat(WorkspaceArtifactScanner.isExcluded("uploads/note.md")).isTrue();
        assertThat(WorkspaceArtifactScanner.isExcluded(".DS_Store")).isTrue();
        // 框架自管的工作区文件（会被框架改写）——不能出现在右侧栏「产物」里
        assertThat(WorkspaceArtifactScanner.isExcluded("MEMORY.md")).isTrue();
        assertThat(WorkspaceArtifactScanner.isExcluded("AGENTS.md")).isTrue();
        assertThat(WorkspaceArtifactScanner.isExcluded("KNOWLEDGE.md")).isTrue();
        assertThat(WorkspaceArtifactScanner.isExcluded("tools.json")).isTrue();

        // 用户可见文件不排除
        assertThat(WorkspaceArtifactScanner.isExcluded("list_files.py")).isFalse();
        assertThat(WorkspaceArtifactScanner.isExcluded("scripts/dir_tree.py")).isFalse();
        assertThat(WorkspaceArtifactScanner.isExcluded("report.md")).isFalse();
    }

    @Test
    void 能识别新增与修改的文件且排除内部目录(@TempDir Path root) throws Exception {
        Path ws = root.resolve("thread-1");
        Files.createDirectories(ws.resolve("scripts"));
        Files.createDirectories(ws.resolve("memory"));
        Files.createDirectories(ws.resolve("uploads"));

        // 既有文件（不在本次变更内）
        Path existing = ws.resolve("old.py");
        Files.writeString(existing, "print(1)\n");

        WorkspaceArtifactScanner scanner = scanner(root);
        Map<String, Long> before = scanner.snapshot("thread-1");
        assertThat(before).containsKey("old.py");
        assertThat(before).doesNotContainKey("memory/2026.md");

        // 本次 run：新增文件 + 修改文件 + 仅内部目录变动
        Files.writeString(ws.resolve("scripts/new.py"), "print(2)\n");
        Files.writeString(existing, "print(1)\nprint(2)\n");
        Files.writeString(ws.resolve("memory/2026-10-11.md"), "记忆内容");
        Files.writeString(ws.resolve("uploads/up.txt"), "上传内容");

        var changed = scanner.changedFiles("thread-1", before).stream()
                // Windows 上是反斜杠，统一成正斜杠再断言
                .map(p -> ws.relativize(p).toString().replace('\\', '/'))
                .sorted()
                .toList();

        assertThat(changed).containsExactly("old.py", "scripts/new.py");
        assertThat(changed).noneMatch(p -> p.contains("memory/") || p.contains("uploads/"));
    }
}
