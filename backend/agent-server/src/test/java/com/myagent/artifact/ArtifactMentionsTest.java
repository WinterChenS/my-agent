package com.myagent.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import com.myagent.agui.AguiProperties;
import com.myagent.artifact.service.ArtifactMentions;
import com.myagent.artifact.service.WorkspaceArtifactScanner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 回复里提到的工作区文件 → 补登记到本轮产物。
 *
 * <p>守的是实测场景：第一次生成报告后，用户说"再发一份给我"，模型只回一句
 * "文件在 outputs/report.html"（不再调用 deliverArtifact），文件也没变化，
 * 于是那一轮气泡下面没有产物。
 */
class ArtifactMentionsTest {

    private ArtifactMentions mentions(Path root) {
        WorkspaceArtifactScanner scanner = new WorkspaceArtifactScanner(
                new AguiProperties(root.toString(), 120_000, 0.9, 32_000, 30_000, 1_500, 128_000));
        return new ArtifactMentions(scanner);
    }

    @Test
    @DisplayName("回复里提到的报告文件能被找到（相对路径与纯文件名都行）")
    void resolvesMentionedDeliverable(@TempDir Path root) throws Exception {
        Path ws = root.resolve("7");
        Files.createDirectories(ws.resolve("outputs"));
        Files.writeString(ws.resolve("outputs/report.html"), "<html>ok</html>");

        ArtifactMentions m = mentions(root);
        assertThat(m.resolve(7L, "文件已经放在 outputs/report.html 了", Set.of()))
                .extracting(p -> p.getFileName().toString())
                .containsExactly("report.html");
        assertThat(m.resolve(7L, "再给你一次：report.html", Set.of()))
                .extracting(p -> p.getFileName().toString())
                .containsExactly("report.html");
    }

    @Test
    @DisplayName("中文文件名也要能识别（实测报告就叫 本周工作小结.html）")
    void resolvesChineseFileName(@TempDir Path root) throws Exception {
        Path ws = root.resolve("7");
        Files.createDirectories(ws.resolve("outputs"));
        Files.writeString(ws.resolve("outputs/本周工作小结.html"), "<html>");

        assertThat(mentions(root).resolve(7L, "文件是 `本周工作小结.html`，双击就能打开", Set.of()))
                .extracting(p -> p.getFileName().toString())
                .containsExactly("本周工作小结.html");
    }

    @Test
    @DisplayName("非交付类型（脚本/数据/日志）不会被补登记")
    void ignoresNonDeliverable(@TempDir Path root) throws Exception {
        Path ws = root.resolve("7");
        Files.createDirectories(ws);
        Files.writeString(ws.resolve("make_report.py"), "print(1)");
        Files.writeString(ws.resolve("data.json"), "{}");
        Files.writeString(ws.resolve("run.log"), "log");

        assertThat(mentions(root).resolve(7L, "脚本 make_report.py、数据 data.json、日志 run.log", Set.of()))
                .isEmpty();
    }

    @Test
    @DisplayName("已经登记过的文件不重复补登记；不存在的文件忽略")
    void skipsRegisteredAndMissing(@TempDir Path root) throws Exception {
        Path ws = root.resolve("7");
        Files.createDirectories(ws);
        Files.writeString(ws.resolve("report.html"), "<html>");

        ArtifactMentions m = mentions(root);
        assertThat(m.resolve(7L, "report.html", Set.of("report.html"))).isEmpty();
        assertThat(m.resolve(7L, "missing.pdf", Set.of())).isEmpty();
    }
}
