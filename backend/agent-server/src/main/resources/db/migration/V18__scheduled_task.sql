-- V18__scheduled_task.sql
-- Phase 21：定时任务（分布式）
--
-- 设计要点：
-- 1) 任务定义（agent / prompt / 计划 / 推送目标）是事实来源，落库；
--    不用 Quartz 之类把 agent 配置塞进 JobDataMap——我们的 agent 靠 AgentFactory 从库里建，
--    跨节点必须能重建，所以任务行只存「agent_id + prompt + 计划」。
-- 2) 多副本安全靠 PostgreSQL 的 SELECT ... FOR UPDATE SKIP LOCKED 抢占：
--    谁抢到谁跑，绝不会重复。不引入任何需要额外部署的组件。
-- 3) lease_until 保证「同一任务不并发」；过期自动回收（节点崩溃/重启）。

CREATE TABLE scheduled_task (
    id                    BIGSERIAL PRIMARY KEY,
    name                  VARCHAR(128) NOT NULL,
    enabled               BOOLEAN      NOT NULL DEFAULT TRUE,
    -- 执行者：复用 AgentFactory，技能/MCP/记忆/工具/工作区全部继承
    agent_id              BIGINT       NOT NULL REFERENCES agent (id),
    created_by_user_id    BIGINT       NOT NULL REFERENCES sys_user (id),

    -- 计划：CRON（cron_expr）/ EVERY_MINUTES（interval_minutes）/ ONCE（once_at）
    schedule_type         VARCHAR(16)  NOT NULL
        CHECK (schedule_type IN ('CRON', 'EVERY_MINUTES', 'ONCE')),
    cron_expr             VARCHAR(128),
    interval_minutes      INT,
    once_at               TIMESTAMPTZ,
    timezone              VARCHAR(64)  NOT NULL DEFAULT 'Asia/Shanghai',

    -- 定时喂给 agent 的话（这就是"任务内容"）
    prompt                TEXT         NOT NULL,

    -- 推送目标：SESSION（写回会话）/ IM（推到钉钉等）/ NONE（只记录）
    target_type           VARCHAR(16)  NOT NULL DEFAULT 'SESSION'
        CHECK (target_type IN ('SESSION', 'IM', 'NONE')),
    -- 运行用的会话（决定 threadId → 记忆/上下文连续）。空则新建一个
    chat_session_id       BIGINT REFERENCES chat_session (id) ON DELETE SET NULL,
    -- 显式绑定的 IM 目标（不填则运行时用 chat_session 对应的 peer）
    channel_app_id        BIGINT REFERENCES channel_app (id) ON DELETE SET NULL,
    peer_key              VARCHAR(256),

    status                VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'PAUSED', 'ERROR', 'DONE')),
    -- 错过触发后的处理：COALESCE=立即补跑一次；SKIP=直接跳到下一个周期
    misfire_policy        VARCHAR(16)  NOT NULL DEFAULT 'COALESCE'
        CHECK (misfire_policy IN ('COALESCE', 'SKIP')),

    next_run_at           TIMESTAMPTZ,
    last_run_at           TIMESTAMPTZ,
    last_error            TEXT,
    run_count             INT          NOT NULL DEFAULT 0,
    consecutive_failures  INT          NOT NULL DEFAULT 0,
    -- 执行租约：未过期表示有节点正在跑，别人不要抢；过期视为节点挂了，可回收
    lease_until           TIMESTAMPTZ,

    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE scheduled_task IS '定时任务定义（分布式抢占执行）';
COMMENT ON COLUMN scheduled_task.prompt IS '定时执行时喂给 agent 的指令';
COMMENT ON COLUMN scheduled_task.lease_until IS '执行租约，防同任务并发；过期可回收';

-- tick 的主查询：只扫"该跑且没被租"的行
CREATE INDEX idx_scheduled_task_due ON scheduled_task (next_run_at)
    WHERE status = 'ACTIVE' AND enabled;
CREATE INDEX idx_scheduled_task_agent ON scheduled_task (agent_id);
CREATE INDEX idx_scheduled_task_session ON scheduled_task (chat_session_id);

CREATE TABLE scheduled_task_run (
    id              BIGSERIAL PRIMARY KEY,
    task_id         BIGINT       NOT NULL REFERENCES scheduled_task (id) ON DELETE CASCADE,
    node_id         VARCHAR(64)  NOT NULL,
    -- CRON / MANUAL（手动"立即执行"）/ COALESCE（补跑）
    trigger_type    VARCHAR(16)  NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'RUNNING'
        CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED', 'ABANDONED')),
    started_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ,
    duration_ms     BIGINT,
    output_preview  TEXT,
    error           TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_scheduled_task_run_task ON scheduled_task_run (task_id, started_at DESC);
CREATE INDEX idx_scheduled_task_run_status ON scheduled_task_run (status) WHERE status = 'RUNNING';
