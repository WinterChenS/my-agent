package com.myagent.artifact;

import com.myagent.agui.RunningRunRegistry;
import com.myagent.artifact.entity.Artifact;
import com.myagent.artifact.service.ArtifactMime;
import com.myagent.artifact.service.ArtifactService;
import com.myagent.chat.entity.ChatSession;
import com.myagent.chat.mapper.ChatSessionMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryRequest;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryResult;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryTarget;
import java.nio.charset.StandardCharsets;
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

    /**
     * 本轮 run 主动交付过的文件名（runId 可能为空时用 threadId 兜底）。
     *
     * <p>用途：{@code ArtifactCaptureService} 在 run 结束时判断"这一轮到底要不要把工作区里
     * 所有新文件都登记成产物" —— 只要 Agent 已经主动交付过成果，就说明**意图明确**，
     * 中途生成的脚本/数据/日志不该再进产物区（用户反馈：产物里全是过程文件）。
     */
    private final java.util.Map<String, java.util.Set<String>> deliveredByRun =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static String deliveryKey(String runId, String threadId) {
        return runId != null && !runId.isBlank() ? runId : "thread:" + threadId;
    }

    /** 本轮（runId 或 threadId）主动交付过的文件名。 */
    public java.util.Set<String> deliveredOf(String runId, String threadId) {
        return deliveredByRun.getOrDefault(deliveryKey(runId, threadId), java.util.Set.of());
    }

    /** run 结束收尾用：清掉记录，避免长期占用内存。 */
    public void clearDelivered(String runId, String threadId) {
        deliveredByRun.remove(deliveryKey(runId, threadId));
    }

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

            deliveredByRun
                    .computeIfAbsent(deliveryKey(runId, threadId), key -> java.util.concurrent.ConcurrentHashMap.newKeySet())
                    .add(fileName);
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
     * 由扩展名猜 MIME —— 表在 {@link ArtifactMime}（**唯一**一份，扫描路径也用它）。
     *
     * <p>保留这个静态方法是因为既有单测直接调它（{@code PlatformArtifactDeliveryTargetTest}）。
     */
    static String guessMime(String fileName) {
        return ArtifactMime.guess(fileName);
    }

    /** 文本预览时用（避免 IDE 报未使用）：交付结果里的正文编码。 */
    static String decode(byte[] bytes) {
        return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
    }
}
