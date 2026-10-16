-- V12__artifact_run_id.sql
-- 「产物挂到哪一轮」：Agent 通过官方 `deliverArtifact` 工具**主动交付**产物时，
-- 平台把当前 run 的 id 记下来。前端据此把产物展示在该轮 assistant 气泡下方
-- （消息侧的 agui_meta.runId 与之对应）。
--
-- 为什么需要它：artifact 原本只有 session_id + created_at，无法把产物精确归到某一轮；
-- 只能用时间窗口猜，容易错配（尤其在长工具调用/多轮快速连发时）。

ALTER TABLE artifact ADD COLUMN run_id VARCHAR(64);

-- 按 (会话, 轮次) 取该轮产物：气泡下方展示时的主查询
CREATE INDEX idx_artifact_session_run ON artifact (session_id, run_id);
