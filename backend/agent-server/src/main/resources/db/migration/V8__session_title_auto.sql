-- Phase 17：会话标题自动生成（类似 ChatGPT）
--
-- 现状：chat_session.title 是「会话 <threadId 前 8 位>」这种占位符，列表里完全看不出聊了什么。
-- 改为：首条用户消息后由该会话的模型生成一个短标题；手工指定/改名过的标题不再被覆盖。
--
-- title_auto：true = 当前标题是自动生成/占位的（可以被自动标题覆盖）；
--             false = 用户或调用方显式指定过（自动流程不得覆盖）。

ALTER TABLE chat_session
    ADD COLUMN title_auto BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN chat_session.title_auto IS '标题是否可被自动生成覆盖（false=人工指定）';

-- 历史数据：形如「会话 xxxxxxxx」的标题视为占位符（可自动覆盖），其余视为人工指定
UPDATE chat_session
SET title_auto = CASE
    WHEN title IS NULL OR title = '' THEN TRUE
    WHEN title ~ '^会话 [0-9a-f]{8}$' THEN TRUE
    ELSE FALSE
END;
