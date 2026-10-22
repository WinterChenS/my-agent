package com.myagent.agui.skillimport;

import com.myagent.common.BizException;
import com.myagent.skillconfig.dto.SkillImportCandidate;
import com.myagent.skillconfig.dto.SkillImportRequest;
import com.myagent.skillconfig.dto.SkillImportResult;
import com.myagent.skillconfig.service.SkillImportService;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 聊天里安装标准 Skill 的工具：{@code install_skill(url)}。
 *
 * <p>与管理页的「从仓库导入」走**同一条**服务（{@link SkillImportService}），差别只在交互：
 *
 * <ul>
 *   <li>仓库里只有一个技能 → 直接装，返回装了什么；
 *   <li>仓库里有多个技能 → **不擅自挑**，返回候选清单，让模型问用户要装哪几个
 *       （用户可能只想要其中一个；实测多技能仓库很常见，例如 {@code anthropics/skills} 有 20 个）；
 *   <li>安全扫描 DANGEROUS → 拒绝并说明原因，不允许"装进去再说"。
 * </ul>
 *
 * <p>权限：本工具是写操作，Agent 的 {@code REQUIRE_APPROVAL} 模式会对它自动弹审批卡
 * （平台把 toolkit 里所有工具都加进了 ask 规则）。
 */
@Component
public class SkillInstallTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(SkillInstallTool.class);

    private final SkillImportService skillImportService;

    public SkillInstallTool(SkillImportService skillImportService) {
        this.skillImportService = skillImportService;
    }

    @Override
    public String getName() {
        return "install_skill";
    }

    @Override
    public String getDescription() {
        return "从仓库链接安装标准 Skill（SKILL.md + scripts/references/assets）到平台技能库。"
                + "用户给出 GitHub / GitLab 仓库地址或 .zip 链接、让你「装这个技能」时用它。"
                + "仓库里只有一个技能会直接安装；有多个技能时返回候选清单，先问用户要装哪一个（不要自己挑）。"
                + "参数 url 必填；names 可指定要装的技能目录名或技能包名（多个用数组）。"
                + "安全扫描判定为危险的仓库会被拒绝安装。安装成功后告诉用户装了什么、多少个资源文件。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> url = new LinkedHashMap<>();
        url.put("type", "string");
        url.put("description", "仓库链接，例如 https://github.com/anthropics/skills 或某个 .zip 直链");

        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("type", "string");
        ref.put("description", "可选：分支或标签（默认用仓库默认分支）");

        Map<String, Object> names = new LinkedHashMap<>();
        names.put("type", "array");
        names.put("items", Map.of("type", "string"));
        names.put("description", "可选：要安装的技能目录名（如 skills/pdf → pdf）；不传 = 仓库里只有一个技能时直接装它");

        Map<String, Object> enable = new LinkedHashMap<>();
        enable.put("type", "boolean");
        enable.put("description", "可选：装完是否立即启用（默认 true）。用户明确要「装好就能用」时保持默认");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("url", url);
        properties.put("ref", ref);
        properties.put("names", names);
        properties.put("enable", enable);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("url"));
        return schema;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String url = String.valueOf(param.getInput().getOrDefault("url", "")).trim();
        String ref = param.getInput().get("ref") == null ? null : String.valueOf(param.getInput().get("ref"));
        List<String> names = toStringList(param.getInput().get("names"));
        boolean enable = !(param.getInput().get("enable") instanceof Boolean b) || b;

        // 下载 + 解压 + 扫描是阻塞 IO：切到弹性线程池，别占住响应式线程
        return Mono.fromCallable(() -> run(url, ref, names, enable))
                .subscribeOn(Schedulers.boundedElastic())
                .map(text -> result(param, text))
                .onErrorResume(ex -> Mono.just(result(param, "安装失败：" + message(ex))));
    }

    private String run(String url, String ref, List<String> names, boolean enable) {
        if (url.isEmpty()) {
            throw BizException.badRequest("缺少 url");
        }
        if (names.isEmpty()) {
            // 先看仓库里有什么，避免把 20 个技能一股脑装进来
            SkillImportResult preview = skillImportService.preview(new SkillImportRequest(url, ref, true, List.of(), true, enable));
            if (preview.candidates().size() > 1) {
                return "该仓库包含 " + preview.candidates().size() + " 个技能，先问用户要装哪些，再带 names 重新调用：\n"
                        + describe(preview.candidates());
            }
        }
        SkillImportResult result = skillImportService.install(
                new SkillImportRequest(url, ref, false, names, true, enable));
        return describeInstalled(result);
    }

    private static String describe(List<SkillImportCandidate> candidates) {
        StringBuilder sb = new StringBuilder();
        for (SkillImportCandidate candidate : candidates) {
            sb.append("- ").append(candidate.name())
                    .append("（目录 ").append(candidate.root())
                    .append(candidate.pack() == null ? "" : "，包 " + candidate.pack())
                    .append("，")
                    .append(candidate.resourceCount()).append(" 个资源文件，扫描 ")
                    .append(candidate.verdict()).append("）：")
                    .append(abbreviate(candidate.description()))
                    .append('\n');
        }
        return sb.toString();
    }

    private static String describeInstalled(SkillImportResult result) {
        StringBuilder sb = new StringBuilder();
        if (!result.installed().isEmpty()) {
            sb.append("已安装 ").append(result.installed().size()).append(" 个技能：\n");
            for (SkillImportResult.Installed item : result.installed()) {
                sb.append("- ").append(item.name())
                        .append("（v").append(item.version()).append("，").append(item.resourceCount())
                        .append(" 个资源文件，").append("action=").append(item.action()).append("）\n");
            }
        }
        if (!result.skipped().isEmpty()) {
            sb.append("跳过：\n");
            for (SkillImportResult.Skipped item : result.skipped()) {
                sb.append("- ").append(item.name()).append("：").append(item.reason()).append('\n');
            }
        }
        if (result.installed().isEmpty() && result.skipped().isEmpty()) {
            sb.append("没有安装任何技能。");
        }
        return sb.toString().trim();
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim().replaceAll("\\s+", " ");
        return trimmed.length() <= 120 ? trimmed : trimmed.substring(0, 120) + "…";
    }

    private static List<String> toStringList(Object raw) {
        List<String> values = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    values.add(String.valueOf(item).trim());
                }
            }
        } else if (raw instanceof String text && !text.isBlank()) {
            values.add(text.trim());
        }
        return values;
    }

    private static String message(Throwable ex) {
        String text = ex.getMessage();
        if (text == null || text.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        log.info("install_skill 失败: {}", text);
        return text;
    }

    private static ToolResultBlock result(ToolCallParam param, String text) {
        return new ToolResultBlock(
                param.getToolUseBlock() == null ? null : param.getToolUseBlock().getId(),
                param.getToolUseBlock() == null ? null : param.getToolUseBlock().getName(),
                List.of(TextBlock.builder().text(text).build()),
                Map.of());
    }
}
