package com.myagent.agui.middleware;

import com.myagent.agui.skillimport.SkillFileLocator;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import java.nio.file.Path;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 每轮把「技能文件到底落在哪个目录」写进系统提示。
 *
 * <p>为什么需要（实测 2026-10-27）：官方注入的 {@code <available_skills>} 只有
 * {@code <name>/<description>/<skill-id>}，**没有 files-root**。模型要执行
 * {@code bin/archify.mjs} 时不知道路径，只能 {@code dir /s} 全盘搜索——
 * 常常翻到过期的临时解压目录，或者把 20 步迭代耗光（实测 archify 就是这样失败的）。
 *
 * <p>这里只做"告诉它目录在哪"，不参与加载（门禁 G8）。
 * 目录不存在就不注入，保持提示词干净。
 */
public class SkillFilesHintMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(SkillFilesHintMiddleware.class);

    static final String TITLE = "## 技能文件位置（可直接用绝对路径执行）";

    /** 最多列出多少个技能（避免提示词膨胀）。 */
    static final int MAX_SKILLS = 12;

    private final SkillFileLocator locator;

    public SkillFilesHintMiddleware(Path workspaceRoot) {
        this.locator = new SkillFileLocator(workspaceRoot);
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext context, String systemPrompt) {
        String userId = context == null ? null : context.getUserId();
        String hint = hintFor(userId);
        if (hint == null || hint.isBlank()) {
            return Mono.just(systemPrompt);
        }
        String base = systemPrompt == null ? "" : systemPrompt;
        return Mono.just(base.isEmpty() ? hint : base + "\n\n" + hint);
    }

    String hintFor(String userId) {
        Map<String, Path> dirs = locator.locate(userId);
        if (dirs.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder(TITLE).append('\n');
        int count = 0;
        for (Map.Entry<String, Path> entry : dirs.entrySet()) {
            if (count >= MAX_SKILLS) {
                out.append("- …（还有 ").append(dirs.size() - count).append(" 个技能目录未列出）\n");
                break;
            }
            Path dir = entry.getValue();
            out.append("- ").append(entry.getKey()).append("：")
                    .append(dir.toAbsolutePath().normalize()).append('\n');
            SkillFileLocator.guessEntrypoint(dir, entry.getKey()).ifPresent(entrypoint ->
                    out.append("  可执行入口：").append(entrypoint)
                            .append("（例如 node ").append(dir.toAbsolutePath().normalize())
                            .append("/").append(entrypoint).append("）\n"));
            count++;
        }
        out.append("技能文件都在上面这些目录里（bin/ scripts/ renderers/ schemas/ assets/ …），")
                .append("**不要用 dir /s、glob **/xxx 去全盘找**，也不要用工作区里残留的临时解压目录。");
        return out.toString();
    }
}
