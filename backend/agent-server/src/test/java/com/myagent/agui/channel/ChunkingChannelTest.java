package com.myagent.agui.channel;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 出站分片包在 Channel 上：三条出站路径（对话回复 / 主动推送 / send_im_message）都过这里，
 * 所以只要这一层拆对了，就不会有哪条路径还在被截断。
 */
class ChunkingChannelTest {

    /** 记录官方渠道最终收到的消息（真实实现是逐条 sendOne）。 */
    private static final class RecordingChannel implements Channel {

        private final List<List<Msg>> delivered = new ArrayList<>();
        private final List<Msg> receipts = new ArrayList<>();

        @Override
        public String channelId() {
            return "1";
        }

        @Override
        public ChannelConfig config() {
            return null;
        }

        @Override
        public Mono<Msg> dispatch(InboundMessage message) {
            return Mono.empty();
        }

        @Override
        public Flux<AgentEvent> dispatchStream(InboundMessage message) {
            return Flux.empty();
        }

        @Override
        public void deliver(OutboundAddress address, List<Msg> messages) {
            delivered.add(new ArrayList<>(messages));
        }

        @Override
        public Mono<String> deliverWithReceipt(OutboundAddress address, Msg message, String type) {
            receipts.add(message);
            return Mono.just("ok");
        }
    }

    private static OutboundAddress address() {
        return OutboundAddress.fromMap(Map.of("to", "1:USER:staff-1"));
    }

    private static Msg longMsg(String text) {
        return Msg.builder().role(MsgRole.ASSISTANT).textContent(text).build();
    }

    @Test
    void 超长消息会被拆成多条_交给官方渠道逐条发() {
        RecordingChannel official = new RecordingChannel();
        ChunkingChannel channel = new ChunkingChannel(official, new ImTextSplitter(100));

        channel.deliver(address(), List.of(longMsg("Y".repeat(250))));

        assertThat(official.delivered).hasSize(1);
        List<Msg> sent = official.delivered.get(0);
        assertThat(sent).hasSizeGreaterThan(1);
        assertThat(sent.get(0).getTextContent()).startsWith("（1/");
        // 每条都是独立的 Msg —— 官方实现是逐个 sendOne 的
        for (Msg msg : sent) {
            assertThat(msg.getTextContent().length()).isLessThanOrEqualTo(100);
        }
    }

    @Test
    void 短消息不动_仍是一条() {
        RecordingChannel official = new RecordingChannel();
        ChunkingChannel channel = new ChunkingChannel(official, new ImTextSplitter(100));

        channel.deliver(address(), List.of(longMsg("没问题")));

        assertThat(official.delivered).hasSize(1);
        assertThat(official.delivered.get(0)).hasSize(1);
        assertThat(official.delivered.get(0).get(0).getTextContent()).isEqualTo("没问题");
    }

    @Test
    void 带回执的发送_第一条走回执其余正常发() {
        RecordingChannel official = new RecordingChannel();
        ChunkingChannel channel = new ChunkingChannel(official, new ImTextSplitter(100));

        String receipt = channel.deliverWithReceipt(address(), longMsg("Z".repeat(250)), "read").block();

        assertThat(receipt).isEqualTo("ok");
        assertThat(official.receipts).hasSize(1);
        assertThat(official.delivered).hasSize(1);
        assertThat(official.delivered.get(0)).hasSizeGreaterThanOrEqualTo(1);
    }
}
