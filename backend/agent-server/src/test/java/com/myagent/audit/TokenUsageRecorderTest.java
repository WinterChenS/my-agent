package com.myagent.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.myagent.audit.mapper.TokenUsageMapper;
import com.myagent.audit.service.TokenUsageRecorder;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.myagent.audit.entity.TokenUsage;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** Token 采集：usage 缺失时不得写行、不得报错（Task 8.1 门禁）。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TokenUsageRecorderTest {

    @Autowired
    private TokenUsageRecorder recorder;

    @Autowired
    private TokenUsageMapper tokenUsageMapper;

    private long countFor(String requestId) {
        return tokenUsageMapper.selectCount(
                Wrappers.<TokenUsage>lambdaQuery().eq(TokenUsage::getRequestId, requestId));
    }

    @Test
    void usage缺失时不写行也不报错() {
        String requestId = "rec-missing-" + System.nanoTime();
        var context = new TokenUsageRecorder.TokenContext(1L, "OPENAI_COMPATIBLE");

        recorder.record(1L, 1L, 1L, context, requestId, null);
        recorder.record(1L, 1L, 1L, context, requestId, Map.of());
        recorder.record(1L, 1L, 1L, context, requestId, Map.of("inputTokens", 0, "outputTokens", 0, "totalTokens", 0));

        assertThat(countFor(requestId)).as("无有效 usage 不应写行").isZero();
    }

    @Test
    void 有效usage写入一行_且requestId与run一致() {
        String requestId = "rec-ok-" + System.nanoTime();
        var context = new TokenUsageRecorder.TokenContext(7L, "OPENAI_COMPATIBLE");

        recorder.record(1L, 2L, 3L, context, requestId,
                Map.of("inputTokens", 11, "outputTokens", 5, "totalTokens", 16));

        var rows = tokenUsageMapper.selectList(
                Wrappers.<TokenUsage>lambdaQuery().eq(TokenUsage::getRequestId, requestId));
        assertThat(rows).hasSize(1);
        TokenUsage row = rows.get(0);
        assertThat(row.getPromptTokens()).isEqualTo(11);
        assertThat(row.getCompletionTokens()).isEqualTo(5);
        assertThat(row.getTotalTokens()).isEqualTo(16);
        assertThat(row.getModelId()).isEqualTo(7L);
        assertThat(row.getProviderType()).isEqualTo("OPENAI_COMPATIBLE");
    }

    @Test
    void 同一run的多次模型调用写多行且requestId相同() {
        String requestId = "rec-multi-" + System.nanoTime();
        var context = new TokenUsageRecorder.TokenContext(1L, "OPENAI_COMPATIBLE");

        recorder.record(1L, 1L, 1L, context, requestId, Map.of("inputTokens", 10, "outputTokens", 2, "totalTokens", 12));
        recorder.record(1L, 1L, 1L, context, requestId, Map.of("inputTokens", 20, "outputTokens", 4, "totalTokens", 24));

        assertThat(countFor(requestId)).isEqualTo(2);
    }

    @Test
    void 缺失totalTokens时按输入输出求和() {
        String requestId = "rec-sum-" + System.nanoTime();
        var context = new TokenUsageRecorder.TokenContext(1L, "OPENAI_COMPATIBLE");

        recorder.record(1L, 1L, 1L, context, requestId, Map.of("inputTokens", 3, "outputTokens", 4));

        TokenUsage row = tokenUsageMapper.selectOne(
                Wrappers.<TokenUsage>lambdaQuery().eq(TokenUsage::getRequestId, requestId));
        assertThat(row.getTotalTokens()).isEqualTo(7);
    }
}
