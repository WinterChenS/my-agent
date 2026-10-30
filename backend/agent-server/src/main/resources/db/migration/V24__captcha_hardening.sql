-- 滑动验证安全加固：会话绑定 / 一次性消费 / 行为风险分 / 无障碍替代题（文字算术）
ALTER TABLE captcha_challenge ADD COLUMN IF NOT EXISTS session_key  VARCHAR(64);
ALTER TABLE captcha_challenge ADD COLUMN IF NOT EXISTS used         BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE captcha_challenge ADD COLUMN IF NOT EXISTS consumed_at  TIMESTAMP;
ALTER TABLE captcha_challenge ADD COLUMN IF NOT EXISTS risk_score   INT;
ALTER TABLE captcha_challenge ADD COLUMN IF NOT EXISTS mode         VARCHAR(16) NOT NULL DEFAULT 'SLIDER';
ALTER TABLE captcha_challenge ADD COLUMN IF NOT EXISTS text_answer  VARCHAR(32);

-- 按会话查/限流用
CREATE INDEX IF NOT EXISTS idx_captcha_session ON captcha_challenge (session_key);
