package com.myagent.skillconfig.importer;

import java.util.List;
import java.util.Map;

/**
 * 技能安全扫描（外部仓库内容 = 外部提示词 + 外部脚本，落库前必须过一遍）。
 *
 * <p>接口在 {@code skillconfig} 包、实现放 {@code agui} 包（门禁 G2）：扫描用 AgentScope 官方
 * {@code SkillSecurityScanner}，不自己维护规则集（门禁 G8）。
 */
public interface SkillSecurityCheck {

    /** 扫描技能正文 + 资源。 */
    SkillScan scan(String name, String content, Map<String, String> resources);

    /** 扫描结果是否允许直接安装（DANGEROUS 一律拦下）。 */
    boolean allows(SkillScan scan);

    /** 把多条命中压成一句给用户看的话。 */
    String summarize(SkillScan scan, List<String> resourcePaths);
}
