-- V19__scheduled_run_cancelled.sql
-- Phase 21 追加：定时任务执行记录支持「已取消」
--
-- 用户在界面上取消一次正在跑的执行时，不能记成 FAILED：
-- 那会让「连续失败 3 次自动暂停」误伤（取消不是故障）。

ALTER TABLE scheduled_task_run
    DROP CONSTRAINT IF EXISTS scheduled_task_run_status_check;

ALTER TABLE scheduled_task_run
    ADD CONSTRAINT scheduled_task_run_status_check
        CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED', 'ABANDONED', 'CANCELLED'));

COMMENT ON COLUMN scheduled_task_run.status IS 'RUNNING / SUCCESS / FAILED / ABANDONED（节点崩溃）/ CANCELLED（用户取消）';
