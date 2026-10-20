package com.myagent.agui.context;

import com.myagent.agui.AgentFactory;
import com.myagent.agui.AguiProperties;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.harness.agent.memory.compaction.TokenCounterUtil;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 会话上下文——**可见且可控**（tier1-upgrades ②）。
 *
 * <p>三件事，全部走框架官方能力（门禁 G8，不自研压缩）：
 *
 * <ol>
 *   <li><b>可见</b>：读框架自己存的会话状态（{@code agent_state} 里 {@code state_key=agent_state}
 *       的那行就是官方 {@link AgentState} 的 JSON），用官方 {@link TokenCounterUtil} 估算
 *       「当前上下文 tokens」，并给出**本会话模型生效的**压缩触发阈值。
 *   <li><b>压缩</b>：用官方 {@link ConversationCompactor} 强制压缩（触发阈值临时压到 1 =
 *       「现在就压」），再把压缩后的消息列表写回同一行状态。
 *   <li><b>清空</b>：删掉该 threadId 的状态行——**聊天记录与产物都保留**，模型从零开始。
 * </ol>
 *
 * <p>刻意不自己解析/拼装框架状态：读进来用 {@link AgentState#fromJsonString}、写回去用
 * {@link AgentState#toJson()}，保证与框架的序列化格式永远一致。
 */
@Service
public class SessionContextService {

    private static final Logger log = LoggerFactory.getLogger(SessionContextService.class);

    /** 官方 AgentState 在状态表里的 key（框架固定用它）。 */
    private static final String STATE_KEY = "agent_state";

    private final JdbcTemplate jdbc;
    private final AgentFactory agentFactory;
    private final AguiProperties aguiProperties;

    public SessionContextService(
            JdbcTemplate jdbc, AgentFactory agentFactory, AguiProperties aguiProperties) {
        this.jdbc = jdbc;
        this.agentFactory = agentFactory;
        this.aguiProperties = aguiProperties;
    }

    /** 用量快照。 */
    public record Usage(
            int estimatedTokens,
            int messages,
            int triggerTokens,
            int keepTokens,
            boolean compacted,
            String summaryPreview,
            /** 模型生效的上下文窗口（配了就用配的，没配用兜底值）—— 用量条的分母 */
            int contextWindow) {}

    /** 读该 threadId 的官方状态（没有则 null）。 */
    private AgentState loadState(String threadId) {
        List<String> payloads = jdbc.queryForList(
                "select payload::text from agent_state where session_id = ? and state_key = ? "
                        + "order by ordinal asc limit 1",
                String.class, threadId, STATE_KEY);
        if (payloads.isEmpty()) {
            return null;
        }
        try {
            return AgentState.fromJsonString(payloads.get(0));
        } catch (Exception ex) {
            log.warn("会话状态解析失败 threadId={}: {}", threadId, ex.toString());
            return null;
        }
    }

    /** 当前上下文用量（估算）。 */
    public Usage usage(String threadId, Long agentDbId) {
        AgentState state = loadState(threadId);
        List<Msg> context = state == null || state.getContext() == null ? List.of() : state.getContext();
        int tokens = TokenCounterUtil.calculateToken(context);
        // 官方的压缩摘要既可落在 state.summary，也会作为一条名为 __compaction_summary__
        // 的消息留在上下文里；两者都算「压缩过」。
        String summary = state == null ? null : state.getSummary();
        String fromMessages = context.stream()
                .filter(m -> m.getName() != null && m.getName().contains("compaction_summary"))
                .map(io.agentscope.core.message.Msg::getTextContent)
                .findFirst()
                .orElse(null);
        String effectiveSummary = (summary != null && !summary.isBlank()) ? summary : fromMessages;
        return new Usage(
                tokens,
                context.size(),
                agentFactory.effectiveCompactionTrigger(agentDbId),
                aguiProperties.compactionKeepTokens(),
                effectiveSummary != null && !effectiveSummary.isBlank(),
                preview(effectiveSummary),
                agentFactory.effectiveContextWindow(agentDbId));
    }

    /**
     * 立刻压缩该会话上下文（官方实现，触发阈值临时置 1 表示「现在就压」）。
     *
     * @return 压缩后的用量；若框架判断无需压缩则原样返回当前用量
     */
    public Usage compact(String threadId, Long agentDbId) {
        AgentState state = loadState(threadId);
        if (state == null || state.getContext() == null || state.getContext().isEmpty()) {
            return usage(threadId, agentDbId);
        }
        try {
            Model model = (Model) agentFactory.buildChatModelFor(agentDbId);
            // flush/offload 交给框架的常规流程，这里只做「压缩」这一件事
            int keep = Math.max(1, aguiProperties.compactionKeepTokens());
            CompactionConfig config = CompactionConfig.builder()
                    .triggerTokens(1)
                    .keepTokens(keep)
                    // 关掉「按当前上下文比例保留」：手动压缩要的是真的把老内容压掉，
                    // 否则比例兜底会把整段上下文都算进保留区（实测 4 条消息 73 万 tokens 压不动）。
                    .keepTokensRatio(0)
                    .keepTokensMin(1024)
                    .keepTokensMax(keep)
                    .flushBeforeCompact(false)
                    .offloadBeforeCompact(false)
                    // 只做「摘要 + 保留最近」的话，超大的工具结果仍会占满上下文
                    // （实测：一个 4 条消息的会话 73 万 tokens 压缩后几乎没降）。
                    // 所以同时启用官方的工具结果瘦身：超长结果按配置截断。
                    .prune(CompactionConfig.PruneConfig.builder()
                            .protectTokens(keep)
                            .minimumTokens(1000)
                            .maxOutputChars(aguiProperties.toolResultMaxChars())
                            .build())
                    .model(model)
                    .build();
            ConversationCompactor compactor = new ConversationCompactor(model, null);
            Optional<List<Msg>> compacted = compactor
                    .compactIfNeeded(RuntimeContext.empty(), state.getContext(), config, threadId, threadId)
                    .block();
            if (compacted == null || compacted.isEmpty()) {
                log.info("压缩上下文：框架判断无需压缩 threadId={}", threadId);
                return usage(threadId, agentDbId);
            }
            List<Msg> newContext = compacted.get();
            int beforeMessages = state.getContext().size();
            int beforeTokens = TokenCounterUtil.calculateToken(state.getContext());
            state.contextMutable().clear();
            state.contextMutable().addAll(newContext);
            saveState(threadId, state);
            log.info("压缩上下文完成 threadId={} messages {}→{} tokens {}→{}", threadId,
                    beforeMessages, newContext.size(), beforeTokens, TokenCounterUtil.calculateToken(newContext));
            return usage(threadId, agentDbId);
        } catch (Exception ex) {
            log.warn("压缩上下文失败 threadId={}: {}", threadId, ex.toString());
            throw new com.myagent.common.BizException(
                    500, "压缩上下文失败：" + ex.getMessage(),
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /** 清空该会话的框架状态（聊天记录保留）。返回删除的状态行数。 */
    public int clear(String threadId) {
        int removed = jdbc.update("delete from agent_state where session_id = ?", threadId);
        log.info("清空上下文 threadId={} 删除状态行={}", threadId, removed);
        return removed;
    }

    /** 把改过的状态写回（只更新那一行；没有则插入）。 */
    private void saveState(String threadId, AgentState state) {
        String json = state.toJson();
        int updated = jdbc.update(
                "update agent_state set payload = ?::jsonb, version = version + 1, updated_at = now() "
                        + "where session_id = ? and state_key = ?",
                json, threadId, STATE_KEY);
        if (updated == 0) {
            jdbc.update(
                    "insert into agent_state (agent_id, session_id, state_key, kind, ordinal, payload) "
                            + "values (?, ?, ?, 'SINGLE', 0, ?::jsonb)",
                    state.getUserId() == null ? "" : state.getUserId(), threadId, STATE_KEY, json);
        }
    }

    /** 摘要预览（包内可见，便于单测）。 */
    static String preview(String summary) {
        if (summary == null || summary.isBlank()) {
            return null;
        }
        String flat = summary.replace('\n', ' ').strip();
        return flat.length() <= 80 ? flat : flat.substring(0, 80) + "…";
    }
}
