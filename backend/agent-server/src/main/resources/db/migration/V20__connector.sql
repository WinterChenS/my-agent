-- 连接器（Connector）：让 Agent 使用外部系统账号（首个类型：邮件）。
--
-- 设计要点：
-- 1) 一个「连接器」= 一个外部账号实例，**归属到用户**（每个人只能用自己的邮箱）；
-- 2) 绑定关系是 agent ↔ connector：只有绑定过该类型连接器的 Agent 才会注册对应工具；
-- 3) 具体访问哪个账号在**调用时**按当前对话用户解析，因此多人用同一个 Agent 也不会串号；
-- 4) 凭据用 CryptoService(AES-GCM) 加密存 config_enc，界面只回显掩码。

CREATE TABLE connector (
    id              BIGSERIAL PRIMARY KEY,
    type            VARCHAR(32)  NOT NULL,
    name            VARCHAR(64)  NOT NULL,
    alias           VARCHAR(24),
    owner_user_id   BIGINT       NOT NULL REFERENCES sys_user (id) ON DELETE CASCADE,
    config_enc      TEXT         NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    -- 最近一次连通性探测结果：UNKNOWN / OK / ERROR
    status          VARCHAR(16)  NOT NULL DEFAULT 'UNKNOWN'
        CHECK (status IN ('UNKNOWN', 'OK', 'ERROR')),
    last_error      TEXT,
    last_checked_at TIMESTAMPTZ,
    -- 入站（P1）：OFF 只记录 / NOTIFY 推 IM / AUTOREPLY 交给 Agent 自动回复
    inbound_mode    VARCHAR(16)  NOT NULL DEFAULT 'OFF'
        CHECK (inbound_mode IN ('OFF', 'NOTIFY', 'AUTOREPLY')),
    -- IMAP 增量拉取游标：UIDVALIDITY 变化时重置 last_uid
    uid_validity    BIGINT,
    last_uid        BIGINT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT connector_type_ck  CHECK (type IN ('MAIL')),
    CONSTRAINT connector_alias_ck CHECK (alias IS NULL OR alias ~ '^[a-z][a-z0-9_]{1,23}$')
);
CREATE UNIQUE INDEX connector_owner_type_name_uk ON connector (owner_user_id, type, name);
CREATE INDEX connector_owner_ix ON connector (owner_user_id);

-- Agent 绑定（与 agent_skill / agent_mcp_server 同构）
CREATE TABLE agent_connector (
    agent_id     BIGINT NOT NULL REFERENCES agent (id) ON DELETE CASCADE,
    connector_id BIGINT NOT NULL REFERENCES connector (id) ON DELETE CASCADE,
    PRIMARY KEY (agent_id, connector_id)
);

-- 写操作审计：发信 / 回复 / 标记 / 删除。发出去的东西必须可追溯。
CREATE TABLE connector_action_log (
    id           BIGSERIAL PRIMARY KEY,
    connector_id BIGINT      NOT NULL REFERENCES connector (id) ON DELETE CASCADE,
    user_id      BIGINT,
    action       VARCHAR(24) NOT NULL,
    target       TEXT,
    subject      TEXT,
    success      BOOLEAN     NOT NULL,
    error        TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX connector_action_log_conn_ix ON connector_action_log (connector_id, created_at DESC);

-- 入站邮件事件（P1）：按 Message-ID 去重，记录处理结果与对应会话/运行
CREATE TABLE connector_inbound_event (
    id            BIGSERIAL PRIMARY KEY,
    connector_id  BIGINT      NOT NULL REFERENCES connector (id) ON DELETE CASCADE,
    external_id   TEXT        NOT NULL,
    thread_key    TEXT,
    from_addr     TEXT,
    subject       TEXT,
    status        VARCHAR(16) NOT NULL DEFAULT 'NEW'
        CHECK (status IN ('NEW', 'HANDLED', 'SKIPPED', 'FAILED')),
    skip_reason   TEXT,
    run_id        VARCHAR(64),
    error         TEXT,
    received_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    handled_at    TIMESTAMPTZ,
    CONSTRAINT connector_inbound_event_uk UNIQUE (connector_id, external_id)
);
CREATE INDEX connector_inbound_event_status_ix ON connector_inbound_event (status, received_at);

-- 入站拉取用「谁在处理这个连接器」的租约，避免多节点重复拉同一批邮件
ALTER TABLE connector ADD COLUMN lease_owner VARCHAR(64);
ALTER TABLE connector ADD COLUMN lease_until TIMESTAMPTZ;
