package com.myagent.agui;

import com.myagent.common.BizException;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.anthropic.AnthropicChatModel;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 把数据库里的 provider/model 配置翻译为 AgentScope 官方模型实例。
 *
 * <p>门禁 G2：这是唯一集中使用 {@code io.agentscope} 模型 API 的地方（连同包内的 AG-UI/审批代码）。
 * 门禁 G8：只使用官方扩展（openai / dashscope / anthropic），不自定义协议。
 */
@Component
public class ModelFactory {

    /**
     * 模型调用重试配置（用官方 {@link ExecutionConfig}，不自研重试）。
     *
     * <p>官方 {@code ExecutionConfig.MODEL_DEFAULTS} 就是 maxAttempts=3 / 初始 2s / 指数退避 / 上限 30s，
     * 这里把它**显式传进 GenerateOptions**，并允许用 {@code myagent.model.retry.*} 覆盖——
     * 之前不传时框架只在部分路径套用默认值（实测日志里出现过 attempt 1/2）。
     */
    private final int retryMaxAttempts;
    private final long retryInitialBackoffMs;
    private final long retryMaxBackoffMs;
    private final double retryMultiplier;
    private final long retryTimeoutMinutes;
    /** 是否把"没有 HTTP 状态码"的模型失败也当作可重试（默认 true）。 */
    private final boolean retryOnUnknownStatus;

    /** 最终使用的重试判定：官方谓词 + （可选）无状态码的模型错误。 */
    private final java.util.function.Predicate<Throwable> retryOn;

    public ModelFactory(
            @org.springframework.beans.factory.annotation.Value("${myagent.model.retry.max-retries:3}")
                    int retryMaxRetries,
            @org.springframework.beans.factory.annotation.Value("${myagent.model.retry.initial-backoff-ms:2000}")
                    long retryInitialBackoffMs,
            @org.springframework.beans.factory.annotation.Value("${myagent.model.retry.max-backoff-ms:30000}")
                    long retryMaxBackoffMs,
            @org.springframework.beans.factory.annotation.Value("${myagent.model.retry.multiplier:2.0}")
                    double retryMultiplier,
            @org.springframework.beans.factory.annotation.Value("${myagent.model.retry.timeout-minutes:5}")
                    long retryTimeoutMinutes,
            @org.springframework.beans.factory.annotation.Value("${myagent.model.retry.on-unknown-status:true}")
                    boolean retryOnUnknownStatus) {
        // 语义：max-retries 是"重试次数"（不含首次），框架的 maxAttempts = 重试 + 1
        this.retryMaxAttempts = Math.max(0, retryMaxRetries) + 1;
        this.retryInitialBackoffMs = Math.max(0, retryInitialBackoffMs);
        this.retryMaxBackoffMs = Math.max(this.retryInitialBackoffMs, retryMaxBackoffMs);
        this.retryMultiplier = retryMultiplier <= 0 ? 2.0 : retryMultiplier;
        this.retryTimeoutMinutes = Math.max(1, retryTimeoutMinutes);
        this.retryOnUnknownStatus = retryOnUnknownStatus;
        this.retryOn = retryOnUnknownStatus
                ? error -> io.agentscope.core.model.ExecutionConfig.RETRYABLE_ERRORS.test(error)
                        || isStatuslessModelError(error)
                : io.agentscope.core.model.ExecutionConfig.RETRYABLE_ERRORS;
    }

    /**
     * 供 AgentFactory 复用同一份重试/超时配置。
     *
     * <p>为什么必须复用：AgentFactory 会自己 new 一个 {@code GenerateOptions}（放 temperature/maxTokens），
     * 若它不带 executionConfig，框架 merge 时可能退回到更小的默认值——实测出现过"配 3 次只重试 2 次"。
     */
    public io.agentscope.core.model.ExecutionConfig executionConfig() {
        return io.agentscope.core.model.ExecutionConfig.builder()
                .timeout(java.time.Duration.ofMinutes(retryTimeoutMinutes))
                .maxAttempts(retryMaxAttempts)
                .initialBackoff(java.time.Duration.ofMillis(retryInitialBackoffMs))
                .maxBackoff(java.time.Duration.ofMillis(retryMaxBackoffMs))
                .backoffMultiplier(retryMultiplier)
                .retryOn(retryOn)
                .build();
    }

    /** 无 HTTP 状态码的模型调用失败（连不上 / 流被关闭 / 解析中断…）。 */
    static boolean isStatuslessModelError(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 6) {
            if (current instanceof io.agentscope.core.model.ModelHttpException http
                    && http.getStatusCode() == null) {
                return true;
            }
            if (current instanceof java.net.ConnectException
                    || current instanceof java.net.SocketException
                    || current instanceof java.io.EOFException) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }

    /** 走 OpenAI 兼容协议的类型。 */
    private static final String TYPE_OPENAI = "OPENAI";
    private static final String TYPE_OPENAI_COMPATIBLE = "OPENAI_COMPATIBLE";
    private static final String TYPE_GEMINI = "GEMINI";
    private static final String TYPE_OLLAMA = "OLLAMA";
    private static final String TYPE_DASHSCOPE = "DASHSCOPE";
    private static final String TYPE_ANTHROPIC = "ANTHROPIC";

