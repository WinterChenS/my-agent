package com.myagent.skillconfig.dto;

import java.util.List;

/**
 * 「从仓库导入 Skill」的请求。
 *
 * <p>两段式：
 * <ol>
 *   <li><b>预览</b>（{@code preview=true}，默认）：只下载 + 拆包 + 校验 + 安全检查，**不落库**，
 *       返回仓库里发现的每个技能，让用户挑；
 *   <li><b>安装</b>：{@code names} 指定要装的技能（为空白 = 装仓库里全部），带 {@code previewToken}
 *       时直接用预览过的内容落库，避免二次下载。
 * </ol>
 *
 * @param url 仓库链接（GitHub / GitLab / .zip 直链）
 * @param ref 可选分支或标签；为空时用链接里带的，再退到默认分支
 * @param preview 是否只预览
 * @param names 选中要安装的技能目录名（{@code root} 的最后一段）；空 = 全部
 * @param overwrite 同名 Skill 已存在时是否覆盖（默认 true，即「再导一次 = 更新」）
 * @param enable 安装后是否启用（默认 false：外部内容先停用，人工确认后再启用）
 * @param force 是否允许安装安全扫描为 DANGEROUS 的技能（默认 false；仅管理页提供，需人工看过命中项）
 */
public record SkillImportRequest(
        String url,
        String ref,
        Boolean preview,
        List<String> names,
        Boolean overwrite,
        Boolean enable,
        Boolean force) {

    /** 兼容旧调用（不带 force）。 */
    public SkillImportRequest(String url, String ref, Boolean preview, List<String> names,
            Boolean overwrite, Boolean enable) {
        this(url, ref, preview, names, overwrite, enable, false);
    }

    public boolean isPreview() {
        return preview == null || preview;
    }

    public boolean overwriteExisting() {
        return overwrite == null || overwrite;
    }

    public boolean enableInstalled() {
        return Boolean.TRUE.equals(enable);
    }

    public boolean forceInstall() {
        return Boolean.TRUE.equals(force);
    }
}
