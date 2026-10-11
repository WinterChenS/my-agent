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

    /** run 开始：返回工作区快照（供结束时对比）。 */
    public Map<String, Long> beginRun(String threadId) {
        return scanner.snapshot(threadId);
    }

    /**
     * run 结束：登记本次新增/变更的文件。
     *
     * @return 实际登记的产物数量
     */
    public int endRun(Long userId, Long sessionId, String threadId, Map<String, Long> before) {
        if (threadId == null || sessionId == null || userId == null) {
            return 0;
        }
        Map<String, Long> baseline = before == null ? new HashMap<>() : before;
        List<Path> changed = scanner.changedFiles(threadId, baseline);
        int registered = 0;
        for (Path path : changed) {
            try {
                byte[] bytes = Files.readAllBytes(path);
                String fileName = path.getFileName().toString();
                String mime = guessMime(fileName);
                artifactService.store(
                        userId, sessionId, ArtifactService.ROLE_ARTIFACT, fileName, mime, bytes, false);
                registered += 1;
            } catch (Exception ex) {
                // 单个文件失败不影响其它文件，也不影响 run
                log.warn("登记产物失败 path={}: {}", path, ex.toString());
            }
        }
        if (registered > 0) {
            log.info("已登记 {} 个产物 sessionId={} threadId={}", registered, sessionId, threadId);
        }
        return registered;
    }

    /** 极简 MIME 推断（存储路由只需要区分文本/非文本，正文类型由下载方决定）。 */
    static String guessMime(String fileName) {
        String name = fileName.toLowerCase(java.util.Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1);
        return switch (ext) {
            case "md", "markdown" -> "text/markdown";
            case "txt", "log" -> "text/plain";
            case "json", "jsonl" -> "application/json";
            case "csv" -> "text/csv";
            case "xml" -> "application/xml";
            case "yaml", "yml" -> "application/yaml";
            case "html", "htm" -> "text/html";
            case "css" -> "text/css";
            case "js", "mjs" -> "text/javascript";
            case "ts", "tsx" -> "text/plain";
            case "py", "java", "sh", "sql", "properties", "ini", "conf", "toml" -> "text/plain";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "pdf" -> "application/pdf";
            case "zip" -> "application/zip";
            default -> "application/octet-stream";
        };
    }
}
