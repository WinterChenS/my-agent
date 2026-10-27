package com.myagent.skillconfig.dto;

import java.util.List;

/**
 * 导入结果：预览时给出全部候选；安装时给出每个技能的处理结果。
 *
 * @param downloadUrl 实际下载的地址（GitHub 仓库链接会被换成 codeload，便于排查 404）
 * @param ref 实际使用的分支 / 标签
 * @param preview 本次是否为预览
 * @param candidates 仓库里发现的技能
 * @param installed 本次安装/更新的技能（预览时为空）
 * @param skipped 本次跳过的技能及原因（预览时为空）
 * @param notes 仓库层面的提示（如「多份同名技能，只取其中一份」）
 */
public record SkillImportResult(
        String downloadUrl,
        String ref,
        boolean preview,
        List<SkillImportCandidate> candidates,
        List<Installed> installed,
        List<Skipped> skipped,
        List<String> notes) {

    public SkillImportResult(
            String downloadUrl,
            String ref,
            boolean preview,
            List<SkillImportCandidate> candidates,
            List<Installed> installed,
            List<Skipped> skipped) {
        this(downloadUrl, ref, preview, candidates, installed, skipped, List.of());
    }

    /** 已落库的技能。 */
    public record Installed(Long id, String name, String root, Integer version, String action, int resourceCount) {}

    /** 跳过的技能。 */
    public record Skipped(String name, String root, String reason) {}
}
