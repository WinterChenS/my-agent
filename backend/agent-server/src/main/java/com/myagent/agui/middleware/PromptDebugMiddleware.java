package com.myagent.agui.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 诊断用中间件：把**最终**系统提示的形态打到日志里（默认关闭）。
 *
 * <p>为什么需要它（2026-10-23 实测）：技能的 {@code <available_skills>} 块如果没渲染出来，
 * 框架**不报错也不告警**——模型只是"看不到技能"，然后说「没有可用技能」或
 * 调用 {@code load_skill_through_path} 拿到 {@code Skill not found}。
 * 这条链路静默失败过好几次，光看业务日志完全看不出来。
 *
 * <p>打开方式：{@code MYAGENT_DEBUG_SYSTEM_PROMPT=true}。打开后每轮记录：
 * 提示词长度、是否含技能清单、技能清单里有几个技能、以及前 2000 字符。
 */
public class PromptDebugMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(PromptDebugMiddleware.class);

    private static final int DUMP_CHARS = 2000;

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext context, String systemPrompt) {
        String prompt = systemPrompt == null ? "" : systemPrompt;
        int skills = countOccurrences(prompt, "<skill>");
        // 框架的 effectiveFilter = builderFilter.overlay(context 里的 SkillFilter)，
        // 而 overlay 是「替换」不是「取交集」：context 里若有 none()，绑定的技能会被全部清掉。
        // 技能清单为空时，这一行是唯一能看出原因的线索。
        io.agentscope.core.skill.SkillFilter contextFilter = context == null
                ? null
                : context.get(io.agentscope.core.skill.SkillFilter.class);
        log.info(
                "系统提示诊断：context 里的 SkillFilter={}（agui-interaction 允许={}）",
                contextFilter == null ? "null" : "present",
                contextFilter == null ? "n/a" : contextFilter.isAllowed("agui-interaction"));
        log.info(
                "系统提示诊断：长度={} 含技能清单={} 技能数={} threadId={}",
                prompt.length(),
                prompt.contains("<available_skills>"),
                skills,
                context == null ? null : context.getSessionId());
        if (log.isDebugEnabled()) {
            log.debug("系统提示前 {} 字符：\n{}", DUMP_CHARS, prompt.substring(0, Math.min(DUMP_CHARS, prompt.length())));
        }
        // 再看一眼 Agent 手上的技能仓库：仓库为空 / 读不到，同样会导致清单为空
        try {
            if (agent instanceof io.agentscope.core.ReActAgent react) {
                var middlewares = react.getMiddlewares();
                StringBuilder names = new StringBuilder();
                for (var mw : middlewares) {
                    names.append(' ').append(mw.getClass().getSimpleName());
                }
                log.info("系统提示诊断：中间件 {} 个：{}", middlewares.size(), names);
                log.info("系统提示诊断：agent.sysPrompt 长度={}", react.getSysPrompt() == null ? 0 : react.getSysPrompt().length());
            } else {
                log.info("系统提示诊断：agent 类型 {}", agent == null ? "null" : agent.getClass().getName());
            }
        } catch (Exception ex) {
            log.warn("系统提示诊断：读取技能仓库失败 {}", ex.toString());
        }
        return Mono.just(prompt);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = text.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }
}
