package com.myagent.audit.service;

import com.myagent.agentconfig.entity.Agent;
import com.myagent.agentconfig.mapper.AgentMapper;
import com.myagent.audit.entity.TokenUsage;
import com.myagent.audit.mapper.TokenUsageMapper;
import com.myagent.modelconfig.entity.LlmModel;
import com.myagent.modelconfig.mapper.LlmModelMapper;
import com.myagent.modelconfig.mapper.LlmProviderMapper;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Token 采集：把 AG-UI 的 {@code CUSTOM / token_usage} 事件落库。
 *
 * <p>事件结构（实测）：
 * <pre>
 * {"delta":{"inputTokens":11,"outputTokens":5,"cachedTokens":0,"totalTokens":16,"time":0.295},
 *  "cumulative":{...},
 *  "modelCall":{"replyId":"..."}}
 * </pre>
 * 按技术方案第 9 节：每次模型调用写一行，{@code request_id} = 该次 /agui/run 的 runId，
 * 因此同一 run 的多轮工具循环会有多行、request_id 相同。
 *
 * <p>本类位于 {@code audit} 包（门禁 G2 允许），只读取普通 Map，不依赖 AgentScope 类型。
 */
@Component
public class TokenUsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(TokenUsageRecorder.class);

    private final TokenUsageMapper tokenUsageMapper;
    private final AgentMapper agentMapper;
    private final LlmModelMapper llmModelMapper;
    private final LlmProviderMapper llmProviderMapper;

    public TokenUsageRecorder(
            TokenUsageMapper tokenUsageMapper,
            AgentMapper agentMapper,
            LlmModelMapper llmModelMapper,
            LlmProviderMapper llmProviderMapper) {
        this.tokenUsageMapper = tokenUsageMapper;
        this.agentMapper = agentMapper;
        this.llmModelMapper = llmModelMapper;
        this.llmProviderMapper = llmProviderMapper;
    }

    /**
     * 一次 run 内固定不变的归属信息（避免每个模型调用都查库）。
     *
     * @param modelId 模型主键
     * @param providerType 供应商类型
     */
    public record TokenContext(Long modelId, String providerType) {}

    /** 解析该 Agent 使用的模型与供应商类型；解析不到时返回全 null 的上下文。 */
    public TokenContext resolveContext(Long agentDbId) {
        try {
            Agent agent = agentDbId == null ? null : agentMapper.selectById(agentDbId);
            LlmModel model = agent == null || agent.getModelId() == null
                    ? null
                    : llmModelMapper.selectById(agent.getModelId());
            if (model == null) {
                return new TokenContext(null, null);
            }
            var provider = llmProviderMapper.selectById(model.getProviderId());
            return new TokenContext(model.getId(), provider == null ? null : provider.getProviderType());
        } catch (Exception ex) {
            log.warn("解析 token 归属失败 agentId={}: {}", agentDbId, ex.toString());
            return new TokenContext(null, null);
        }
    }

    /**
     * 记录一次模型调用的 token 用量。
     *
     * <p>usage 缺失（无 delta 或全为 0）时不写行、不报错（Task 8.1 门禁）。
     */
    public void record(
            Long userId,
            Long sessionId,
            Long agentDbId,
            TokenContext context,
            String requestId,
            Map<String, Object> delta) {
        if (delta == null) {
            return;
        }
        Integer prompt = asInt(delta.get("inputTokens"));
        Integer completion = asInt(delta.get("outputTokens"));
        Integer total = asInt(delta.get("totalTokens"));
        if (prompt == null && completion == null && total == null) {
            return;
        }
        int p = prompt == null ? 0 : prompt;
        int c = completion == null ? 0 : completion;
        int t = total == null ? p + c : total;
        if (p == 0 && c == 0 && t == 0) {
            return;
        }

        try {
            TokenUsage usage = new TokenUsage();
            usage.setUserId(userId);
            usage.setSessionId(sessionId);
            usage.setAgentId(agentDbId);
            usage.setModelId(context == null ? null : context.modelId());
            usage.setProviderType(context == null ? null : context.providerType());
            // 缓存命中 token：不同厂商/事件字段名不一，按可能的名字依次取（取不到就是 0）
        usage.setCacheTokens(firstInt(delta, "cacheTokens", "cachedTokens", "cacheReadTokens", "cachedInputTokens"));
        usage.setPromptTokens(p);
            usage.setCompletionTokens(c);
            usage.setTotalTokens(t);
            usage.setRequestId(requestId);
            tokenUsageMapper.insert(usage);
        } catch (Exception ex) {
            // 审计失败不能影响对话主链路
            log.error("写入 token_usage 失败 sessionId={} requestId={}", sessionId, requestId, ex);
        }
    }

    /** 依次尝试多个字段名，返回第一个非空值（都没有则 0）。 */
    private static int firstInt(Map<String, Object> delta, String... keys) {
        for (String key : keys) {
            Integer value = asInt(delta.get(key));
            if (value != null) {
                return value;
            }
        }
        return 0;
    }

    private static Integer asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.valueOf(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
