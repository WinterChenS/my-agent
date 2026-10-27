package com.myagent.agui;

import com.myagent.modelconfig.probe.ModelRetryTester;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 模型重试自检实现（放在 {@code agui} 包，AgentScope 类型不出这个包 —— 门禁 G2）。
 *
 * <p>两种模式：
 * <ul>
 *   <li>{@link #liveTest}：用真实模型发一次最小请求，统计过程中发生了几次可重试失败；</li>
 *   <li>{@link #simulate}：本地起假端点，第 1 次流出半句后断开、第 2 次正常返回，
 *       用来证明"重试链路本身真的会重试"（不依赖上游、不消耗额度、也不会真的失败）。</li>
 * </ul>
 */
@Component
public class AguiModelRetryTester implements ModelRetryTester {

    private static final Logger log = LoggerFactory.getLogger(AguiModelRetryTester.class);
    private static final Duration TIMEOUT = Duration.ofMinutes(2);
    private static final int PROBE_MAX_TOKENS = 16;
    private static final String PARTIAL_TEXT = "第一次尝试（断流）";
    private static final String FULL_TEXT = "第二次尝试（重试成功）";

    private final ModelFactory modelFactory;

    public AguiModelRetryTester(ModelFactory modelFactory) {
        this.modelFactory = modelFactory;
    }

    @Override
    public PolicyView policy() {
        return new PolicyView(
                modelFactory.maxRetries(),
                modelFactory.initialBackoffMs(),
                modelFactory.maxBackoffMs(),
                modelFactory.backoffMultiplier(),
                modelFactory.timeoutMinutes(),
                modelFactory.retryOnUnknownStatus(),
                modelFactory.retryOnStreamFailure(),
                describePolicy());
    }

    private String describePolicy() {
        StringBuilder text = new StringBuilder();
        text.append("失败后最多重试 ")
                .append(modelFactory.maxRetries())
                .append(" 次（首次之外）；退避 ")
                .append(modelFactory.initialBackoffMs())
                .append("ms 起，每次 x")
                .append(modelFactory.backoffMultiplier())
                .append("，上限 ")
                .append(modelFactory.maxBackoffMs())
                .append("ms；单次调用超时 ")
                .append(modelFactory.timeoutMinutes())
                .append(" 分钟。");
        text.append("可重试：429/5xx、超时、网络中断");
        if (modelFactory.retryOnUnknownStatus()) {
            text.append("、拿不到 HTTP 状态码的失败");
        }
        if (modelFactory.retryOnStreamFailure()) {
            text.append("、流式返回中途断连");
        }
        text.append("；参数错误(400)不重试。");
        return text.toString();
    }

    @Override
    public TestResult liveTest(
            String providerType,
            String baseUrl,
            String apiKey,
            String modelId,
            Map<String, Object> params) {
        long start = System.nanoTime();
        try (RetryProbeCounter counter = RetryProbeCounter.start()) {
            ChatModelBase model = modelFactory.create(providerType, baseUrl, apiKey, modelId, params, false);
            List<ChatResponse> responses = call(model, false);
            int failures = counter.failures();
            return new TestResult(
                    true, failures, retriesOf(true, failures), elapsed(start), textOf(responses), null);
        } catch (Exception ex) {
            log.warn("模型重试测试（真实调用）失败: {}", ex.toString());
            return new TestResult(false, 0, 0, elapsed(start), null, rootMessage(ex));
        }
    }

    @Override
    public TestResult simulate() {
        long start = System.nanoTime();
        try (RetryProbeCounter counter = RetryProbeCounter.start()) {
            // 第 1 次：先吐半句再抛"流中断"；第 2 次：正常返回。
            // 直接喂给官方的 ModelUtils.applyTimeoutAndRetry —— 验证的就是真实生效的那段重试链路
            // （executionConfig 里是我们的重试次数/退避/谓词，谓词命中会累加到 counter）。
            //
            // 为什么不用"本地假 HTTP 端点"：那样得手写 chunked 编码并在半截掐断连接，
            // 客户端和假服务端很容易互相等（实测卡到 120s 超时）。而重试逻辑本身跟传输层无关，
            // 直接驱动同一个包装函数既准确又不抖。
            java.util.concurrent.atomic.AtomicInteger attempt = new java.util.concurrent.atomic.AtomicInteger();
            reactor.core.publisher.Flux<ChatResponse> upstream = reactor.core.publisher.Flux.defer(() -> {
                if (attempt.incrementAndGet() == 1) {
                    return reactor.core.publisher.Flux.just(response(PARTIAL_TEXT))
                            .concatWith(reactor.core.publisher.Flux.error(new IllegalStateException(
                                    "OpenAI API error in streaming response: retry-test:"
                                            + " connection lost mid-reply (unexpected EOF)")));
                }
                return reactor.core.publisher.Flux.just(response(FULL_TEXT));
            });

            GenerateOptions options = GenerateOptions.builder()
                    .executionConfig(modelFactory.executionConfig())
                    .build();
            List<ChatResponse> responses = io.agentscope.core.model.ModelUtils
                    .applyTimeoutAndRetry(upstream, options, options, "retry-test", "retry-simulate")
                    .collectList()
                    .block(TIMEOUT);

            int failures = counter.failures();
            String text = textOf(responses);
            boolean ok = text != null && text.contains(FULL_TEXT);
            return new TestResult(
                    ok,
                    failures,
                    retriesOf(ok, failures),
                    elapsed(start),
                    text,
                    ok ? null : "重试后没有拿到预期内容（实际：" + text + "）");
        } catch (Exception ex) {
            log.warn("模型重试模拟自检失败: {}", ex.toString());
            return new TestResult(false, 0, 0, elapsed(start), null, rootMessage(ex));
        }
    }

    /** 构造一条只带文本的响应（自检用，不需要真实 usage）。 */
    private static ChatResponse response(String text) {
        return ChatResponse.builder()
                .id("retry-test")
                .content(List.of(TextBlock.builder().text(text).build()))
                .build();
    }

    /** 成功时每次失败都换来了重试；失败时最后一次没有重试。 */
    private static int retriesOf(boolean ok, int failures) {
        return Math.max(0, ok ? failures : failures - 1);
    }

    private List<ChatResponse> call(ChatModelBase model, boolean stream) {
        Msg msg = Msg.builderForRole(MsgRole.USER).textContent("重试自检").build();
        GenerateOptions options = GenerateOptions.builder()
                .maxTokens(PROBE_MAX_TOKENS)
                .stream(stream)
                .executionConfig(modelFactory.executionConfig())
                .build();
        return model.stream(List.of(msg), List.of(), options).collectList().block(TIMEOUT);
    }

    /** 把所有文本片段拼起来，便于判断重试拿到的到底是哪一次的内容。 */
    private static String textOf(List<ChatResponse> responses) {
        if (responses == null || responses.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (ChatResponse response : responses) {
            if (response.getContent() == null) {
                continue;
            }
            response.getContent().forEach(block -> {
                if (block instanceof TextBlock textBlock && textBlock.getText() != null) {
                    text.append(textBlock.getText());
                }
            });
        }
        return text.toString();
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
