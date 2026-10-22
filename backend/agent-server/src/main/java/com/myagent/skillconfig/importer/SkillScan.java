package com.myagent.skillconfig.importer;

import java.util.List;

/**
 * 一个待安装技能的安全扫描结论（平台侧 DTO，不依赖 AgentScope 类型）。
 *
 * @param verdict {@code SAFE / CAUTION / DANGEROUS}
 * @param findings 命中项（人可读的一行一条）
 */
public record SkillScan(String verdict, List<String> findings) {

    public static SkillScan safe() {
        return new SkillScan("SAFE", List.of());
    }

    public SkillScan {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }
}
