package com.myagent.schedule.tool;

import com.myagent.channelconfig.service.ChannelTargetResolver;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 列出可用的 IM 推送目标（渠道 + 已绑定接收人）。
 *
 * <p>为什么要有它：模型在调 {@code schedule_task} 时得写出**准确的**渠道名与接收人；
 * 让它先查这份清单，比让它猜名字可靠得多（猜错会把定时结果发给别人）。
 *
 * <p>只读工具，不涉及权限拦截。
 */
@Component
public class ListImTargetsTool implements AgentTool {

    private final ChannelTargetResolver resolver;

    public ListImTargetsTool(ChannelTargetResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public String getName() {
        return "list_im_targets";
    }

    @Override
    public String getDescription() {
        return "列出平台已配置的 IM 渠道和已绑定的接收人（钉钉等）。"
                + "用户要把定时任务/消息发到 IM、但没说清发给谁时，先调它拿到准确名称，"
                + "再把渠道名填进 schedule_task 的 channel、接收人填进 recipient。"
                + "只有列出「单聊/群」的才是可推送目标；没有接收人时要提示用户先给机器人发一条消息，"
                + "或在「IM 渠道 → 会话」里手工绑定。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>());
        return schema;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> ToolResultBlock.builder()
                        .output(TextBlock.builder().text(resolver.describeTargets()).build())
                        .build())
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 供单测断言用。 */
    public List<ChannelTargetResolver.TargetChannel> channels() {
        return resolver.channels();
    }
}
