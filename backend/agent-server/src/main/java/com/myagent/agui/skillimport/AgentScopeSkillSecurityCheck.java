package com.myagent.agui.skillimport;

import com.myagent.skillconfig.importer.SkillScan;
import com.myagent.skillconfig.importer.SkillSecurityCheck;
import io.agentscope.harness.agent.skill.curator.SkillSecurityScanner;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 用 AgentScope 官方 {@link SkillSecurityScanner} 扫描导入的技能。
 *
 * <p>门禁 G8：规则集、严重度、结论（SAFE / CAUTION / DANGEROUS）全部来自官方实现，
 * 平台只做两件事：把结论转成可落库/可展示的结构，以及决定「什么结论算放行」。
 *
 * <p>放行策略：{@code SAFE} 直接装；{@code CAUTION} 允许（有命中但不致命，装完默认 DISABLED 等人工确认）；
 * {@code DANGEROUS}（注入 / 破坏性命令 / 外联管道执行）一律拒绝。
 */
@Component
public class AgentScopeSkillSecurityCheck implements SkillSecurityCheck {

    @Override
    public SkillScan scan(String name, String content, Map<String, String> resources) {
        // 只扫文本资源：二进制（base64:…）按字节解码后必然出现随机"可疑词"，实测字体文件被误判成
        // jailbreak 命中（canvas-design 的 ttf）。二进制本来也不该按文本规则判。
        Map<String, String> textResources = new java.util.LinkedHashMap<>();
        if (resources != null) {
            resources.forEach((path, value) -> {
                if (!value.startsWith("base64:")) {
                    textResources.put(path, value);
                }
            });
        }
        SkillSecurityScanner.ScanResult result =
                SkillSecurityScanner.scan(name, content == null ? "" : content, textResources);
        List<String> findings = new ArrayList<>();
        for (SkillSecurityScanner.Finding finding : result.findings()) {
            findings.add("[" + finding.severity() + "/" + finding.category() + "] "
                    + finding.file() + ":" + finding.line() + " — " + finding.description());
        }
        return new SkillScan(result.verdict().name(), findings);
    }

    @Override
    public boolean allows(SkillScan scan) {
        return scan != null && !"DANGEROUS".equalsIgnoreCase(scan.verdict());
    }

    @Override
    public String summarize(SkillScan scan, List<String> resourcePaths) {
        if (scan == null) {
            return "未扫描";
        }
        int resources = resourcePaths == null ? 0 : resourcePaths.size();
        if (scan.findings().isEmpty()) {
            return "安全扫描通过（" + resources + " 个资源文件）";
        }
        return "安全扫描 " + scan.verdict() + "：命中 " + scan.findings().size() + " 条（" + resources + " 个资源文件）";
    }
}
