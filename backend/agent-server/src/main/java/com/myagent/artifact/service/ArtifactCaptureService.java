package com.myagent.artifact.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 产物自动登记（D22「自动全收」）。
 *
 * <p>由 {@code /agui/run} 在每次 run 开始/结束时调用：开始时快照工作区，结束时把
 * 新增或变更的文件登记为 {@code artifact(role=ARTIFACT)}。
 *
 * <p>登记失败**不得影响 run 结果**：逐个文件 try/catch，失败只记日志。
 */
@Service
public class ArtifactCaptureService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactCaptureService.class);

    private final WorkspaceArtifactScanner scanner;
    private final ArtifactService artifactService;

    public ArtifactCaptureService(WorkspaceArtifactScanner scanner, ArtifactService artifactService) {
        this.scanner = scanner;
        this.artifactService = artifactService;
    }

    /** run 开始：返回属主工作区快照（供结束时对比）。owner 用 userId。 */
    public Map<String, Long> beginRun(String owner) {
        return scanner.snapshot(owner);
    }

    /**
     * run 结束：登记本次新增/变更的文件。
     *
     * @param runId 本轮 run id —— 写进产物的 `run_id`，前端才能把产物挂到“产出它的那一轮”气泡下方
     * @return 实际登记的产物数量
     */
    public int endRun(
            Long userId, Long sessionId, String threadId, Map<String, Long> before, String runId) {
        if (threadId == null || sessionId == null || userId == null) {
            return 0;
        }
        Map<String, Long> baseline = before == null ? new HashMap<>() : before;
        // 工作区按属主（userId）划分，与该 session/thread 无关；本次 run 的变更归到当前会话
        List<Path> changed = scanner.changedFiles(String.valueOf(userId), baseline);
        int registered = 0;
        for (Path path : changed) {
            try {
                byte[] bytes = Files.readAllBytes(path);
                String fileName = path.getFileName().toString();
                String mime = ArtifactMime.guess(fileName);
                artifactService.store(
                        userId, sessionId, ArtifactService.ROLE_ARTIFACT, fileName, mime, bytes, false, runId);
                registered += 1;
            } catch (Exception ex) {
                // 单个文件失败不影响其它文件，也不影响 run
                log.warn("登记产物失败 path={}: {}", path, ex.toString());
            }
        }
        if (registered > 0) {
            log.info("已登记 {} 个产物 sessionId={} owner={}", registered, sessionId, userId);
        }
        return registered;
    }
}
