package com.myagent.artifact;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.myagent.agui.AguiProperties;
import com.myagent.artifact.service.ArtifactCaptureService;
import com.myagent.artifact.service.ArtifactService;
import com.myagent.artifact.service.WorkspaceArtifactScanner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * run 结束时的产物登记：必须带上**本轮** runId —— 前端按 {@code artifact.runId == 消息.runId}
 * 把产物挂到"产出它的那一轮"气泡下方；此前扫描路径传 null，于是这类产物只在右侧栏出现。
 */
class ArtifactCaptureServiceTest {

    private final ArtifactService artifactService = mock(ArtifactService.class);

    @Test
    void 扫描登记的产物带上本轮runId(@TempDir Path root) throws Exception {
        WorkspaceArtifactScanner scanner = new WorkspaceArtifactScanner(
                new AguiProperties(root.toString(), 120_000, 0.9, 32_000, 30_000, 1_500, 128_000));
        ArtifactCaptureService capture = new ArtifactCaptureService(scanner, artifactService);

        Path ws = root.resolve("7");
        Files.createDirectories(ws);
        Map<String, Long> before = capture.beginRun("7"); // run 开始：快照
        Files.writeString(ws.resolve("shot.png"), "png-bytes"); // run 期间新增的文件

        capture.endRun(7L, 42L, "thread-1", before, "run-abc");

        verify(artifactService)
                .store(
                        eq(7L),
                        eq(42L),
                        eq(ArtifactService.ROLE_ARTIFACT),
                        eq("shot.png"),
                        any(),
                        any(),
                        anyBoolean(),
                        eq("run-abc"));
    }
}
