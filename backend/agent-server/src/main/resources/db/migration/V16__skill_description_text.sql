-- ===== Skill 说明放宽为 TEXT =====
-- 从仓库导入的标准 Skill，description 常远超 512 字符（实测 nextlevelbuilder/ui-ux-pro-max-skill
-- 的 ui-ux-pro-max 有 900+ 字符，写了完整的触发场景）。
--
-- 这段文字是模型判断「什么时候用这个技能」的唯一依据，截断会直接降低命中率；
-- 官方 agentscope.agentscope_skills.description 本身就是 TEXT，平台侧没必要更窄。

ALTER TABLE skill ALTER COLUMN description TYPE TEXT;
