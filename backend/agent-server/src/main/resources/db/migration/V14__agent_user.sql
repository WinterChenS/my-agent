-- V14__agent_user.sql
-- 按资源的授权：让某个 Agent **只给指定用户使用**（用户反馈"没有『这个 Agent 只给某些用户用』的概念"）。
--
-- 语义：**没有记录 = 对所有人开放**（向后兼容，现有 Agent 不受影响）；
-- 一旦给某个 Agent 配了至少一条记录，就只有这些用户 + ADMIN 能用它。

CREATE TABLE agent_user (
    agent_id   BIGINT      NOT NULL REFERENCES agent (id) ON DELETE CASCADE,
    user_id    BIGINT      NOT NULL REFERENCES sys_user (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (agent_id, user_id)
);

-- 反查"这个用户能用哪些 Agent"（非管理员列 Agent 时按它过滤）
CREATE INDEX idx_agent_user_user ON agent_user (user_id);
