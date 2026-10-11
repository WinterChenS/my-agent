package com.myagent.agui;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 会话级技能覆盖（D21）。
 *
 * <p>`/agui/run` 在开始处理前把该 threadId 选中的技能 id 放进来，
 * {@link PlatformAgentResolver} 解析 Agent 时读取它，从而把「会话选中的 Skill」并入
 * AgentScope 官方 SkillFilter；run 结束后清空。
 *
 * <p>用 threadId 作键而不是 ThreadLocal：AgentResolver 可能在响应式线程上被调用，
 * ThreadLocal 不可靠。
 */
@Component
public class SessionSkillOverrides {

    private final Map<String, List<Long>> overrides = new ConcurrentHashMap<>();

    public void put(String threadId, List<Long> skillIds) {
        if (threadId == null) {
            return;
        }
        if (skillIds == null || skillIds.isEmpty()) {
            overrides.remove(threadId);
        } else {
            overrides.put(threadId, List.copyOf(skillIds));
        }
    }

    public List<Long> get(String threadId) {
        return threadId == null ? List.of() : overrides.getOrDefault(threadId, List.of());
    }

    public void clear(String threadId) {
        if (threadId != null) {
            overrides.remove(threadId);
        }
    }
}
