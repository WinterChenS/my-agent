package com.myagent.agui.channel;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 把超长文本拆成多条 IM 消息。
 *
 * <p>为什么需要：IM 平台对单条消息有硬上限（钉钉文本约 20000 字节），超了会被网关截断——
 * 用户看到的就是"话说到一半没了"。以前平台侧的做法是把正文截到 3000 字再加"…（内容过长，已截断）"，
 * 等于主动丢内容。现在改成**按上限拆成多条依次发**，内容不丢。
 *
 * <p>拆分优先级：空行分段 &gt; 换行 &gt; 句末标点 &gt; 硬切（不切坏 emoji/代理对）。
 * 多条时每段前缀 {@code （i/N）}，避免在 IM 里看起来像重复发送。
 */
@Component
public class ImTextSplitter {

    /** 留出「（i/N）」前缀的位置。 */
    private static final int PREFIX_RESERVE = 10;

    /** 单条消息的字符上限（可配置：myagent.channel.max-text-chars）。 */
    private final int maxChars;

    public ImTextSplitter(@Value("${myagent.channel.max-text-chars:2000}") int maxChars) {
        // 配 0/负数视为"没配"，用默认 2000；其余原样（单测里会传很小的值）
        this.maxChars = maxChars > 0 ? maxChars : 2000;
    }

    public int maxChars() {
        return maxChars;
    }

    /**
     * 拆分文本。
     *
     * @return 至少一个元素；长度不超过上限时原样返回
     */
    public List<String> split(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.length() <= maxChars) {
            return List.of(normalized);
        }

        // 预算是「上限 - 序号前缀」；下限给 20 防止配了极小值把预算算成负数
        int budget = Math.max(20, maxChars - PREFIX_RESERVE);
        List<String> raw = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : normalized.split("\n\n", -1)) {
            for (String piece : breakParagraph(paragraph, budget)) {
                if (current.length() > 0 && current.length() + 2 + piece.length() > budget) {
                    raw.add(current.toString());
                    current.setLength(0);
                }
                if (current.length() > 0) {
                    current.append("\n\n");
                }
                current.append(piece);
            }
        }
        if (current.length() > 0) {
            raw.add(current.toString());
        }

        if (raw.size() <= 1) {
            // 单段但超长（极端情况：整段没有任何可切点）
            return hardSplit(raw.isEmpty() ? normalized : raw.get(0), budget).stream()
                    .map(String::strip)
                    .filter(part -> !part.isEmpty())
                    .toList();
        }

        List<String> numbered = new ArrayList<>(raw.size());
        int total = raw.size();
        for (int i = 0; i < total; i++) {
            String part = raw.get(i).strip();
            if (part.isEmpty()) {
                continue;
            }
            numbered.add("（" + (i + 1) + "/" + total + "）\n" + part);
        }
        return numbered.isEmpty() ? List.of(normalized) : numbered;
    }

    /**
     * 把一条 Msg 拆成多条：文本块按上限切，非文本块（图片/文件引用）原样放在最后一条上。
     *
     * <p>没有文本或未超长时返回原对象，避免无谓复制（调用方按"是否 >1 条"判断即可）。
     */
    public List<Msg> splitMsg(Msg msg) {
        if (msg == null) {
            return List.of();
        }
        String text = msg.getTextContent();
        List<ContentBlock> nonText = new ArrayList<>();
        if (msg.getContent() != null) {
            for (ContentBlock block : msg.getContent()) {
                if (!(block instanceof TextBlock)) {
                    nonText.add(block);
                }
            }
        }
        List<String> parts = split(text);
        if (parts.size() <= 1 && nonText.isEmpty()) {
            return List.of(msg);
        }
        if (parts.isEmpty()) {
            return List.of(msg);
        }
        List<Msg> result = new ArrayList<>(parts.size());
        for (int i = 0; i < parts.size(); i++) {
            List<ContentBlock> blocks = new ArrayList<>();
            blocks.add(TextBlock.builder().text(parts.get(i)).build());
            if (i == parts.size() - 1) {
                // 图片/文件引用只在最后一条上带一次，否则会重复发
                blocks.addAll(nonText);
            }
            result.add(Msg.builder()
                    .role(MsgRole.ASSISTANT)
                    .content(blocks)
                    .build());
        }
        return result;
    }

    /** 先按空行，再按换行，再按句末标点，最后硬切。 */
    private List<String> breakParagraph(String paragraph, int budget) {
        if (paragraph.length() <= budget) {
            return List.of(paragraph);
        }
        List<String> byLines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String one : paragraph.split("\n", -1)) {
            for (String chunk : breakLine(one, budget)) {
                if (line.length() > 0 && line.length() + 1 + chunk.length() > budget) {
                    byLines.add(line.toString());
                    line.setLength(0);
                }
                if (line.length() > 0) {
                    line.append('\n');
                }
                line.append(chunk);
            }
        }
        if (line.length() > 0) {
            byLines.add(line.toString());
        }
        return byLines;
    }

    /** 单行超长时按句末标点切，仍不行就硬切。 */
    private List<String> breakLine(String line, int budget) {
        if (line.length() <= budget) {
            return List.of(line);
        }
        List<String> sentences = new ArrayList<>();
        StringBuilder sentence = new StringBuilder();
        for (String piece : splitKeepDelimiter(line)) {
            if (sentence.length() > 0 && sentence.length() + piece.length() > budget) {
                sentences.add(sentence.toString());
                sentence.setLength(0);
            }
            sentence.append(piece);
            if (sentence.length() >= budget) {
                sentences.add(sentence.toString());
                sentence.setLength(0);
            }
        }
        if (sentence.length() > 0) {
            sentences.add(sentence.toString());
        }
        List<String> result = new ArrayList<>();
        for (String item : sentences) {
            result.addAll(hardSplit(item, budget));
        }
        return result;
    }

    /** 按句末标点切分（标点留在前一句）。 */
    private static List<String> splitKeepDelimiter(String text) {
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char[] chars = text.toCharArray();
        for (char c : chars) {
            current.append(c);
            if ("。！？；.!?;\n".indexOf(c) >= 0) {
                pieces.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            pieces.add(current.toString());
        }
        return pieces;
    }

    /** 硬切：按 budget 切，但不把代理对（emoji）切开。 */
    static List<String> hardSplit(String text, int budget) {
        List<String> parts = new ArrayList<>();
        int index = 0;
        while (index < text.length()) {
            int end = Math.min(index + budget, text.length());
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
                end--; // 别把 emoji 劈成两半
            }
            parts.add(text.substring(index, end));
            index = end;
        }
        return parts;
    }
}
