package com.myagent.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.myagent.agui.RunningRunRegistry;
import com.myagent.artifact.service.ArtifactService;
import com.myagent.chat.entity.ChatSession;
import com.myagent.chat.mapper.ChatSessionMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryRequest;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryResult;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * 官方「Agent 主动交付产物」接收端的单测。
 *
 * <p>要点：交付要落到本会话的 artifact 表，并记下**当前这一轮**的 runId
 * （前端据此把产物展示在对应气泡下方）；失败时返回 fail 而不是抛异常。
 */
class PlatformArtifactDeliveryTargetTest {

    private final ArtifactService artifactService = mock(ArtifactService.class);
    private final ChatSessionMapper chatSessionMapper = mock(ChatSessionMapper.class);
    private final RunningRunRegistry runningRuns = mock(RunningRunRegistry.class);
    private final PlatformArtifactDeliveryTarget target =
            new PlatformArtifactDeliveryTarget(artifactService, chatSessionMapper, runningRuns);

    private static RuntimeContext context(String threadId) {
        return RuntimeContext.builder().sessionId(threadId).userId("7").build();
    }

    private static ArtifactDeliveryRequest request(String fileName, String content) {
        return new ArtifactDeliveryRequest(
                "reports/" + fileName,
                content.getBytes(StandardCharsets.UTF_8),
                fileName,
                "季度报告",
                false);
    }

    @Test
    void deliversIntoSessionAndRecordsCurrentRun() {
        ChatSession session = new ChatSession();
        session.setId(42L);
        session.setUserId(7L);
        when(chatSessionMapper.selectOne(any())).thenReturn(session);
        when(runningRuns.snapshot("thread-1"))
                .thenReturn(new RunningRunRegistry.RunSnapshot(true, "", "run-abc", false));

        ArtifactDeliveryResult result = target.deliver(context("thread-1"), request("report.md", "# 报告"));

        assertThat(result.successful()).isTrue();
        // 关键：runId 要跟着写进去 —— 这是"产物挂到对应气泡下方"的依据
        verify(artifactService)
                .store(
                        eq(7L),
                        eq(42L),
                        eq(ArtifactService.ROLE_ARTIFACT),
                        eq("report.md"),
                        eq("text/markdown"),
                        any(byte[].class),
                        eq(false),
                        eq("run-abc"));
    }

    @Test
    void failsGracefullyWhenSessionMissing() {
        when(chatSessionMapper.selectOne(any())).thenReturn(null);

        ArtifactDeliveryResult result = target.deliver(context("thread-x"), request("a.txt", "x"));

        assertThat(result.successful()).isFalse();
        assertThat(result.error()).contains("会话不存在");
    }

    @Test
    void failsGracefullyWhenThreadIdMissing() {
        ArtifactDeliveryResult result = target.deliver(context(null), request("a.txt", "x"));

        assertThat(result.successful()).isFalse();
        assertThat(result.error()).contains("threadId");
    }

    @Test
    void failsOnEmptyContent() {
        ChatSession session = new ChatSession();
        session.setId(42L);
        session.setUserId(7L);
        when(chatSessionMapper.selectOne(any())).thenReturn(session);

        ArtifactDeliveryResult result =
                target.deliver(context("t"), new ArtifactDeliveryRequest("a.txt", new byte[0], "a.txt", null, false));

        assertThat(result.successful()).isFalse();
        assertThat(result.error()).contains("交付内容为空");
    }

    @Test
    void deliveryStillWorksWhenRunIdUnknown() {
        ChatSession session = new ChatSession();
        session.setId(42L);
        session.setUserId(7L);
        when(chatSessionMapper.selectOne(any())).thenReturn(session);
        when(runningRuns.snapshot(anyString())).thenReturn(null);

        ArtifactDeliveryResult result = target.deliver(context("t"), request("a.txt", "hello"));

        assertThat(result.successful()).isTrue();
        verify(artifactService)
                .store(anyLong(), anyLong(), anyString(), anyString(), anyString(), any(byte[].class), anyBoolean(), eq(null));
    }

    @Test
    void guessesMimeByExtension() {
        assertThat(PlatformArtifactDeliveryTarget.guessMime("a.md")).isEqualTo("text/markdown");
        assertThat(PlatformArtifactDeliveryTarget.guessMime("a.csv")).isEqualTo("text/csv");
        assertThat(PlatformArtifactDeliveryTarget.guessMime("a.png")).isEqualTo("image/png");
        assertThat(PlatformArtifactDeliveryTarget.guessMime("a.unknown")).isEqualTo("application/octet-stream");
    }
}
