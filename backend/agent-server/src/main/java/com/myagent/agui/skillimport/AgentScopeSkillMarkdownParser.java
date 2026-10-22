package com.myagent.agui.skillimport;

import com.myagent.common.BizException;
import com.myagent.skillconfig.importer.SkillMarkdownParser;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.util.SkillUtil;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 用 AgentScope 官方 {@link SkillUtil#createFrom} 解析标准 SKILL.md。
 *
 * <p>门禁 G8：frontmatter 的解析、必填校验（name / description）、正文非空校验全部交给官方实现，
 * 平台只负责把异常翻译成 400 文案 —— 自己写一套 YAML 解析必然与框架漂移。
 */
@Component
public class AgentScopeSkillMarkdownParser implements SkillMarkdownParser {

    @Override
    public Parsed parse(String markdown, Map<String, String> resources, String source) {
        if (markdown == null || markdown.isBlank()) {
            throw BizException.badRequest("SKILL.md 内容为空");
        }
        AgentSkill skill;
        try {
            skill = SkillUtil.createFrom(markdown, resources == null ? Map.of() : resources, source);
        } catch (IllegalArgumentException ex) {
            // 官方抛的是 "The SKILL.md must have a YAML Front Matter including `name` and `description` fields."
            throw BizException.badRequest("SKILL.md 不符合标准格式：" + ex.getMessage());
        }
        return new Parsed(skill.getName(), skill.getDescription(), skill.getSkillContent());
    }
}
