package com.myagent.agui.channel;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 出站分片：把超长消息拆成多条再交给官方渠道发送。
 *
 * <p>为什么包在 Channel 上而不是在各个调用点各拆一次：出站路径有三条
 * （普通对话回复走官方 {@code deliver}、主动推送 {@code ChannelRuntime.push}、Agent 工具
 * {@code send_im_message}），只改一处必然漏。包在最外层，一条路径都不用记得改。
 *
 * <p>官方钉钉实现里 {@code deliver(address, List&lt;Msg&gt;)} 是**逐条**调用
 * {@code sendOne} 的（字节码确认：{@code lambda$send$0} 每个 Msg 一次），所以把一条拆成多条
 * 就等于真的分多条发出。
 */
public class ChunkingChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(ChunkingChannel.class);

    private final Channel delegate;
    private final ImTextSplitter splitter;

    public ChunkingChannel(Channel delegate, ImTextSplitter splitter) {
        this.delegate = delegate;
        this.splitter = splitter;
    }

    @Override
    public String channelId() {
        return delegate.channelId();
    }

    @Override
    public ChannelConfig config() {
        return delegate.config();
    }

    @Override
    public void init(Gateway gateway) {
        delegate.init(gateway);
    }

    @Override
    public void start() {
        delegate.start();
    }

    @Override
    public void stop() {
        delegate.stop();
    }

    @Override
    public Mono<Msg> dispatch(InboundMessage message) {
        return delegate.dispatch(message);
    }

    @Override
    public Flux<AgentEvent> dispatchStream(InboundMessage message) {
        return delegate.dispatchStream(message);
    }

    @Override
    public void deliver(OutboundAddress address, List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            delegate.deliver(address, messages);
            return;
        }
        List<Msg> expanded = new ArrayList<>();
        for (Msg message : messages) {
            expanded.addAll(splitter.splitMsg(message));
        }
        if (expanded.size() > messages.size()) {
            log.info("出站消息超长已分片: 原始 {} 条 -> 实际 {} 条", messages.size(), expanded.size());
        }
        delegate.deliver(address, expanded);
    }

    @Override
    public Mono<String> deliverWithReceipt(OutboundAddress address, Msg message, String receiptType) {
        List<Msg> parts = splitter.splitMsg(message);
        if (parts.size() <= 1) {
            return delegate.deliverWithReceipt(address, message, receiptType);
        }
        // 回执只对第一条有意义；其余按普通消息发
        Mono<String> receipt = delegate.deliverWithReceipt(address, parts.get(0), receiptType);
        delegate.deliver(address, new ArrayList<>(parts.subList(1, parts.size())));
        return receipt;
    }

    @Override
    public boolean applyRoutingConfig(ChannelConfig config) {
        return delegate.applyRoutingConfig(config);
    }

    /** 被包装的原始渠道（排障/管理页用）。 */
    public Channel delegate() {
        return delegate;
    }
}