    public ChatModelBase create(
            String providerType,
            String baseUrl,
            String apiKey,
            String modelId,
            Map<String, Object> params,
            boolean stream) {
        GenerateOptions options = buildOptions(params);
        String type = providerType == null ? "" : providerType.trim().toUpperCase();
        return switch (type) {
            case TYPE_OPENAI, TYPE_OPENAI_COMPATIBLE, TYPE_GEMINI, TYPE_OLLAMA ->
                    openAi(baseUrl, apiKey, modelId, options, stream);
            case TYPE_DASHSCOPE -> dashScope(baseUrl, apiKey, modelId, options, stream);
            case TYPE_ANTHROPIC -> anthropic(baseUrl, apiKey, modelId, options, stream);
            default -> throw BizException.badRequest("不支持的 provider_type: " + providerType);
        };
    }

    private ChatModelBase openAi(
            String baseUrl, String apiKey, String modelId, GenerateOptions options, boolean stream) {
        OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelId)
                .stream(stream)
                .generateOptions(options);
        if (StringUtils.hasText(baseUrl)) {
            builder.baseUrl(baseUrl);
        }
        return builder.build();
    }

    private ChatModelBase dashScope(
            String baseUrl, String apiKey, String modelId, GenerateOptions options, boolean stream) {
        DashScopeChatModel.Builder builder = DashScopeChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelId)
                .stream(stream)
                .defaultOptions(options);
        if (StringUtils.hasText(baseUrl)) {
            builder.baseUrl(baseUrl);
        }
        return builder.build();
    }

    private ChatModelBase anthropic(
            String baseUrl, String apiKey, String modelId, GenerateOptions options, boolean stream) {
        AnthropicChatModel.Builder builder = AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelId)
                .stream(stream)
                .defaultOptions(options);
        if (StringUtils.hasText(baseUrl)) {
            builder.baseUrl(baseUrl);
        }
        return builder.build();
    }

    /**
     * 采样参数：temperature / maxTokens / topP / topK，以及**思考强度**。
     *
     * <p>{@code reasoningEffort} 是 2026-10-26 实测补上的：网关上的 {@code deepseek-v4.1-flash}
     * 默认**不返回** {@code reasoning_content}（{@code reasoning_tokens=0}），必须显式传
     * {@code reasoning_effort: "high"} 才会思考。不传就永远看不到思考过程——
     * 看起来像"前端不显示"，实际是模型压根没想。
     *
     * <p>{@code additionalBodyParams} 是逃生口：厂商自定义参数（如 {@code thinking}、
     * {@code chat_template_kwargs}）不用改代码就能透传。
     */
    private GenerateOptions buildOptions(Map<String, Object> params) {
        GenerateOptions.Builder builder = GenerateOptions.builder();
        // 官方重试/超时配置（默认 3 次，指数退避），可用 myagent.model.retry.* 调整
        builder.executionConfig(io.agentscope.core.model.ExecutionConfig.builder()
                .timeout(java.time.Duration.ofMinutes(retryTimeoutMinutes))
                .maxAttempts(retryMaxAttempts)
                .initialBackoff(java.time.Duration.ofMillis(retryInitialBackoffMs))
                .maxBackoff(java.time.Duration.ofMillis(retryMaxBackoffMs))
                .backoffMultiplier(retryMultiplier)
                // 官方 RETRYABLE_ERRORS 覆盖 429 / 5xx / 超时 / IOException；
                // 但**连不上、流被对端关闭**这类没有 HTTP 状态码的失败它判为"不可重试"
                // （isRetryableHttpStatus 对 statusCode==null 返回 false），
                // 而这恰恰是最常见的抖动。这里用官方 retryOn 谓词把"无状态码的模型调用失败"补进来。
                .retryOn(retryOn)
                .build());
        if (params != null) {
            Object temperature = params.get("temperature");
            if (temperature instanceof Number number) {
                builder.temperature(number.doubleValue());
            }
            Object maxTokens = params.get("maxTokens");
            if (maxTokens instanceof Number number) {
                builder.maxTokens(number.intValue());
            }
            // Phase 15：Top-N（核采样 top_p / top_k）也要生效
            Object topP = params.get("topP");
            if (topP instanceof Number number) {
                builder.topP(number.doubleValue());
            }
            Object topK = params.get("topK");
            if (topK instanceof Number number) {
                builder.topK(number.intValue());
            }
            // 思考强度：两种写法都认（camel / snake），值走官方 reasoningEffort（low / medium / high）
            Object effort = params.containsKey("reasoningEffort")
                    ? params.get("reasoningEffort")
                    : params.get("reasoning_effort");
            if (effort instanceof String text && !text.isBlank()) {
                builder.reasoningEffort(text.trim());
            }
            Object thinkingBudget = params.get("thinkingBudget");
            if (thinkingBudget instanceof Number number) {
                builder.thinkingBudget(number.intValue());
            }
            // 厂商自定义参数直通（body 里原样带上去）
            Object additional = params.get("additionalBodyParams");
            if (additional instanceof Map<?, ?> map) {
                map.forEach((key, value) -> {
                    if (key != null && value != null) {
                        builder.additionalBodyParam(String.valueOf(key), value);
                    }
                });
            }
        }
        return builder.build();
    }
}
