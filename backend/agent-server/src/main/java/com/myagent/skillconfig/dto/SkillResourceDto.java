package com.myagent.skillconfig.dto;

/**
 * 技能的一个资源文件（渐进式披露的第二层）。
 *
 * <p>标准 Skill 是「两层」结构：SKILL.md 只写「什么时候用、怎么用、去哪儿找细节」，
 * 具体规则/脚本/数据放在 {@code references/}、{@code scripts/}、{@code data/} 里，
 * 模型按需用 {@code load_skill_through_path} 读。管理页要把这一层展示出来，
 * 否则用户会以为「导入只进来一个 markdown」。
 *
 * <p>列表接口只返回元信息（{@code content == null}）；点开某个文件时才返回正文。
 *
 * @param path 相对技能根目录的路径（{@code references/quick-reference.md}）
 * @param size 字符数（base64 资源为编码后的字符数）
 * @param binary 是否为二进制（以 {@code base64:} 前缀存储）
 * @param content 正文；列表接口为 null
 */
public record SkillResourceDto(String path, int size, boolean binary, String content) {

    public static SkillResourceDto meta(String path, int size, boolean binary) {
        return new SkillResourceDto(path, size, binary, null);
    }
}
