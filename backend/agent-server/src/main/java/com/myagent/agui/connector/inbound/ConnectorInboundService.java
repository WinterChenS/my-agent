package com.myagent.agui.connector.inbound;

import com.myagent.agui.AgentFactory;
import com.myagent.agui.connector.ConnectorProvider;
import com.myagent.agui.connector.ConnectorToolkit;
import com.myagent.channelconfig.service.ChannelPushPort;
import com.myagent.chat.entity.ChatSession;
import com.myagent.chat.service.ChatSessionService;
import com.myagent.connectorconfig.ConnectorRecord;
import com.myagent.connectorconfig.ConnectorService;
import com.myagent.connectorconfig.ConnectorStore;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.harness.agent.HarnessAgent;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 入站邮件的处理：去重 → 护栏 → 按 {@code inbound_mode} 通知/自动回复 → 记账。
 *
 * <p>三种模式：
 * <ul>
 *   <li>{@code OFF}：只记录（默认，不打扰）；</li>
 *   <li>{@code NOTIFY}：把「谁发了什么」推到用户的 IM（没绑定 IM 就只留记录）；</li>
 *   <li>{@code AUTOREPLY}：交给绑定本连接器的 Agent 跑一轮，然后把结果作为回复发出去。</li>
 * </ul>
 *
 * <p>护栏（防邮件轰炸 / 防自环 / 防越权）：
 * <ol>
 *   <li>自称自动回复的邮件（{@code Auto-Submitted}）不回；</li>
 *   <li>{@code Precedence: bulk/auto_reply/junk} 的不回；</li>
 *   <li>发件人是自己（连接器邮箱）的不回；</li>
 *   <li>{@code noreply/no-reply/mailer-daemon/postmaster} 开头的不回；</li>
 *   <li>**同一邮件线程只自动回一次**；</li>
 *   <li>每连接器每小时自动回复上限（默认 10）。</li>
 * </ol>
 */
@Service
public class ConnectorInboundService {

    private static final Logger log = LoggerFactory.getLogger(ConnectorInboundService.class);

    /** 自动回复正文前缀：明确告诉对方这是机器回复。 */
    static final String AUTO_REPLY_SIGNATURE = "\n\n——\n（本条由 AI 助手自动回复，如需人工处理请直接联系邮箱主人）";

    /** 通知正文里引用的邮件正文长度上限（IM 里太长会刷屏）。 */
    static final int NOTIFY_PREVIEW_CHARS = 300;

    private final ConnectorStore store;
    private final ConnectorService connectorService;
    private final ConnectorToolkit connectorToolkit;
    private final AgentFactory agentFactory;
    private final ChatSessionService sessionService;
    private final ChannelPushPort pushPort;
    private final JdbcTemplate jdbc;
    private final int maxAutoRepliesPerHour;
    /** 单轮最多自动回复几封（一次涌入很多封时防止"连珠炮"回信）。 */
    private final int maxAutoRepliesPerPoll;

    public ConnectorInboundService(
            ConnectorStore store,
            ConnectorService connectorService,
            ConnectorToolkit connectorToolkit,
            AgentFactory agentFactory,
            ChatSessionService sessionService,
            ObjectProvider<ChannelPushPort> pushPort,
            JdbcTemplate jdbc,
            @Value("${myagent.connector.mail.max-auto-replies-per-hour:10}") int maxAutoRepliesPerHour,
            @Value("${myagent.connector.inbound.max-auto-replies-per-poll:3}") int maxAutoRepliesPerPoll) {
        this.store = store;
        this.connectorService = connectorService;
        this.connectorToolkit = connectorToolkit;
        this.agentFactory = agentFactory;
        this.sessionService = sessionService;
        this.pushPort = pushPort.getIfAvailable();
        this.jdbc = jdbc;
        this.maxAutoRepliesPerHour = maxAutoRepliesPerHour <= 0 ? 10 : maxAutoRepliesPerHour;
        this.maxAutoRepliesPerPoll = maxAutoRepliesPerPoll <= 0 ? 3 : maxAutoRepliesPerPoll;
    }

