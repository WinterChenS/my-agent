-- tier1-upgrades ③：run 状态持久化。
--
-- 目的：让「上一轮跑到哪」在后端重启后仍然可见（用户刷新/重启不该只看到一片空白）。
-- 边界（spec 已声明）：**不做**分布式队列、不做跨进程续跑；这里只记录状态与结果摘要。
--
-- 与既有实现的关系：run 的实时态仍在内存（RunningRunRegistry），本表是它的**落库副本**：
--   beginRun  → 插一行 RUNNING
--   markFinished → 更新为 FINISHED / INTERRUPTED（用户停止）
--   启动时把残留的 RUNNING 归为 INTERRUPTED（进程被重启，这一轮不会自己接着跑）

CREATE TABLE agent_run (
    id           BIGSERIAL    PRIMARY KEY,
    thread_id    VARCHAR(128) NOT NULL,
    run_id       VARCHAR(128),
    session_id   BIGINT,
    -- RUNNING（进行中）/ FINISHED（已完成）/ INTERRUPTED（被停止）/ ERROR（出错）
    status       VARCHAR(16)  NOT NULL,
    started_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ended_at     TIMESTAMPTZ,
    -- 已生成文本的摘要（便于列表上直接看出"上一轮跑出了什么"）
    text_summary TEXT,
    error        TEXT,
    CONSTRAINT ck_agent_run_status
        CHECK (status IN ('RUNNING', 'FINISHED', 'INTERRUPTED', 'ERROR'))
);

-- 按会话查最近几轮
CREATE INDEX idx_agent_run_session ON agent_run (session_id, started_at DESC);
-- 按 thread 收尾 / 启动时归置残留
CREATE INDEX idx_agent_run_thread_status ON agent_run (thread_id, status);

COMMENT ON TABLE agent_run IS 'run 状态落库副本（tier1-upgrades ③）：重启后仍能看到上一轮状态';
