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
