package com.myagent.agui;

import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.ImageInputContent;
import io.agentscope.core.agui.model.InputContent;
import io.agentscope.core.agui.model.MessageContent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.model.TextInputContent;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 多模态守卫（tier1-upgrades ①）。
 *
 * <p>模型没开「多模态」时把图片内容块**剥离**掉，并在文本末尾追加一句说明——
 * 否则厂商会直接返回 400（模型不支持图片输入），用户只会看到一段莫名报错。
 * 模型开了多模态则原样放行（官方适配器会把 ImagePart 转成 ImageBlock 交给模型）。
 */
@Component
public class MultimodalGuard {

    private static final Logger log = LoggerFactory.getLogger(MultimodalGuard.class);
    private static final int MAX_IMAGES_PER_RUN = 5;

    /** 守卫结果：可能被改写的输入 + 被剥离的图片数。 */
    public record Result(RunAgentInput input, int strippedImages) {}

    public Result apply(RunAgentInput input, boolean multimodal) {
        if (input == null || !input.hasMessages() || multimodal) {
            // 开了多模态（或没有消息）：原样放行。官方的 ImagePart → ImageBlock 由适配器负责。
            return new Result(input, 0);
        }
        int stripped = 0;
        List<AguiMessage> messages = new ArrayList<>();
        for (AguiMessage message : input.getMessages()) {
            AguiMessage rewritten = stripImages(message);
            if (rewritten != message) {
                stripped += countImages(message);
            }
            messages.add(rewritten);
        }
        if (stripped == 0) {
            return new Result(input, 0);
        }
        log.info("模型未开启多模态，已剥离 {} 张图片 threadId={}", stripped, input.getThreadId());
        RunAgentInput rebuilt = RunAgentInput.builder()
                .threadId(input.getThreadId())
                .runId(input.getRunId())
                .messages(messages)
                .tools(input.getTools())
                .context(input.getContext())
                .state(input.getState())
                .forwardedProps(input.getForwardedProps())
                .resume(input.getResume())
                .build();
        return new Result(rebuilt, stripped);
    }

    /** 把一条消息里的图片块去掉（保留文本，并追加说明）；没有图片时返回原对象。 */
    private AguiMessage stripImages(AguiMessage message) {
        MessageContent content = message.getContent();
        if (!(content instanceof MessageContent.Blocks blocks) || blocks.parts() == null) {
            return message;
        }
        List<InputContent> kept = new ArrayList<>();
        int removed = 0;
        StringBuilder text = new StringBuilder();
        for (InputContent part : blocks.parts()) {
            if (part instanceof ImageInputContent) {
                removed++;
                continue;
            }
            kept.add(part);
            if (part instanceof TextInputContent textPart) {
                text.append(textPart.text());
            }
        }
        if (removed == 0) {
            return message;
        }
        text.append("\n\n（提示：当前模型未开启多模态，本轮 ").append(removed).append(" 张图片未发送给模型。）");
        List<InputContent> replaced = new ArrayList<>();
        replaced.add(new TextInputContent(text.toString()));
        for (InputContent part : kept) {
            if (!(part instanceof TextInputContent)) {
                replaced.add(part);
            }
        }
        return AguiMessage.blocksMessage(
                message.getId(), message.getRole(), replaced, message.getToolCalls(), message.getToolCallId());
    }

    /** 统计消息里的图片数量（用于单轮上限校验）。 */
    public int countImages(AguiMessage message) {
        MessageContent content = message.getContent();
        if (!(content instanceof MessageContent.Blocks blocks) || blocks.parts() == null) {
            return 0;
        }
        int count = 0;
        for (InputContent part : blocks.parts()) {
            if (part instanceof ImageInputContent) {
                count++;
            }
        }
        return count;
    }

    /** 单张图片大小上限（按 base64 解码后的字节数）。 */
    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;

    /** 允许的图片格式。 */
    private static final java.util.Set<String> ALLOWED_MIME =
            java.util.Set.of("image/png", "image/jpeg", "image/jpg", "image/webp", "image/gif");

    /**
     * 校验本轮图片是否合规（tier1-upgrades ① 的「图片限制」）。
     *
     * <p>三道：单轮张数 ≤5、单张 ≤5MB、格式仅 png/jpeg/webp/gif。返回人类可读的问题列表（空=合规）。
     * 校验失败时整轮拒绝（文本消息请单独发送）——错误提示必须说明是哪张、错在哪。
     */
    public List<String> validateImages(RunAgentInput input) {
        List<String> problems = new ArrayList<>();
        if (input == null || !input.hasMessages()) {
            return problems;
        }
        int index = 0;
        int total = 0;
        for (AguiMessage message : input.getMessages()) {
            MessageContent content = message.getContent();
            if (!(content instanceof MessageContent.Blocks blocks) || blocks.parts() == null) {
                continue;
            }
            for (InputContent part : blocks.parts()) {
                if (!(part instanceof ImageInputContent image)) {
                    continue;
                }
                total++;
                index++;
                // ImageInputContent / InputContentDataSource 都是 record，用 record 访问器
                io.agentscope.core.agui.model.InputContentSource src = image.source();
                String mime = null;
                String value = null;
                if (src instanceof io.agentscope.core.agui.model.InputContentDataSource data) {
                    mime = data.mimeType();
                    value = data.value();
                } else if (src != null) {
                    mime = String.valueOf(src);
                }
                if (mime == null || !ALLOWED_MIME.contains(mime.toLowerCase())) {
                    problems.add("第 " + index + " 张图片格式不支持（仅支持 png/jpeg/webp/gif）：" + mime);
                    continue;
                }
                if (value != null) {
                    long bytes = (long) (value.length() / 4.0 * 3);
                    if (bytes > MAX_IMAGE_BYTES) {
                        problems.add("第 " + index + " 张图片过大（"
                                + (bytes / 1024 / 1024) + "MB，上限 5MB）");
                    }
                }
            }
        }
        if (total > MAX_IMAGES_PER_RUN) {
            problems.add("单轮最多发送 " + MAX_IMAGES_PER_RUN + " 张图片（当前 " + total + " 张）");
        }
        return problems;
    }

    /** 单轮图片数量上限（超出返回超出张数，0 表示合规）。 */
    public int overLimit(RunAgentInput input) {
        int total = 0;
        if (input != null && input.hasMessages()) {
            for (AguiMessage message : input.getMessages()) {
                total += countImages(message);
            }
        }
        return Math.max(0, total - MAX_IMAGES_PER_RUN);
    }
}
