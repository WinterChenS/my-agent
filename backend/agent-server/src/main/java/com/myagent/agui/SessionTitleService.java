package com.myagent.agui;

import com.myagent.chat.entity.ChatSession;
import com.myagent.chat.mapper.ChatSessionMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ChatResponse;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 会话标题自动生成（Phase 17，用户要求：像 ChatGPT 那样按第一次对话自动起标题）。
 *
 * <p>做法：首条用户消息落库后，用**该会话的模型**生成一个短标题（异步，不阻塞对话流）；
 * 生成失败或返回空则回退为「首条消息截断」，保证列表里始终有可读标题。
 *
 * <p>只覆盖 {@code title_auto=true} 的会话——人工指定或改名过的标题不会被自动流程覆盖。
 */
@Component
public class SessionTitleService {

    private static final Logger log = LoggerFactory.getLogger(SessionTitleService.class);
    private static final String PROMPT =
            "请为下面这句用户提问生成一个中文标题，要求：不超过 12 个字，只输出标题本身，"
            + "不要引号、句号或任何解释。\n\n用户提问：";
    private static final int MAX_TITLE_CHARS = 24;
    private static final int FALLBACK_CHARS = 20;

    private final ChatSessionMapper sessionMapper;
    private final com.myagent.chat.service.ChatSessionService chatSessionService;
    private final ModelFactory modelFactory;
    private final AgentFactory agentFactory;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "session-title");
        t.setDaemon(true);
        return t;
    });

    public SessionTitleService(
            ChatSessionMapper sessionMapper,
            com.myagent.chat.service.ChatSessionService chatSessionService,
            ModelFactory modelFactory,
            AgentFactory agentFactory) {
        this.sessionMapper = sessionMapper;
        this.chatSessionService = chatSessionService;
        this.modelFactory = modelFactory;
        this.agentFactory = agentFactory;
    }

    /**
     * 只在该会话的**第一条**用户消息落库后生成标题（Phase 17）。
     *
     * <p>判断依据：用户消息条数 == 1 且标题仍是自动态；这样多轮对话不会反复改标题。
     */
    public void generateIfFirstMessage(ChatSession session, String firstUserMessage) {
        if (session == null || session.getId() == null) {
            return;
        }
        if (Boolean.FALSE.equals(session.getTitleAuto())) {
            return;
        }
        if (chatSessionService.countUserMessages(session.getId()) != 1) {
            return;
        }
        generateAsync(session, firstUserMessage);
    }

    /** 异步生成标题（首条用户消息时调用）。 */
    public void generateAsync(ChatSession session, String firstUserMessage) {
        if (session == null || firstUserMessage == null || firstUserMessage.isBlank()) {
            return;
        }
        Long sessionId = session.getId();
        Long agentId = session.getAgentId();
        executor.submit(() -> {
            try {
                String title = generate(agentId, firstUserMessage);
                updateIfAuto(sessionId, title);
            } catch (Exception ex) {
                log.warn("生成会话标题失败 sessionId={}: {}", sessionId, ex.toString());
                updateIfAuto(sessionId, fallbackTitle(firstUserMessage));
            }
        });
    }

    /** 调模型生成标题（失败时由调用方回退）。 */
    public String generate(Long agentDbId, String firstUserMessage) {
        firstUserMessage = stripAttachmentBlock(firstUserMessage);
        ChatModelBase model = agentFactory.buildChatModelFor(agentDbId);
        Msg msg = Msg.builderForRole(MsgRole.USER).textContent(PROMPT + firstUserMessage).build();
        GenerateOptions options = GenerateOptions.builder()
                // 标题很短：给足上限即可，不要占用太多窗口
                // 给足额度：部分模型（含推理型）额度太小会把预算花在思考上、正文为空
                .maxTokens(512)
                // 用流式：实测这个 OpenAI 兼容端点非流式解析不出文本，而流式正常
                .stream(true)
                .build();
        List<ChatResponse> responses = model.stream(List.of(msg), List.of(), options).collectList().block();
        String text = extract(responses);
        log.info("标题生成模型原始输出 session agent={} raw={}", agentDbId,
                text == null ? "null" : text.replace((char) 10, ' ').substring(0, Math.min(80, text.length())));
        String cleaned = sanitize(text);
        return cleaned.isBlank() ? fallbackTitle(firstUserMessage) : cleaned;
    }

    /** 从模型响应里取文本（拼接所有 TextBlock 的内容）。 */
    private static String extract(List<ChatResponse> responses) {
        if (responses == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (ChatResponse response : responses) {
            if (response == null || response.getContent() == null) {
                continue;
            }
            for (io.agentscope.core.message.ContentBlock block : response.getContent()) {
                if (block instanceof io.agentscope.core.message.TextBlock textBlock
                        && textBlock.getText() != null) {
                    text.append(textBlock.getText());
                }
            }
        }
        return text.toString();
    }

    /**
     * 去掉我们自己加在用户消息末尾的「[已上传附件]」块。
     *
     * <p>那是给模型的路径提示，不应该出现在标题里（实测回填时标题被它污染）。
     */
    public static String stripAttachmentBlock(String content) {
        if (content == null) {
            return "";
        }
        int idx = content.indexOf("[已上传附件]");
        return (idx >= 0 ? content.substring(0, idx) : content).strip();
    }

    /** 清洗：去掉引号/换行/多余空白，并限长（模型偶尔会多说一句）。 */
    static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.strip()
                .replace("\n", " ")
                .replaceAll("^[\"'“”「『]+", "")
                .replaceAll("[\"'“”」』]+$", "")
                .strip();
        if (text.length() > MAX_TITLE_CHARS) {
            text = text.substring(0, MAX_TITLE_CHARS).strip();
        }
        return text;
    }

    /** 回退标题：首条消息截断（保留可读性）。 */
    static String fallbackTitle(String firstUserMessage) {
        String text = stripAttachmentBlock(firstUserMessage).replace("\n", " ");
        if (text.length() <= FALLBACK_CHARS) {
            return text;
        }
        return text.substring(0, FALLBACK_CHARS) + "…";
    }

    /**
     * 历史会话标题回填（一次性运维动作）：给「标题仍是自动态」的会话按首条用户消息生成标题。
     *
     * <p>逐条同步生成（便于回报进度），单次调用限制条数，避免一次打爆模型。
     *
     * @return 实际更新的条数
     */
    public int backfill(java.util.List<FirstMessage> candidates) {
        int updated = 0;
        for (FirstMessage candidate : candidates) {
            if (candidate == null || candidate.sessionId() == null
                    || candidate.firstUserMessage() == null
                    || candidate.firstUserMessage().isBlank()
                    || candidate.agentId() == null) {
                continue;
            }
            try {
                String title = generate(candidate.agentId(), candidate.firstUserMessage());
                updateIfAuto(candidate.sessionId(), title);
                updated++;
            } catch (Exception ex) {
                log.warn("回填标题失败 sessionId={}: {}", candidate.sessionId(), ex.toString());
                updateIfAuto(candidate.sessionId(), fallbackTitle(candidate.firstUserMessage()));
                updated++;
            }
        }
        return updated;
    }

    /** 回填候选：会话 + 它绑定的 Agent + 首条用户消息。 */
    public record FirstMessage(Long sessionId, Long agentId, String firstUserMessage) {}

    /** 仅在 title_auto=true 时更新标题。 */
    private void updateIfAuto(Long sessionId, String title) {
        if (sessionId == null || title == null || title.isBlank()) {
            return;
        }
        ChatSession current = sessionMapper.selectById(sessionId);
        if (current == null || Boolean.FALSE.equals(current.getTitleAuto())) {
            return; // 人工指定过，不覆盖
        }
        ChatSession patch = new ChatSession();
        patch.setId(sessionId);
        patch.setTitle(title);
        patch.setTitleAuto(false); // 生成后即固化，避免被反复改写
        sessionMapper.updateById(patch);
        log.info("会话标题已生成 sessionId={} title={}", sessionId, title);
    }
}
