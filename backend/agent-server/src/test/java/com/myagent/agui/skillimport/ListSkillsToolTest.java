package com.myagent.agui.skillimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.myagent.agentconfig.entity.Agent;
import com.myagent.agentconfig.mapper.AgentMapper;
import com.myagent.agentconfig.mapper.AgentSkillMapper;
import com.myagent.chat.entity.ChatSession;
import com.myagent.chat.mapper.ChatSessionMapper;
import com.myagent.skillconfig.entity.Skill;
import com.myagent.skillconfig.mapper.SkillMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * list_skills：给出「技能名 → 准确 skill-id」。
 *
 * <p>背景（用户实测）：`archify` 的真实 id 是 `archify_myagent`；模型按用户说的名字直接传 `archify`
 * 会得到 `Skill not found`，随后还误报「没有可用技能」。这个工具就是给模型一个可靠的查 id 入口。
 */
class ListSkillsToolTest {

    private static ToolCallParam param(String threadId) {
        RuntimeContext context = RuntimeContext.builder().sessionId(threadId).userId("1").build();
        return ToolCallParam.builder()
                .toolUseBlock(new ToolUseBlock("call-1", "list_skills", Map.of()))
                .input(Map.of())
                .runtimeContext(context)
                .build();
    }

    private static String textOf(ToolResultBlock block) {
        return block.getOutput().stream()
                .map(c -> c instanceof TextBlock t ? t.getText() : "")
                .reduce("", String::concat);
    }

    private static Skill skill(long id, String name, String status) {
        Skill s = new Skill();
        s.setId(id);
        s.setName(name);
        s.setStatus(status);
        s.setDescription("desc of " + name);
        return s;
    }

    @Test
    void 输出技能名与准确skill_id(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workspace) {
        SkillMapper skillMapper = mock(SkillMapper.class);
        AgentSkillMapper bindMapper = mock(AgentSkillMapper.class);
        AgentMapper agentMapper = mock(AgentMapper.class);
        ChatSessionMapper sessionMapper = mock(ChatSessionMapper.class);

        ChatSession session = new ChatSession();
        session.setId(100L);
        session.setThreadId("t-1");
        session.setAgentId(37L);
        when(sessionMapper.selectOne(any())).thenReturn(session);

        Agent agent = new Agent();
        agent.setId(37L);
        agent.setName("小梅");
        when(agentMapper.selectById(37L)).thenReturn(agent);

        when(bindMapper.findSkillIds(37L)).thenReturn(List.of(470L, 83L));
        when(skillMapper.selectList(any())).thenReturn(List.of(
                skill(470L, "archify", "ENABLED"),
                skill(83L, "agui-interaction", "ENABLED")));

        ListSkillsTool tool = new ListSkillsTool(skillMapper, bindMapper, agentMapper, sessionMapper, workspace.toString());
        String text = textOf(tool.callAsync(param("t-1")).block());

        assertThat(text).contains("archify_myagent").contains("agui-interaction_myagent");
        // 必须明确告诉模型"不要用技能名当 id"
        assertThat(text).contains("不要用「archify」");
    }

    @Test
    void 绑定但全部停用时给出可操作提示(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workspace) {
        SkillMapper skillMapper = mock(SkillMapper.class);
        AgentSkillMapper bindMapper = mock(AgentSkillMapper.class);
        AgentMapper agentMapper = mock(AgentMapper.class);
        ChatSessionMapper sessionMapper = mock(ChatSessionMapper.class);
        ChatSession session = new ChatSession();
        session.setThreadId("t-2");
        session.setAgentId(37L);
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(bindMapper.findSkillIds(37L)).thenReturn(List.of(470L));
        when(skillMapper.selectList(any())).thenReturn(List.of());

        String text = textOf(new ListSkillsTool(skillMapper, bindMapper, agentMapper, sessionMapper, workspace.toString())
                .callAsync(param("t-2")).block());

        assertThat(text).contains("停用").contains("启用");
    }

    @Test
    void 没有绑定技能时提示如何安装(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workspace) {
        SkillMapper skillMapper = mock(SkillMapper.class);
        AgentSkillMapper bindMapper = mock(AgentSkillMapper.class);
        AgentMapper agentMapper = mock(AgentMapper.class);
        ChatSessionMapper sessionMapper = mock(ChatSessionMapper.class);
        ChatSession session = new ChatSession();
        session.setThreadId("t-3");
        session.setAgentId(37L);
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(bindMapper.findSkillIds(37L)).thenReturn(List.of());

        String text = textOf(new ListSkillsTool(skillMapper, bindMapper, agentMapper, sessionMapper, workspace.toString())
                .callAsync(param("t-3")).block());

        assertThat(text).contains("没有绑定任何技能").contains("install_skill");
    }

    @Test
    void 没有上下文时给出说明而不是崩(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workspace) {
        String text = textOf(new ListSkillsTool(
                mock(SkillMapper.class), mock(AgentSkillMapper.class),
                mock(AgentMapper.class), mock(ChatSessionMapper.class), workspace.toString())
                .callAsync(param(null)).block());

        assertThat(text).contains("没有会话上下文");
    }

    @Test
    void 输出里带上技能文件目录与可执行入口(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workspace)
            throws Exception {
        java.nio.file.Path dir = workspace.resolve(".skills-cache").resolve("1")
                .resolve("postgresql_agentscope_agentscope_skills").resolve("archify");
        java.nio.file.Files.createDirectories(dir.resolve("bin"));
        java.nio.file.Files.writeString(dir.resolve("bin/archify.mjs"), "x");

        SkillMapper skillMapper = mock(SkillMapper.class);
        AgentSkillMapper bindMapper = mock(AgentSkillMapper.class);
        AgentMapper agentMapper = mock(AgentMapper.class);
        ChatSessionMapper sessionMapper = mock(ChatSessionMapper.class);
        ChatSession session = new ChatSession();
        session.setThreadId("t-4");
        session.setAgentId(37L);
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(bindMapper.findSkillIds(37L)).thenReturn(List.of(470L));
        when(skillMapper.selectList(any())).thenReturn(List.of(skill(470L, "archify", "ENABLED")));

        String text = textOf(new ListSkillsTool(skillMapper, bindMapper, agentMapper, sessionMapper, workspace.toString())
                .callAsync(param("t-4")).block());

        assertThat(text).contains("文件目录").contains("bin/archify.mjs");
    }

    @Test
    void skillId规则是名称加下划线myagent() {
        assertThat(ListSkillsTool.skillIdOf("archify")).isEqualTo("archify_myagent");
        assertThat(ListSkillsTool.skillIdOf("pdf")).isEqualTo("pdf_myagent");
    }
}
