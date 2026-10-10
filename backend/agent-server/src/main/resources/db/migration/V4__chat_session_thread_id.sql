-- V4__chat_session_thread_id.sql
-- AG-UI 的 threadId 需要与平台会话一一对应（技术方案第 4 节“按 threadId 定位或创建 chat_session”）。
-- 原 DDL 未含该列，这里补齐：同一用户下 thread_id 唯一。

ALTER TABLE chat_session ADD COLUMN thread_id VARCHAR(128);

CREATE UNIQUE INDEX uk_chat_session_user_thread ON chat_session (user_id, thread_id);
