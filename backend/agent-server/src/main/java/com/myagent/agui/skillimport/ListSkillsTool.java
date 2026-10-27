package com.myagent.agui.skillimport;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.myagent.agentconfig.entity.Agent;
import com.myagent.agentconfig.mapper.AgentMapper;
import com.myagent.agentconfig.mapper.AgentSkillMapper;
import com.myagent.chat.entity.ChatSession;
import com.myagent.chat.mapper.ChatSessionMapper;
import com.myagent.skillconfig.entity.Skill;
import com.myagent.skillconfig.mapper.SkillMapper;
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
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 列出「当前 Agent 能用哪些技能，以及它们的**准确 skill-id**」。
 *
 * <p>为什么需要它（实测踩过）：框架的 skill-id 是 {@code 技能名_来源}（本平台来源固定 {@code myagent}），
 * 例如 {@code archify} 的真实 id 是 {@code archify_myagent}。
 * 用户在对话里说「用 archify 技能」，模型很自然地传 {@code archify} →
 * {@code Error: Skill not found: 'archify'}；更糟的是它随后会断言"没有可用技能"。
 *
 * <p>有了这个只读工具，模型可以随时查到准确 id，不必依赖系统提示里那一段清单。
 */
@Component
public class ListSkillsTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(ListSkillsTool.class);

    /** 平台同步到官方 Skill 仓库时写死的 source（skill-id = name + "_" + source）。 */
    public static final String SKILL_SOURCE = "myagent";

    private final SkillMapper skillMapper;
    private final AgentSkillMapper agentSkillMapper;
    private final AgentMapper agentMapper;
    private final ChatSessionMapper sessionMapper;
    /** 查"这个技能的文件落盘到哪个目录"（官方缓存目录）。 */
    private final SkillFileLocator fileLocator;

    public ListSkillsTool(
            SkillMapper skillMapper,
            AgentSkillMapper agentSkillMapper,
            AgentMapper agentMapper,
            ChatSessionMapper sessionMapper,
            @org.springframework.beans.factory.annotation.Value("${myagent.workspace-root:./.myagent-workspace}")
                    String workspaceRoot) {
        this.skillMapper = skillMapper;
        this.agentSkillMapper = agentSkillMapper;
        this.agentMapper = agentMapper;
        this.sessionMapper = sessionMapper;
        this.fileLocator = new SkillFileLocator(
                java.nio.file.Path.of(workspaceRoot).toAbsolutePath().normalize());
    }

    @Override
    public String getName() {
        return "list_skills";
    }

    @Override
    public String getDescription() {
        return "列出当前 Agent 已绑定且启用中的技能，以及每个技能的**准确 skill-id**。"
                + "用户提到某个技能、或调 load_skill_through_path 报 `Skill not found` 时，先调它确认 id。"
                + "注意：skill-id 形如「技能名_myagent」（例如 archify 的 id 是 archify_myagent），"
                + "**不能只用技能名**。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>());
        return schema;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> text(list(param)))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(ex -> Mono.just(text("查询技能失败：" + ex.getMessage())));
    }

    private String list(ToolCallParam param) {
        Long agentDbId = resolveAgentDbId(param);
        if (agentDbId == null) {
            return "当前没有会话上下文，无法确定是哪个 Agent。";
        }
        Agent agent = agentMapper.selectById(agentDbId);
        List<Long> ids = new ArrayList<>(agentSkillMapper.findSkillIds(agentDbId));
        if (ids.isEmpty()) {
            return "当前 Agent（" + (agent == null ? agentDbId : agent.getName())
                    + "）没有绑定任何技能。可以让我用 install_skill 装，或到「Agent 配置」里绑定。";
        }
        List<Skill> skills = skillMapper.selectList(Wrappers.<Skill>lambdaQuery()
                .in(Skill::getId, ids)
                .eq(Skill::getStatus, "ENABLED")
                .orderByAsc(Skill::getName));
        if (skills.isEmpty()) {
            return "当前 Agent 绑定了 " + ids.size()
                    + " 个技能，但都是停用状态（外部导入的默认停用）。请到「技能」页启用后再用。";
        }
        StringBuilder out = new StringBuilder("当前 Agent 可用技能 " + skills.size() + " 个：\n");
        for (Skill skill : skills) {
            String skillId = skillIdOf(skill.getName());
            out.append("- 名称：").append(skill.getName()).append('\n')
                    .append("  skill-id：").append(skillId)
                    .append("    ← 调 load_skill_through_path 时用这个，不要用「")
                    .append(skill.getName()).append("」\n");
            if (skill.getDescription() != null && !skill.getDescription().isBlank()) {
                String desc = skill.getDescription().replace('\n', ' ');
                out.append("  说明：").append(desc.length() > 120 ? desc.substring(0, 120) + "…" : desc)
                        .append('\n');
            }
            // 文件目录：官方 <available_skills> 不带 files-root，模型要跑 bin/xxx.mjs 得知道路径
            String userId = param.getRuntimeContext() == null
                    ? null : param.getRuntimeContext().getUserId();
            fileLocator.locate(userId, skill.getName()).ifPresent(dir -> {
                out.append("  文件目录：").append(dir.toAbsolutePath().normalize()).append('\n');
                SkillFileLocator.guessEntrypoint(dir, skill.getName()).ifPresent(entrypoint ->
                        out.append("  可执行入口：").append(entrypoint).append('\n'));
            });
        }
        return out.toString();
    }

    /** 官方 skill-id 规则：{@code 名称_来源}。 */
    public static String skillIdOf(String skillName) {
        return skillName + "_" + SKILL_SOURCE;
    }

    private Long resolveAgentDbId(ToolCallParam param) {
        String threadId = param.getRuntimeContext() == null
                ? null : param.getRuntimeContext().getSessionId();
        if (threadId == null) {
            return null;
        }
        ChatSession session = sessionMapper.selectOne(Wrappers.<ChatSession>lambdaQuery()
                .eq(ChatSession::getThreadId, threadId)
                .last("limit 1"));
        if (session == null) {
            log.debug("list_skills: 找不到会话 threadId={}", threadId);
            return null;
        }
        return session.getAgentId();
    }

    private static ToolResultBlock text(String content) {
        return ToolResultBlock.builder().output(TextBlock.builder().text(content).build()).build();
    }
}
