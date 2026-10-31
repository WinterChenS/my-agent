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
    private final com.myagent.artifact.PlatformArtifactDeliveryTarget deliveryTarget;
    private final ArtifactMentions mentions;

    public ArtifactCaptureService(
            WorkspaceArtifactScanner scanner,
            ArtifactService artifactService,
            com.myagent.artifact.PlatformArtifactDeliveryTarget deliveryTarget,
            ArtifactMentions mentions) {
        this.scanner = scanner;
        this.artifactService = artifactService;
        this.deliveryTarget = deliveryTarget;
        this.mentions = mentions;
    }

    /**
     * 用户说"再发一份/同样的再来一份"时，模型常常只回一句"文件是 xxx.html"，既没重新生成
     * 也没调用 {@code deliverArtifact} —— 于是这一轮气泡下什么都没有（用户实测反馈）。
     *
     * <p>兜底：本轮没有任何产物、且回复看起来是在"再给一份"时，把**本会话上一轮**的产物
     * 复制一份挂到本轮（内容相同，runId 记本轮），让用户在本轮气泡下能再次拿到它。
     */
    private int reDeliverForRepeatRequest(
            Long userId, Long sessionId, String runId, String assistantText) {
        if (assistantText == null || assistantText.isBlank()) {
            return 0;
        }
        java.util.regex.Pattern repeat = java.util.regex.Pattern.compile(
                "再发|又发|再来一份|再给一份|同样的|又一份|复制一份|重新发|再传一份|副本");
        if (!repeat.matcher(assistantText).find()) {
            return 0;
        }
        java.util.List<com.myagent.artifact.dto.ArtifactResponse> recent;
        try {
            recent = artifactService.list(userId, sessionId);
        } catch (Exception ex) {
            return 0;
        }
        for (com.myagent.artifact.dto.ArtifactResponse artifact : recent) {
            if (!ArtifactService.ROLE_ARTIFACT.equals(artifact.role())) {
                continue;
            }
            if (runId != null && runId.equals(artifact.runId())) {
                continue; // 本轮的，跳过
            }
            String fileName = artifact.fileName();
            String stem = fileName == null || fileName.lastIndexOf('.') <= 0
                    ? (fileName == null ? "" : fileName)
                    : fileName.substring(0, fileName.lastIndexOf('.'));
            boolean mentioned = assistantText.contains(fileName == null ? "" : fileName)
                    || (!stem.isBlank() && assistantText.contains(stem));
            if (!mentioned) {
                return 0; // 只认最近一条，且必须被提到
            }
            try {
                byte[] bytes = artifactService.contentBytes(userId, artifact.id());
                if (bytes == null || bytes.length == 0) {
                    return 0;
                }
                artifactService.store(userId, sessionId, ArtifactService.ROLE_ARTIFACT, fileName,
                        artifact.mime(), bytes, false, runId);
                log.info("用户要求再给一份，已把上一轮的 {} 重新挂到本轮 runId={}", fileName, runId);
                return 1;
            } catch (Exception ex) {
                log.warn("重新挂载上一轮产物失败 {}: {}", fileName, ex.toString());
                return 0;
            }
        }
        return 0;
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
        return endRun(userId, sessionId, threadId, before, runId, null);
    }

    /**
     * run 结束：登记本轮产物。
     *
     * @param assistantText 本轮最终回复（兜底用：模型常说"文件在 outputs/xxx.html"却没再调
     *                      {@code deliverArtifact}，那种情况下本轮气泡下会缺产物）
     */
    public int endRun(
            Long userId, Long sessionId, String threadId, Map<String, Long> before, String runId,
            String assistantText) {
        if (threadId == null || sessionId == null || userId == null) {
            return 0;
        }
        // 这一轮 Agent 已经主动交付过成果 → 意图明确，不再把工作区里所有新文件都登记成产物
        // （脚本、中间数据、日志都是"生成过程"，用户要的只是最终那几个文件）。
        java.util.Set<String> delivered = deliveryTarget.deliveredOf(runId, threadId);
        if (!delivered.isEmpty()) {
            deliveryTarget.clearDelivered(runId, threadId);
            log.info("本轮已主动交付 {} 个文件（{}），跳过自动登记以免把过程文件当产物",
                    delivered.size(), String.join(", ", delivered));
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
