package com.myagent.agui.connector;

import com.myagent.connectorconfig.ConnectorRecord;
import com.myagent.connectorconfig.ConnectorTypeDescriptor;
import java.util.List;
import java.util.Map;

/**
 * 一种连接器的**工具提供者**（SPI）。实现放在本包，因此可以使用 AgentScope 类型（门禁 G8）。
 *
 * <p>加一种连接器：在 {@code ConnectorTypeRegistry} 加一条描述 + 在本包加一个实现，框架层不动。
 *
 * <p>注意：工具是**无状态单例 + 运行时按用户解析**的——
 * 凭据不烘进工具实例，调用时才按 {@code RuntimeContext.getUserId()} 找"这个用户自己的连接器"。
 * 这样多人共用一个 Agent 时，每个人只会碰到自己的邮箱。
 */
public interface ConnectorProvider {

    /** 支持的连接器类型（与 {@code connector.type} 一致，大写）。 */
    String type();

    /**
     * 产出该类型的工具集。
     *
     * @param agentId 当前 Agent 的数据库 id（工具用它限定"只认绑定到本 Agent 的连接器"）
     * @param descriptor 类型描述（能力集）
     */
    List<io.agentscope.core.tool.AgentTool> tools(long agentId, ConnectorTypeDescriptor descriptor);

    /** 连通性探测（保存/测试连接时用）。 */
    com.myagent.connectorconfig.dto.ConnectorTestResult probe(Map<String, String> config);

    /**
     * 带调用者身份的探测（默认退回无身份版本）。
     *
     * <p>钉钉文档这类连接器的身份是 operatorId，需要按平台用户自动映射；没有它就只能用占位身份，
     * 「测试连接」会误报"没权限"。
     */
    default com.myagent.connectorconfig.dto.ConnectorTestResult probe(
            Map<String, String> config, Long userId) {
        return probe(config);
    }

    /**
     * 入站轮询：返回自上次游标以来的新邮件（只读）**以及要保存的新游标**。
     *
     * <p>为什么要连游标一起返回：第一次跑（没有游标）只能"打基线"而不拉历史邮件，
     * 这时没有邮件但有游标要落库——只返回 {@code List} 的话，游标永远建立不起来，
     * 于是每轮都被当成"第一次"，历史未读会被反复推送（用户实测投诉过）。
     *
     * <p>默认空实现：不支持入站的类型不用管。
     */
    default PollResult poll(ConnectorRecord record, Map<String, String> config) {
        return PollResult.empty();
    }

    /**
     * 一次轮询结果。
     *
     * @param messages 新邮件（第一次打基线时为空）
     * @param uidValidity 服务端当前游标版本（邮箱被重建会变）
     * @param lastUid 处理完后要保存的游标
     */
    record PollResult(List<InboundMessage> messages, Long uidValidity, long lastUid, boolean baselined) {
        public static PollResult empty() {
            return new PollResult(List.of(), null, 0L, false);
        }

        public boolean hasCursor() {
            return uidValidity != null;
        }
    }

    /**
     * 回复一封入站邮件（AUTOREPLY 模式用；实现里要带 {@code Auto-Submitted: auto-replied} 头）。
     *
     * @return 发送结果说明（写到入站事件里，便于排查）
     */
    default String reply(InboundMessage message, Map<String, String> config, String body) {
        throw new UnsupportedOperationException("该连接器类型不支持自动回复");
    }

    /**
     * 一封入站邮件（已解析到"能给 Agent 看"的程度）。
     *
     * @param externalId Message-ID（去重键）
     * @param threadKey 线程标识（References 根 或 In-Reply-To），用于"同线程只自动回一次"
     * @param autoSubmitted 邮件是否自称自动回复（RFC 3834），是则不能自动回
     * @param to 收件人（判断是不是发给自己的）
     */
    record InboundMessage(
            long uid,
            Long uidValidity,
            String externalId,
            String threadKey,
            String fromAddr,
            String fromName,
            String subject,
            String text,
            java.time.OffsetDateTime receivedAt,
            boolean autoSubmitted,
            String bulkPrecedence,
            List<String> recipients) {}
}