    /**
     * 处理一个连接器拉到的新邮件，并推进 IMAP 游标。
     *
     * @return 处理条数（日志/测试用）
     */
    public int handle(ConnectorRecord record, List<ConnectorProvider.InboundMessage> messages) {
        if (messages.isEmpty()) {
            return 0;
        }
        Map<String, String> config = connectorService.config(record);
        ConnectorProvider provider = connectorToolkit.providerFor(record.type());
        if (provider == null) {
            log.warn("连接器类型没有 Provider，跳过入站 id={} type={}", record.id(), record.type());
            return 0;
        }
        int handled = 0;
        int repliedThisPoll = 0;
        for (ConnectorProvider.InboundMessage message : messages) {
            try {
                if (process(record, provider, config, message, repliedThisPoll)) {
                    handled++;
                    if ("AUTOREPLY".equalsIgnoreCase(record.inboundMode())) {
                        repliedThisPoll++;
                    }
                }
            } catch (Exception ex) {
                log.warn("处理入站邮件失败 connectorId={} uid={}: {}", record.id(), message.uid(), ex.toString());
                markFailed(record.id(), message.externalId(), ex.getMessage());
            }
        }
        // 游标由 ConnectorInboundPoller 依据 PollResult 统一保存：
        // 这里只负责"处理"，不管游标——否则"只打基线（没邮件但有游标）"那次就丢游标了。
        return handled;
    }

    private boolean process(
            ConnectorRecord record,
            ConnectorProvider provider,
            Map<String, String> config,
            ConnectorProvider.InboundMessage message,
            int repliedThisPoll) {
        boolean first = store.recordInbound(
                record.id(), message.externalId(), message.threadKey(),
                message.fromAddr(), message.subject(), message.receivedAt());
        if (!first) {
            return false;
        }
        Long eventId = store.inboundId(record.id(), message.externalId()).orElse(null);
        String mode = record.inboundMode() == null ? "OFF" : record.inboundMode();

        String skip = skipReason(record, message, mode, repliedThisPoll, config);
        if (skip != null) {
            store.finishInbound(eventId, "SKIPPED", skip, null, null);
            return false;
        }
        if ("NOTIFY".equals(mode)) {
            notify(record, message);
            store.finishInbound(eventId, "HANDLED", "通知已推送", null, null);
            return true;
        }
        if ("AUTOREPLY".equals(mode)) {
            String runId = autoReply(record, provider, config, message, eventId);
            store.finishInbound(eventId, "HANDLED", null, runId, null);
            return true;
        }
        store.finishInbound(eventId, "SKIPPED", "入站模式为 OFF（只记录）", null, null);
        return false;
    }

    /**
     * 护栏判定：返回 null 表示可以处理，否则返回跳过原因（写进事件，便于排查"为什么没回"）。
     *
     * <p>这些规则**必须能单测**：写错一条就是给别人发垃圾邮件。
     */
    String skipReason(ConnectorRecord record, ConnectorProvider.InboundMessage message, String mode) {
        return skipReason(record, message, mode, 0, connectorService.config(record));
    }

    /**
     * 护栏判定（带"本轮已回几封"与配置）。
     *
     * @param repliedThisPoll 本轮已经自动回复了几封（一次涌入很多封时防"连珠炮"）
     */
    String skipReason(
            ConnectorRecord record,
            ConnectorProvider.InboundMessage message,
            String mode,
            int repliedThisPoll,
            Map<String, String> config) {
        String from = normalize(message.fromAddr());
        if (from.isEmpty()) {
            return "没有发件人";
        }
        if (from.equals(normalize(connectorAddress(record)))) {
            return "发件人是自己（防自环）";
        }
        if (from.startsWith("noreply") || from.startsWith("no-reply")
                || from.startsWith("mailer-daemon") || from.startsWith("postmaster")) {
            return "系统邮箱（noreply/postmaster），不处理";
        }
        if (message.autoSubmitted()) {
            return "对方是自动回复（Auto-Submitted），不再自动回";
        }
        String precedence = message.bulkPrecedence() == null ? "" : message.bulkPrecedence().toLowerCase(Locale.ROOT);
        if (precedence.contains("bulk") || precedence.contains("auto_reply") || precedence.contains("junk")) {
            return "批量/自动回复邮件（Precedence: " + message.bulkPrecedence() + "），不处理";
        }
        if (!"AUTOREPLY".equals(mode)) {
            return null;
        }
        if (!record.enabled()) {
            return "连接器已停用";
        }
        // 关闭了发信却开着自动回复 = 自相矛盾：自动回复本质就是发信，直接跳过并说明原因
        if ("false".equalsIgnoreCase(String.valueOf(config.getOrDefault("allowSend", "true")))) {
            return "连接器关闭了「允许发信」，自动回复也会被拒（请先打开允许发信）";
        }
        // 只在抄送/群发里出现（不是直接发给我）的邮件不自动回复：
        // 否则会把"抄送给我的通知邮件"当成对话对象，回一堆没有意义的内容
        String self = normalize(connectorAddress(record));
        List<String> recipients = message.recipients() == null ? List.of() : message.recipients();
        if (!self.isEmpty() && !recipients.isEmpty()
                && recipients.stream().noneMatch(to -> normalize(to).contains(self))) {
            return "收件人里没有本邮箱（可能只是抄送/群发），不自动回复";
        }
        // 自动回复白名单：留空=对所有人回；填了就只回名单里的发件人（支持 @域名.com 整域）
        String replyWhitelist = config.get("allowedAutoReplySenders");
        if (!isBlank(replyWhitelist) && !matchesAny(replyWhitelist, from)) {
            return "发件人不在自动回复白名单里（只回名单内的地址/域名）";
        }
        if (boundAgents(record.id()).isEmpty()) {
            return "没有绑定任何 Agent，无法自动回复";
        }
        if (store.countAutoRepliesForThread(record.id(), message.threadKey()) > 0) {
            return "这条邮件线程已经自动回复过（防互相轰炸）";
        }
        if (autoRepliesThisHour(record.id()) >= maxAutoRepliesPerHour) {
            return "本小时自动回复已达上限（" + maxAutoRepliesPerHour + " 封）";
        }
        if (repliedThisPoll >= maxAutoRepliesPerPoll) {
            return "本轮自动回复已达上限（" + maxAutoRepliesPerPoll + " 封），这批邮件留给你人工处理";
        }
        return null;
    }

