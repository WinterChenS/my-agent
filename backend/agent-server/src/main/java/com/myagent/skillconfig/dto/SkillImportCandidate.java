package com.myagent.skillconfig.dto;

import java.util.List;

/**
 * 一个待安装 / 已安装技能的预览信息。
 *
 * <p>{@code content} 是 SKILL.md 的**正文**（frontmatter 已被官方解析器剥掉），预览时截断到 4000 字符，
 * 完整内容在真正安装时才落库。
 *
 * @param root 仓库内的目录（{@code skills/pdf}），多技能仓库里用来区分
 * @param pack 所属技能包（{@code .claude-plugin/marketplace.json} 里的插件名）；没有则为 null
 * @param existingSkillId 平台里已有同名 Skill 时的主键（用于提示「将更新」）
 */
public record SkillImportCandidate(
        String root,
        String pack,
        String name,
        String description,
        String content,
        int resourceCount,
        long resourceBytes,
        List<String> resourcePaths,
        String verdict,
        List<String> findings,
        Long existingSkillId,
        Integer existingVersion) {}
