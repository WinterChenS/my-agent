package com.myagent.agui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.common.BizException;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.GenerateOptions;
import java.util.List;
import java.util.StringJoiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 用 AI 生成技能草稿：把「人类自然语言需求」变成技能的三段内容（name / description / content）。
 *
 * <p>为什么需要它：技能的 description 决定模型**什么时候命中**这个技能，content 是写给模型看的执行说明；
 * 手写这两段有门槛（要清楚有哪些工具可用、名字必须精确、格式有约束）。所以这里把
 * **可用的工具清单**与**技能格式要求**一起交给大模型，让它产出可直接落库的草稿，人再改。
 *
 * <p>复用平台已有能力，不另起一套：模型调用走 {@link AgentFactory#buildChatModel(Long)}
 * （与聊天路径同一套供应商/密钥/参数解析），工具清单走 {@link ToolCatalogService}
 * （本地工具取自框架自身的 Toolkit schema，MCP 工具取自已同步的 tool_cache）。
 */
@Service
public class SkillGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(SkillGeneratorService.class);

    /** 需求描述长度上限（太长既费 token 也没意义）。 */
    private static final int MAX_REQUIREMENT_CHARS = 4000;

    /** 最多往提示词里塞多少个 MCP 工具名（本机曾有几百个，全塞不划算）。 */
    private static final int MAX_MCP_TOOLS_IN_PROMPT = 300;

    private final AgentFactory agentFactory;
    private final ToolCatalogService toolCatalogService;
    private final ObjectMapper objectMapper;

    public SkillGeneratorService(
            AgentFactory agentFactory, ToolCatalogService toolCatalogService, ObjectMapper objectMapper) {
        this.agentFactory = agentFactory;
        this.toolCatalogService = toolCatalogService;
        this.objectMapper = objectMapper;
    }

    /** 生成的草稿。 */
    public record Draft(String name, String description, String content, String notice) {}

    /**
     * 按自然语言需求生成技能草稿。
     *
     * @param requirement 人类写的需求（越具体越好）
     * @param modelId 用哪个模型生成（为空则取第一个启用中的模型）
     */
    public Draft generate(String requirement, Long modelId) {
        if (!StringUtils.hasText(requirement)) {
            throw BizException.badRequest("请先用一句话描述你想要的技能");
        }
        String text = requirement.trim();
        if (text.length() > MAX_REQUIREMENT_CHARS) {
            text = text.substring(0, MAX_REQUIREMENT_CHARS);
        }
        ChatModelBase model = agentFactory.buildChatModel(modelId);
        Msg msg = Msg.builderForRole(MsgRole.USER)
                .textContent(buildPrompt(text))
                .build();
        GenerateOptions options = GenerateOptions.builder()
                // 技能正文可能比较长，给足输出额度；太小会把预算花在思考上导致正文为空（实测过）
                .maxTokens(4096)
                .stream(true)
                .build();
        String raw;
        try {
            List<ChatResponse> responses =
                    model.stream(List.of(msg), List.of(), options).collectList().block();
            raw = extract(responses);
        } catch (Exception ex) {
            log.warn("AI 生成技能失败: {}", ex.toString());
            throw new BizException(
                    502,
                    "调用模型生成失败：" + ex.getMessage(),
                    org.springframework.http.HttpStatus.BAD_GATEWAY);
        }
        log.info("AI 生成技能：模型输出 {} 字符", raw == null ? 0 : raw.length());
        return parse(raw);
    }

    /** 组装提示词：格式要求 + 可用工具 + 输出约定。 */
    private String buildPrompt(String requirement) {
        List<ToolCatalogService.ToolEntry> tools = toolCatalogService.list();
        StringJoiner local = new StringJoiner("\n");
        StringJoiner mcp = new StringJoiner("\n");
        int mcpCount = 0;
        int mcpTotal = 0;
        for (ToolCatalogService.ToolEntry tool : tools) {
            if (tool.group().startsWith("MCP")) {
                mcpTotal++;
                if (mcpCount < MAX_MCP_TOOLS_IN_PROMPT) {
                    mcp.add("- " + tool.name() + (tool.group().length() > 0 ? "（" + tool.group() + "）" : ""));
                    mcpCount++;
                }
            } else {
                local.add("- " + tool.name() + "：" + (tool.hint() == null ? "（无说明）" : tool.hint()));
            }
        }
        String mcpNote = mcpTotal > mcpCount
                ? "（MCP 工具共 " + mcpTotal + " 个，这里只列出前 " + mcpCount + " 个；如需引用未列出的，请让用户确认准确名字）"
                : "（共 " + mcpTotal + " 个）";

        return """
                你在为一个 Agent 平台撰写「技能（Skill）」。技能是写给**大模型**看的 Markdown 说明文档，
                当用户的任务与之相关时，平台会把这段内容注入模型的上下文，指导它怎么做事。

                ## 硬性格式要求（必须遵守）

                1. `name`：英文 kebab-case，简短唯一，例如 `order-inquiry`、`release-checklist`。
                2. `description`：**不能为空**（平台强制要求），而且它是模型判断「要不要用这个技能」的依据，
                   所以要写清两件事：①什么场景该用（触发条件）；②用户通常会怎么说（关键词/说法）。
                   一句话到两句话，中文。
                3. `content`：Markdown 正文，写给模型看的操作说明。建议结构：
                   - 一句话说明这个技能的用途；
                   - 「什么时候用 / 什么时候别用」；
                   - 具体步骤（要用到工具时，**必须写出准确工具名**，用行内代码包起来）；
                   - 硬性规则（禁止事项、必须确认的事项、输出格式要求）。

                ## 可用的本地工具（平台内置，名字直接可用）

                %s

                ## 可用的 MCP 工具（名字带 `服务器__工具` 命名空间，必须一字不差）

                %s

                ## 重要约束

                - 只能引用上面真实存在的工具名，**不要编造工具**；没把握就在正文里写「需人工确认工具名」。
                - 不要写代码，不要写解释，不要写 Markdown 之外的包装。
                - content 控制在 800 字以内，可执行、可检查，别写空话。

                ## 用户的需求

                %s

                ## 输出格式（严格 JSON，不要有任何其它文字、不要用 ``` 代码块包裹）

                {"name": "...", "description": "...", "content": "..."}
                """
                .formatted(local.toString(), mcp + "\n" + mcpNote, requirement);
    }

    /** 从模型响应里取文本（拼接所有 TextBlock）。 */
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

    /** 解析模型输出（容忍 ```json 包裹与前后废话）。 */
    Draft parse(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new BizException(
                    502, "模型没有返回内容，请换个模型或重试", org.springframework.http.HttpStatus.BAD_GATEWAY);
        }
        String cleaned = raw.trim();
        // 去掉 ```json ... ``` 包裹
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            int lastFence = cleaned.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                cleaned = cleaned.substring(firstNewline + 1, lastFence).trim();
            }
        }
        // 取第一个 { 到最后一个 }（模型偶尔会在 JSON 前后加一句话）
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new BizException(
                    502,
                    "模型返回的不是预期格式（没找到 JSON）：" + abbreviate(cleaned),
                    org.springframework.http.HttpStatus.BAD_GATEWAY);
        }
        try {
            JsonNode node = objectMapper.readTree(cleaned.substring(start, end + 1));
            String name = text(node, "name");
            String description = text(node, "description");
            String content = text(node, "content");
            if (!StringUtils.hasText(name) || !StringUtils.hasText(content)) {
                throw new BizException(
                        502,
                        "模型返回的 JSON 缺少 name 或 content",
                        org.springframework.http.HttpStatus.BAD_GATEWAY);
            }
            String notice = null;
            if (!StringUtils.hasText(description)) {
                // 平台要求 description 非空，缺了就先用 name 兜底并提示用户补
                description = "（请补充说明：什么场景该用这个技能、用户会怎么说）";
                notice = "模型没给出「说明」，已填入占位文案 —— 说明必须补写，它决定模型何时命中该技能。";
            }
            return new Draft(name.trim(), description.trim(), content.strip(), notice);
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException(
                    502,
                    "解析模型输出失败：" + ex.getMessage() + "｜原文：" + abbreviate(cleaned),
                    org.springframework.http.HttpStatus.BAD_GATEWAY);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private static String abbreviate(String text) {
        String flat = text.replace('\n', ' ').strip();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }
}
