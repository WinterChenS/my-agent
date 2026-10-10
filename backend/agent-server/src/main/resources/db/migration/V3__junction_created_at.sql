-- V3__junction_created_at.sql
-- 让所有业务表都带 created_at（任务列表 Task 1.1 验收门禁）。关联表补建绑定时间。

ALTER TABLE agent_skill       ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE agent_mcp_server  ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();
