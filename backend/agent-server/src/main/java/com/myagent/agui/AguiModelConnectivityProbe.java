package com.myagent.agui;

import com.myagent.modelconfig.probe.ModelConnectivityProbe;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 用 AgentScope 官方模型扩展做一次最小补全，验证供应商可达且密钥有效。
 *
 * <p>放在 {@code agui} 包内以满足门禁 G2；任何失败都转换为 {@code ok=false} 的结果，
 * 不让异常冒泡成 500（Task 2.1 门禁）。
 */
@Component
public class AguiModelConnectivityProbe implements ModelConnectivityProbe {

    private static final Logger log = LoggerFactory.getLogger(AguiModelConnectivityProbe.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int PROBE_MAX_TOKENS = 8;
    private static final String PROBE_PROMPT = "hi";

    private final ModelFactory modelFactory;

    public AguiModelConnectivityProbe(ModelFactory modelFactory) {
        this.modelFactory = modelFactory;
    }

    @Override
    public ProbeResult probe(
            String providerType,
            String baseUrl,
            String apiKey,
            String modelId,
            Map<String, Object> params) {
        long start = System.nanoTime();
        try {
            if (!StringUtils.hasText(apiKey)) {
                return new ProbeResult(false, elapsed(start), "该供应商未配置 API Key");
            }
            ChatModelBase model = modelFactory.create(providerType, baseUrl, apiKey, modelId, params, false);
            Msg msg = Msg.builderForRole(MsgRole.USER).textContent(PROBE_PROMPT).build();
            GenerateOptions options = GenerateOptions.builder()
                    .maxTokens(PROBE_MAX_TOKENS)
                    .stream(false)
                    .build();

            List<ChatResponse> responses = model
                    .stream(List.of(msg), List.of(), options)
                    .collectList()
                    .block(TIMEOUT);

            long latency = elapsed(start);
            if (responses == null || responses.isEmpty()) {
                return new ProbeResult(false, latency, "模型未返回任何响应");
            }
            return new ProbeResult(true, latency, null);
        } catch (Exception ex) {
            log.warn("模型连通性测试失败 providerType={} model={} : {}", providerType, modelId, ex.toString());
            return new ProbeResult(false, elapsed(start), rootMessage(ex));
        }
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
