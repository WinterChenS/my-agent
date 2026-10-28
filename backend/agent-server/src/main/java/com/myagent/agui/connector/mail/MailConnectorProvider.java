package com.myagent.agui.connector.mail;

import com.myagent.agui.connector.ConnectorProvider;
import com.myagent.connectorconfig.ConnectorRecord;
import com.myagent.connectorconfig.ConnectorService;
import com.myagent.connectorconfig.ConnectorTypeDescriptor;
import com.myagent.connectorconfig.dto.ConnectorTestResult;
import io.agentscope.core.tool.AgentTool;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 邮件连接器（MAIL）：产出 8 个工具 + 连通性探测 + 入站拉取。
 *
 * <p>这是「加一种连接器」时唯一要写的实现类：类型描述在
 * {@code ConnectorTypeRegistry}，工具/协议在本类与 {@link MailTools}/{@link MailClient}。
 */
public class MailConnectorProvider implements ConnectorProvider {

    private final ConnectorService connectorService;
    private final Path workspaceRoot;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final long maxAttachmentBytes;
    private final long maxAttachmentsTotalBytes;
    private final int maxBodyChars;
    private final int maxBatch;

    public MailConnectorProvider(
            ConnectorService connectorService,
            Path workspaceRoot,
            int connectTimeoutMs,
            int readTimeoutMs,
            long maxAttachmentBytes,
            long maxAttachmentsTotalBytes,
            int maxBodyChars,
            int maxBatch) {
        this.connectorService = connectorService;
        this.workspaceRoot = workspaceRoot;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.maxAttachmentBytes = maxAttachmentBytes;
        this.maxAttachmentsTotalBytes = maxAttachmentsTotalBytes;
        this.maxBodyChars = maxBodyChars;
        this.maxBatch = maxBatch <= 0 ? 20 : maxBatch;
    }

    @Override
    public String type() {
        return "MAIL";
    }

    @Override
    public List<AgentTool> tools(long agentId, ConnectorTypeDescriptor descriptor) {
        return MailTools.create(agentId, connectorService, workspaceRoot,
                connectTimeoutMs, readTimeoutMs, maxAttachmentBytes, maxAttachmentsTotalBytes, maxBodyChars);
    }

    @Override
    public ConnectorTestResult probe(Map<String, String> config) {
        MailSettings settings = MailSettings.of(config);
        MailClient client = new MailClient(settings, connectTimeoutMs, readTimeoutMs,
                maxAttachmentBytes, maxAttachmentsTotalBytes, maxBodyChars);
        try {
            List<String> steps = new ArrayList<>(client.probe());
            steps.add("账号：" + settings.email() + "（" + settings.imapHost() + " / " + settings.smtpHost() + "）");
            return new ConnectorTestResult(true, "连接正常", steps);
        } catch (MailClient.MailException ex) {
            return new ConnectorTestResult(false, ex.getMessage(), List.of());
        } catch (RuntimeException ex) {
            return new ConnectorTestResult(false, "测试失败：" + ex.getMessage(), List.of());
        }
    }

    @Override
    public String reply(InboundMessage message, Map<String, String> config, String body) {
        MailSettings settings = MailSettings.of(config);
        MailClient client = new MailClient(settings, connectTimeoutMs, readTimeoutMs,
                maxAttachmentBytes, maxAttachmentsTotalBytes, maxBodyChars);
        // 自动回复必须带 RFC 3834 头：否则对方的自动回复系统会跟我们互相回个没完
        String messageId = client.replyAuto(message.uid(), "INBOX", body);
        return messageId == null ? "已回复" : "已回复（Message-ID: " + messageId + "）";
    }

    @Override
    public PollResult poll(ConnectorRecord record, Map<String, String> config) {
        MailSettings settings = MailSettings.of(config);
        MailClient client = new MailClient(settings, connectTimeoutMs, readTimeoutMs,
                maxAttachmentBytes, maxAttachmentsTotalBytes, maxBodyChars);
        MailClient.PollResult raw = client.pollNew(record.uidValidity(), record.lastUid(), maxBatch);
        List<InboundMessage> messages = new ArrayList<>(raw.messages().size());
        for (MailClient.InboundRaw message : raw.messages()) {
            messages.add(new InboundMessage(
                    message.uid(),
                    message.uidValidity(),
                    message.messageId(),
                    message.threadKey(),
                    message.from(),
                    message.fromName(),
                    message.subject(),
                    message.text(),
                    message.receivedAt(),
                    message.autoSubmitted(),
                    message.precedence(),
                    message.recipients()));
        }
        return new PollResult(messages, raw.uidValidity(), raw.lastUid(), raw.baselined());
    }
}
