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
    String generate(Long agentDbId, String firstUserMessage) {
        ChatModelBase model = agentFactory.buildChatModelFor(agentDbId);
        Msg msg = Msg.builderForRole(MsgRole.USER).textContent(PROMPT + firstUserMessage).build();
        GenerateOptions options = GenerateOptions.builder()
                // 标题很短：给足上限即可，不要占用太多窗口
                .maxTokens(64)
                .stream(false)
                .build();
        List<ChatResponse> responses = model.stream(List.of(msg), List.of(), options).collectList().block();
        String text = extract(responses);
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
        String text = firstUserMessage.strip().replace("\n", " ");
        if (text.length() <= FALLBACK_CHARS) {
            return text;
        }
        return text.substring(0, FALLBACK_CHARS) + "…";
    }

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
