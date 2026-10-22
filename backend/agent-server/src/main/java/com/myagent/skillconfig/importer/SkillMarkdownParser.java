package com.myagent.skillconfig.importer;

import java.util.Map;

/**
 * 把标准 SKILL.md（含 YAML frontmatter）+ 资源解析成平台要落库的三段内容。
 *
 * <p>接口在 {@code skillconfig} 包、实现放 {@code agui} 包（门禁 G2 / G8）：解析必须走 AgentScope 官方
 * {@code SkillUtil.createFrom}，平台不自己写 frontmatter 解析。
 */
public interface SkillMarkdownParser {

    /**
     * @param markdown SKILL.md 原文（含 frontmatter）
     * @param resources 相对路径 → 内容（二进制已按 {@code base64:} 编码）
     * @param source 写入官方仓库的 source 标记
     * @return 解析结果；frontmatter 缺失 name/description 或正文为空时抛业务异常
     */
    Parsed parse(String markdown, Map<String, String> resources, String source);

    /**
     * @param name 技能名
     * @param description 说明（决定模型何时命中）
     * @param content 去掉 frontmatter 后的正文
     */
    record Parsed(String name, String description, String content) {}
}