    // ===================== 通知 =====================

    private void notify(ConnectorRecord record, ConnectorProvider.InboundMessage message) {
        if (pushPort == null) {
            log.info("入站通知：渠道未就绪，仅记录 connectorId={} subject={}", record.id(), message.subject());
            return;
        }
        Optional<Peer> peer = peerOfOwner(record.ownerUserId());
        if (peer.isEmpty()) {
            log.info("入站通知：该用户没有可用的 IM 接收人，仅记录 connectorId={}", record.id());
            return;
        }
        String text = "📧 新邮件（" + record.displayName() + "）\n"
                + "发件人：" + message.fromAddr() + "\n"
                + "主题：" + message.subject() + "\n"
                + "时间：" + format(message.receivedAt()) + "\n\n"
                + preview(message.text());
        try {
            pushPort.push(peer.get().appId(), peer.get().peerKey(), text, List.of());
        } catch (Exception ex) {
            log.warn("入站通知推送失败 connectorId={}: {}", record.id(), ex.toString());
        }
    }

    private record Peer(Long appId, String peerKey) {}

    /** 该用户名下（任一 IM 应用）最近互动的接收人。 */
    private Optional<Peer> peerOfOwner(long ownerUserId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select cp.channel_app_id as app_id, cp.peer_key as peer_key"
                        + " from channel_peer cp join channel_app ca on ca.id = cp.channel_app_id"
                        + " where ca.owner_user_id = ? order by cp.id desc limit 1", ownerUserId);
        return rows.stream().findFirst().map(row -> new Peer(
                ((Number) row.get("app_id")).longValue(), String.valueOf(row.get("peer_key"))));
    }

    // ===================== 自动回复 =====================

    private String autoReply(
            ConnectorRecord record,
            ConnectorProvider provider,
            Map<String, String> config,
            ConnectorProvider.InboundMessage message,
            Long eventId) {
        List<Long> agents = boundAgents(record.id());
        Long agentId = agents.get(0);
        Long ownerUserId = record.ownerUserId();
        // 每个连接器固定一个会话：邮件往来有上下文，Agent 才接得上话；
        // 会话名固定便于用户在会话列表里找到"这封邮件是怎么回的"。
        String threadId = "connector-" + record.id();
        ChatSession session = sessionService.findByThread(ownerUserId, threadId).orElseGet(
                () -> sessionService.createForThread(ownerUserId, threadId, agentId));
        String runId = java.util.UUID.randomUUID().toString();

        String prompt = "你收到了一封新邮件，请根据内容判断如何回复。\n"
                + "发件人：" + message.fromAddr() + "\n"
                + "主题：" + message.subject() + "\n"
                + "时间：" + format(message.receivedAt()) + "\n"
                + "正文：\n" + message.text() + "\n\n"
                + "要求：直接用一段**邮件正文**回复（不要写「主题：」「收件人：」这类头部，不要解释你在做什么）。"
                + "如果这封邮件是广告/通知/无需回复的，就只回一句「无需回复」——系统会跳过发送。";

        String answer = runAgent(agentId, ownerUserId, session, prompt);
        sessionService.appendMessage(session.getId(), "ASSISTANT", answer, Map.of(
                "connectorId", record.id(),
                "inboundSubject", message.subject(),
                "autoReply", true));
        sessionService.touch(session.getId());

        if (answer == null || answer.isBlank() || answer.contains("无需回复")) {
            store.finishInbound(eventId, "SKIPPED", "Agent 判断无需回复", runId, null);
            return runId;
        }
        try {
            String result = provider.reply(message, config, answer.strip() + AUTO_REPLY_SIGNATURE);
            connectorService.logAction(record.id(), ownerUserId, "REPLY",
                    message.fromAddr(), "Re: " + message.subject(), true, null);
            log.info("已自动回复邮件 connectorId={} to={} result={}", record.id(), message.fromAddr(), result);
        } catch (Exception ex) {
            connectorService.logAction(record.id(), ownerUserId, "REPLY",
                    message.fromAddr(), "Re: " + message.subject(), false, ex.getMessage());
            throw ex;
        }
        return runId;
    }

    private String runAgent(Long agentId, Long userId, ChatSession session, String prompt) {
        HarnessAgent agent = agentFactory.build(agentId);
        Msg userMsg = Msg.builder()
                .role(MsgRole.USER)
                .content(List.of(TextBlock.builder().text(prompt).build()))
                .build();
        RuntimeContext context = RuntimeContext.builder()
                .sessionId(session.getThreadId())
                .userId(String.valueOf(userId))
                .build();
        StreamOptions options = StreamOptions.builder()
                .eventTypes(EventType.ALL)
                .incremental(true)
                .build();
        StringBuilder answer = new StringBuilder();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        agent.stream(List.of(userMsg), options, context)
                .doOnNext(event -> {
                    if (event != null && event.getType() == EventType.AGENT_RESULT && event.getMessage() != null) {
                        event.getMessage().getContent().stream()
                                .filter(block -> block instanceof TextBlock)
                                .map(block -> ((TextBlock) block).getText())
                                .filter(text -> text != null && !text.isEmpty())
                                .forEach(answer::append);
                    }
                })
                .onErrorResume(ex -> {
                    failure.set(ex);
                    return reactor.core.publisher.Mono.empty();
                })
                .blockLast();
        if (failure.get() != null) {
            throw new IllegalStateException(failure.get().getMessage(), failure.get());
        }
        return answer.toString().strip();
    }

    // ===================== 杂项 =====================

    private void markFailed(long connectorId, String externalId, String error) {
        store.inboundId(connectorId, externalId).ifPresent(id -> store.finishInbound(
                id, "FAILED", null, null, error == null ? "未知错误" : error));
    }

    private List<Long> boundAgents(long connectorId) {
        return store.agentIdsOf(connectorId);
    }

    private int autoRepliesThisHour(long connectorId) {
        return jdbc.queryForObject(
                "select count(*) from connector_inbound_event"
                        + " where connector_id = ? and run_id is not null and received_at >= now() - interval '1 hour'",
                Integer.class, connectorId);
    }

    /** 连接器自己的邮箱地址（判断"是不是自己发的"）。 */
    private String connectorAddress(ConnectorRecord record) {
        String email = connectorService.config(record).get("email");
        return email == null ? "" : email;
    }

    /**
     * 白名单匹配：逗号/分号/空白分隔；以 {@code @域} 表示整个域；大小写不敏感。
     *
     * <p>与 {@code MailSettings.matches} 同一套语义——这里不依赖邮件实现，
     * 因为"谁能触发自动回复"是连接器的通用策略（以后日历/工单连接器也用得上）。
     */
    static boolean matchesAny(String rawRules, String address) {
        if (isBlank(rawRules)) {
            return true;
        }
        String target = normalize(address);
        if (target.isEmpty()) {
            return false;
        }
        int at = target.lastIndexOf('@');
        String domain = at < 0 ? "" : target.substring(at + 1);
        for (String rule : rawRules.split("[,;\s]+")) {
            String normalized = rule.trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty()) {
                continue;
            }
            if (normalized.startsWith("@") ? domain.equals(normalized.substring(1)) : normalized.equals(target)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String normalize(String address) {
        return address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
    }

    private static String preview(String text) {
        if (text == null || text.isBlank()) {
            return "（无正文）";
        }
        String flat = text.strip();
        return flat.length() <= NOTIFY_PREVIEW_CHARS
                ? flat
                : flat.substring(0, NOTIFY_PREVIEW_CHARS) + "…";
    }

    private static String format(OffsetDateTime time) {
        return time == null ? "未知时间" : time.toLocalDateTime().toString().replace('T', ' ');
    }
}
