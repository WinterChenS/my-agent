-- ===== 标准 Skill 从仓库导入 =====
-- 背景：此前 skill 表只有「name / description / content」三段，标准 skill（SKILL.md + scripts / references /
-- assets）里的资源文件无处可放，同步到 AgentScope 官方仓库时会被丢掉（实测官方仓库的
-- agentscope_skill_resources 一直是 0 行）。
--
-- 这里补三件事：
--   1) 记来源：source_url / source_ref，用于「同一个仓库再导一次 = 更新」与溯源展示；
--   2) 记内容指纹：content_hash（name + 正文 + 资源 的 SHA-256），资源没变就不必刷版本号；
--   3) skill_resource 表：与官方 agentscope_skill_resources 同构（path → 内容），
--      二进制文件按官方的 base64: 前缀约定存文本。

ALTER TABLE skill ADD COLUMN source_url VARCHAR(512);
ALTER TABLE skill ADD COLUMN source_ref VARCHAR(128);
ALTER TABLE skill ADD COLUMN content_hash VARCHAR(64);

COMMENT ON COLUMN skill.source_url IS 'Skill 的导入来源（仓库地址），为空表示手工创建';
COMMENT ON COLUMN skill.source_ref IS '导入来源的分支 / 标签';
COMMENT ON COLUMN skill.content_hash IS 'name+正文+资源的 SHA-256，用于判断内容是否真的变了';

-- 资源文件（scripts/ references/ templates/ assets/ 等）。
-- 主键 (skill_id, resource_path)：同一 Skill 内路径唯一；删除 Skill 时级联清理。
CREATE TABLE skill_resource (
    skill_id         BIGINT       NOT NULL REFERENCES skill (id) ON DELETE CASCADE,
    resource_path    VARCHAR(500) NOT NULL,
    resource_content TEXT         NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (skill_id, resource_path)
);

COMMENT ON TABLE skill_resource IS 'Skill 的资源文件；同步到 AgentScope 官方仓库的 agentscope_skill_resources';
