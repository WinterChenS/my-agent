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
     * 入站轮询：返回自上次游标以来的新邮件（只读）。
     *
     * <p>默认空实现：不支持入站的类型不用管。
     */
    default List<InboundMessage> poll(ConnectorRecord record, Map<String, String> config) {
        return List.of();
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
