package com.myagent.agui.channel;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.myagent.channelconfig.entity.ChannelPeer;
import com.myagent.channelconfig.service.ChannelPushPort;
import com.myagent.chat.entity.ChatSession;
import com.myagent.chat.mapper.ChatSessionMapper;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 让 Agent 能**主动**给 IM 会话发消息（不只是被动回复）。
 *
 * <p>典型用法：用户说「跑完告诉我」「十分钟后提醒我」，Agent 在长任务结束/定时触发时自己推一条。
 *
 * <p>只发正文与产物文件；思考过程和工具调用不会出现在 IM 里（需求 3）。
 */
@Component
public class SendImMessageTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(SendImMessageTool.class);

    private final ChannelSessionBridge sessionBridge;
    private final ChatSessionMapper sessionMapper;
    /**
     * 用 {@link ObjectProvider} 延迟解析：ChannelPushPort 的实现（ChannelRuntime）依赖
     * PlatformChannelGateway → AgentFactory → McpToolkitFactory → 本工具，
     * 构造期直接注入会形成循环依赖。
     */
    private final ObjectProvider<ChannelPushPort> pushPort;

    public SendImMessageTool(
            ChannelSessionBridge sessionBridge,
            ChatSessionMapper sessionMapper,
            ObjectProvider<ChannelPushPort> pushPort) {
        this.sessionBridge = sessionBridge;
        this.sessionMapper = sessionMapper;
        this.pushPort = pushPort;
    }

    @Override
    public String getName() {
        return "send_im_message";
    }

    @Override
    public String getDescription() {
        return "主动给当前 IM 会话（钉钉）发一条消息，可附带文件或图片（图片会以图片消息发出）。"
                + "用户说「发张图给我」「搞定后通知我」「提醒我」这类需求时用它。"
                + "参数 text 必填（正文）；paths 可选：**工作区相对路径**列表（如 screen.png、output/结果.csv），"
                + "刚生成还没登记的文件用它最省事；artifactIds 可选（已有产物 id）。"
                + "图片与文件是独立消息，正文单独一条。只有在 IM 会话里能用；Web 会话调用会返回说明。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("text", Map.of("type", "string", "description", "要发送的正文"));
        properties.put("artifactIds", Map.of(
                "type", "array",
                "items", Map.of("type", "integer"),
                "description", "要随消息发送的产物 id 列表（可选）"));
        properties.put("paths", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "工作区相对路径列表（可选），例如 screen.png。刚生成的文件用它，"
                        + "图片会以图片消息发出"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("text"));
        return schema;
    }

    @Override
    public boolean isReadOnly() {
        // 会对外发消息，属于写操作（REQUIRE_APPROVAL 模式下会被拦下审批）
        return false;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> send(param))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(ex -> Mono.just(error("发送失败：" + ex.getMessage())));
    }

    private ToolResultBlock send(ToolCallParam param) {
        String text = asString(param.getInput().get("text"));
        if (text == null || text.isBlank()) {
            return error("text 不能为空");
        }
        String threadId = param.getRuntimeContext() == null
                ? null
                : param.getRuntimeContext().getSessionId();
        if (threadId == null || threadId.isBlank()) {
            return error("当前没有会话上下文，无法发送 IM 消息");
        }
        ChatSession session = sessionMapper.selectOne(Wrappers.<ChatSession>lambdaQuery()
                .eq(ChatSession::getThreadId, threadId)
                .last("limit 1"));
        if (session == null) {
            return error("找不到当前会话");
        }
        ChannelPeer peer = sessionBridge.peerOfSession(session.getId());
        if (peer == null) {
            return error("当前会话不是 IM 会话（Web 会话没有可推送的渠道），这条消息没有发出去");
        }
        List<Long> artifactIds = asLongList(param.getInput().get("artifactIds"));
        List<String> paths = asStringList(param.getInput().get("paths"));
        ChannelPushPort port = pushPort.getIfAvailable();
        if (port == null) {
            return error("渠道运行时未就绪，这条消息没有发出去");
        }
        int sent = port.push(peer.getChannelAppId(), peer.getPeerKey(), text, artifactIds);
        if (!paths.isEmpty()) {
            sent += port.pushFiles(peer.getChannelAppId(), peer.getPeerKey(), null, paths);
        }
        log.info("Agent 主动推送 IM 消息 sessionId={} appId={} sent={}",
                session.getId(), peer.getChannelAppId(), sent);
        return ToolResultBlock.builder()
                .output(TextBlock.builder().text("已发送 " + sent + " 条消息到 IM 会话。").build())
                .build();
    }

    private static ToolResultBlock error(String message) {
        return ToolResultBlock.builder()
                .output(TextBlock.builder().text(message).build())
                .build();
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static List<String> asStringList(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item == null) {
                    continue;
                }
                String text = String.valueOf(item).trim();
                if (!text.isEmpty()) {
                    result.add(text);
                }
            }
        }
        return result;
    }

    private static List<Long> asLongList(Object value) {
        List<Long> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item == null) {
                    continue;
                }
                try {
                    result.add(Long.valueOf(String.valueOf(item)));
                } catch (NumberFormatException ignored) {
                    // 非数字忽略
                }
            }
        }
        return result;
    }
}
