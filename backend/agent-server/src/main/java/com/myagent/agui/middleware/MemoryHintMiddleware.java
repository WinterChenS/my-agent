package com.myagent.agui.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 每轮把该用户**记忆里的关键事实**提到系统提示最前面（官方 {@code MiddlewareBase#onSystemPrompt} 钩子）。
 *
 * <p>为什么需要它（实测，2026-10-20）：
 * 框架已经把 {@code MEMORY.md} 与近期台账注入到 {@code <loaded_context><memory_context>} 里，但那是
 * 「工作区参考资料」区，位置靠后、体量大（十几 KB）。实测真模型（deepseek-flash）明明拿到了
 * 「用户希望被称为「主人」」，仍然去调 {@code memory_search}：
 * <ul>
 *   <li>整句 / 多词查询（如 {@code "用户称呼 名字 偏好"}）→ 框架是整串字面量匹配 → 0 命中；</li>
 *   <li>于是它照着空结果回答「我目前没有关于你名字或称呼的记录」——用户看到的就是「永久记忆不生效」。</li>
 * </ul>
 * 提示词层面已经要求过「先看 memory_context」，模型不听。所以这里改成**指令区注入**：
 * 把这些事实放进系统提示的显眼位置，并明确「以此为准，不要声称没有记录」。
 *
 * <p>只读框架自己维护的记忆文件（{@code <workspaceRoot>/<userId>/MEMORY.md}），
 * 不改写、不重写框架的记忆逻辑（门禁 G8）；读取失败一律原样放行。
 */
public class MemoryHintMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(MemoryHintMiddleware.class);

    /** 注入块的标题：放最前面，模型更容易当“指令”而不是“资料”。 */
    static final String TITLE = "## 当前用户（来自长期记忆，已确认，务必遵守）";

    /** 最多注入多少条、多少字符（避免把系统提示撑爆）。 */
    static final int MAX_LINES = 12;
    static final int MAX_CHARS = 900;

    /** 这些行优先（称呼/偏好之类最容易被问到）。 */
    private static final Pattern KEY_LINE =
            Pattern.compile("称呼|叫法|名字|昵称|偏好|喜欢|习惯|身份|语言|语气|所在地|城市");

    /** 优先取的章节标题（MEMORY.md 的「用户偏好」小节）。 */
    private static final Pattern PREF_SECTION = Pattern.compile("用户偏好|用户画像|用户信息|个人偏好");

    private final Path workspaceRoot;

    public MemoryHintMiddleware(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext context, String systemPrompt) {
        String hint = hintFor(context);
        if (hint == null || hint.isBlank()) {
            return Mono.justOrEmpty(systemPrompt == null ? "" : systemPrompt);
        }
        String base = systemPrompt == null ? "" : systemPrompt;
        return Mono.just(hint + "\n\n" + base);
    }

    /** 读该用户的 MEMORY.md，抽出一小段关键事实；拿不到就返回 null（不影响本轮）。 */
    String hintFor(RuntimeContext context) {
        String userId = context == null ? null : context.getUserId();
        if (userId == null || userId.isBlank() || workspaceRoot == null) {
            return null;
        }
        Path file = workspaceRoot.resolve(userId).resolve("MEMORY.md");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            String markdown = Files.readString(file, StandardCharsets.UTF_8);
            List<String> lines = pickLines(markdown);
            if (lines.isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder(TITLE).append('\n');
            for (String line : lines) {
                sb.append("- ").append(line).append('\n');
            }
            sb.append("（以上是已确认的用户信息，**不是参考资料**：\n")
                    .append("① **每次回答都要用这里的称呼称呼用户**（例如开头/句中用「主人」），不要用泛泛的「你」；\n")
                    .append("② 涉及这些事实时直接以此为准，不要再声称“没有记录”，也不要为了确认去检索；\n")
                    .append("③ 记忆里确实没有的信息（比如还从没告诉过你的姓名/身份），就直说没记下并顺便用上称呼。）");
            return sb.toString();
        } catch (Exception ex) {
            log.warn("读取 MEMORY.md 生成记忆提醒失败 user={}: {}", userId, ex.toString());
            return null;
        }
    }

    /**
     * 从 MEMORY.md 里挑出要注入的行（纯函数，便于单测）。
     *
     * <p>规则：先取「用户偏好」类小节的全部要点；再按关键词补充其它行；去重、限量。
     */
    static List<String> pickLines(String markdown) {
        List<String> picked = new ArrayList<>();
        int chars = 0;

        for (String line : sectionBullets(markdown)) {
            if (add(picked, line)) {
                chars += line.length();
                if (picked.size() >= MAX_LINES || chars >= MAX_CHARS) {
                    return picked;
                }
            }
        }
        for (String raw : markdown.split("\n")) {
            String line = clean(raw);
            if (line == null || !KEY_LINE.matcher(line).find()) {
                continue;
            }
            if (add(picked, line)) {
                chars += line.length();
                if (picked.size() >= MAX_LINES || chars >= MAX_CHARS) {
                    break;
                }
            }
        }
        return picked;
    }

    /** 「用户偏好 / 用户画像 …」小节的要点行。 */
    private static List<String> sectionBullets(String markdown) {
        List<String> out = new ArrayList<>();
        boolean inSection = false;
        for (String raw : markdown.split("\n")) {
            String trimmed = raw.trim();
            if (trimmed.startsWith("#")) {
                inSection = PREF_SECTION.matcher(trimmed).find();
                continue;
            }
            if (!inSection) {
                continue;
            }
            String line = clean(raw);
            if (line != null) {
                out.add(line);
            }
        }
        return out;
    }

    /** 去掉列表符号/空行/标题行，返回要点正文；不是要点就返回 null。 */
    private static String clean(String raw) {
        String line = raw == null ? "" : raw.trim();
        if (line.isEmpty() || line.startsWith("#")) {
            return null;
        }
        line = line.replaceFirst("^[-*+]\\s+", "").trim();
        if (line.isEmpty()) {
            return null;
        }
        return line.length() > MAX_CHARS ? line.substring(0, MAX_CHARS) : line;
    }

    /** 去重加入（忽略完全相同的条目）。 */
    private static boolean add(List<String> picked, String line) {
        if (line == null || picked.contains(line)) {
            return false;
        }
        picked.add(line);
        return true;
    }
}
