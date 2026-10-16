package com.myagent.artifact;

import com.myagent.agui.RunningRunRegistry;
import com.myagent.artifact.entity.Artifact;
import com.myagent.artifact.service.ArtifactService;
import com.myagent.chat.entity.ChatSession;
import com.myagent.chat.mapper.ChatSessionMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryRequest;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryResult;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryTarget;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 「Agent 主动交付产物」的官方接收端（{@link ArtifactDeliveryTarget}）。
 *
 * <p>官方机制（AgentScope 2.0.3 即支持，不需要自研）：给 {@code HarnessAgent} 配上这个 target 后，
 * 框架会向 Agent 注册内置工具 {@code deliverArtifact}；Agent 认为某个文件是**要交付的成果**时调用它，
 * 框架再把请求交给这里的 {@link #deliver}（{@code RuntimeContext} 带 sessionId=threadId、userId）。
 *
 * <p>与"扫工作区"（{@link com.myagent.artifact.service.WorkspaceArtifactScanner}）的区别：
 * 那是"凡新增文件都收"，而这里是 Agent **有意**交付的成果 —— 正是官方文档所说的
 * "an Artifact records a deliberately delivered result"。两条路都会落到同一张 {@code artifact} 表。
 *
 * <p>落库时记下当前 run 的 id：前端据此把产物展示在**对应那一轮的气泡下方**（消息侧 agui_meta.runId 与之对应）。
 */
@Component
public class PlatformArtifactDeliveryTarget implements ArtifactDeliveryTarget {

    private static final Logger log = LoggerFactory.getLogger(PlatformArtifactDeliveryTarget.class);

    private final ArtifactService artifactService;
    private final ChatSessionMapper chatSessionMapper;
    private final RunningRunRegistry runningRuns;

    public PlatformArtifactDeliveryTarget(
            ArtifactService artifactService,
            ChatSessionMapper chatSessionMapper,
            RunningRunRegistry runningRuns) {
        this.artifactService = artifactService;
        this.chatSessionMapper = chatSessionMapper;
        this.runningRuns = runningRuns;
    }

    @Override
    public ArtifactDeliveryResult deliver(RuntimeContext context, ArtifactDeliveryRequest request) {
        try {
            String threadId = context == null ? null : context.getSessionId();
            if (threadId == null) {
                return ArtifactDeliveryResult.fail("缺少会话标识（threadId），无法登记交付的产物");
            }
            ChatSession session = chatSessionMapper.selectOne(
                    Wrappers.<ChatSession>lambdaQuery().eq(ChatSession::getThreadId, threadId));
            if (session == null) {
                return ArtifactDeliveryResult.fail("会话不存在，无法登记交付的产物");
            }
            byte[] bytes = request.content();
            if (bytes == null || bytes.length == 0) {
                return ArtifactDeliveryResult.fail("交付内容为空：" + request.filePath());
            }
            String fileName = request.fileName();
            if (fileName == null || fileName.isBlank()) {
                fileName = basename(request.filePath());
            }
            // 当前这一轮的 run id：前端用它把产物挂到对应气泡下方
            RunningRunRegistry.RunSnapshot snapshot = runningRuns.snapshot(threadId);
            String runId = snapshot == null ? null : snapshot.runId();

            Artifact saved = artifactService.store(
                    session.getUserId(),
                    session.getId(),
                    ArtifactService.ROLE_ARTIFACT,
                    fileName,
                    guessMime(fileName),
                    bytes,
                    // 文件本来就在 Agent 工作区里（工具是从工作区读的），不必再物化一份
                    false,
                    runId);

            log.info(
                    "Agent 交付产物已登记 sessionId={} fileName={} size={} runId={}",
                    session.getId(),
                    fileName,
                    bytes.length,
                    runId);
            String description = request.description();
            return ArtifactDeliveryResult.success(
                    description == null || description.isBlank()
                            ? "已交付：" + fileName
                            : description);
        } catch (Exception ex) {
            // 交付失败不能让整个 run 崩掉：如实返回失败原因
            log.warn("Agent 交付产物失败 filePath={}", request == null ? null : request.filePath(), ex);
            return ArtifactDeliveryResult.fail(String.valueOf(ex.getMessage()));
        }
    }

    private static String basename(String path) {
        if (path == null || path.isBlank()) {
            return "artifact";
        }
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    /**
     * 由扩展名猜 MIME。
     *
     * <p>只影响「文本入库还是走 MinIO」与预览判定（{@code ArtifactService.isTextual} 对空 mime 也会按扩展名兜底），
     * 所以这里只需覆盖常见类型。
     */
    static String guessMime(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1) : "";
        return switch (ext) {
            case "txt", "log" -> "text/plain";
            case "md", "markdown" -> "text/markdown";
            case "json" -> "application/json";
            case "csv" -> "text/csv";
            case "html", "htm" -> "text/html";
            case "xml" -> "application/xml";
            case "yml", "yaml" -> "application/yaml";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "svg" -> "image/svg+xml";
            case "pdf" -> "application/pdf";
            default -> "application/octet-stream";
        };
    }

    /** 文本预览时用（避免 IDE 报未使用）：交付结果里的正文编码。 */
    static String decode(byte[] bytes) {
        return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
    }
}
