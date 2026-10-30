-- 登录滑动验证：一次一用、短 TTL、失败可重试（attempts 计数）。
-- 存库而不是存内存：多副本部署/后端重启都不会把正在登录的用户踢掉。
CREATE TABLE IF NOT EXISTS captcha_challenge (
    id         VARCHAR(40) PRIMARY KEY,
    answer_x   INT       NOT NULL,
    piece_y    INT       NOT NULL,
    verified   BOOLEAN   NOT NULL DEFAULT FALSE,
    ticket     VARCHAR(64),
    attempts   INT       NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_captcha_expires ON captcha_challenge (expires_at);
CREATE UNIQUE INDEX IF NOT EXISTS uq_captcha_ticket ON captcha_challenge (ticket) WHERE ticket IS NOT NULL;
