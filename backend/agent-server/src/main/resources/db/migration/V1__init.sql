-- V1__init.sql
-- my-agent 平台初始 schema。门禁 G4：DDL 只允许出现在 src/main/resources/db/migration/。

-- ===== 用户 =====
CREATE TABLE sys_user (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    display_name  VARCHAR(128),
    role          VARCHAR(16)  NOT NULL DEFAULT 'USER'    CHECK (role   IN ('ADMIN', 'USER')),
    status        VARCHAR(16)  NOT NULL DEFAULT 'ENABLED' CHECK (status IN ('ENABLED', 'DISABLED')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ===== 大模型配置 =====
CREATE TABLE llm_provider (
    id             BIGSERIAL PRIMARY KEY,
    name           VARCHAR(128) NOT NULL UNIQUE,
    provider_type  VARCHAR(32)  NOT NULL
        CHECK (provider_type IN ('OPENAI', 'OPENAI_COMPATIBLE', 'DASHSCOPE', 'ANTHROPIC', 'GEMINI', 'OLLAMA')),
    base_url       VARCHAR(512),
    api_key_cipher TEXT,
    status         VARCHAR(16)  NOT NULL DEFAULT 'ENABLED' CHECK (status IN ('ENABLED', 'DISABLED')),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE llm_model (
    id           BIGSERIAL PRIMARY KEY,
    provider_id  BIGINT       NOT NULL REFERENCES llm_provider (id),
    model_id     VARCHAR(128) NOT NULL,
    display_name VARCHAR(128),
    params_json  JSONB        NOT NULL DEFAULT '{}'::jsonb,
    status       VARCHAR(16)  NOT NULL DEFAULT 'ENABLED' CHECK (status IN ('ENABLED', 'DISABLED')),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (provider_id, model_id)
);

-- ===== Skill =====
CREATE TABLE skill (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(128) NOT NULL UNIQUE,
    description VARCHAR(512),
    content     TEXT,
    version     INT          NOT NULL DEFAULT 1,
    status      VARCHAR(16)  NOT NULL DEFAULT 'ENABLED' CHECK (status IN ('ENABLED', 'DISABLED')),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ===== MCP =====
CREATE TABLE mcp_server (
    id             BIGSERIAL PRIMARY KEY,
    name           VARCHAR(128) NOT NULL UNIQUE,
    transport      VARCHAR(24)  NOT NULL CHECK (transport IN ('STDIO', 'SSE', 'STREAMABLE_HTTP')),
    endpoint_url   VARCHAR(512),
    command        VARCHAR(512),
    args_json      JSONB        NOT NULL DEFAULT '[]'::jsonb,
    env_json       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    headers_json   JSONB        NOT NULL DEFAULT '{}'::jsonb,
    tool_cache     JSONB,
    last_synced_at TIMESTAMPTZ,
    status         VARCHAR(16)  NOT NULL DEFAULT 'ENABLED' CHECK (status IN ('ENABLED', 'DISABLED')),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ===== Agent =====
CREATE TABLE agent (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(128) NOT NULL UNIQUE,
    agent_id        VARCHAR(128) NOT NULL UNIQUE,
    sys_prompt      TEXT,
    model_id        BIGINT REFERENCES llm_model (id),
    temperature     NUMERIC(3, 2),
    max_tokens      INT,
    permission_mode VARCHAR(24)  NOT NULL DEFAULT 'ALWAYS_ALLOW'
        CHECK (permission_mode IN ('ALWAYS_ALLOW', 'ALWAYS_DENY', 'REQUIRE_APPROVAL')),
    max_iterations  INT          NOT NULL DEFAULT 20,
    status          VARCHAR(16)  NOT NULL DEFAULT 'ENABLED' CHECK (status IN ('ENABLED', 'DISABLED')),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 删除 Agent 时清理其绑定；但被 Agent 绑定的 Skill / MCP Server 不允许被删除（服务层也会校验）。
CREATE TABLE agent_skill (
    agent_id BIGINT NOT NULL REFERENCES agent (id) ON DELETE CASCADE,
    skill_id BIGINT NOT NULL REFERENCES skill (id),
    PRIMARY KEY (agent_id, skill_id)
);

CREATE TABLE agent_mcp_server (
    agent_id      BIGINT NOT NULL REFERENCES agent (id) ON DELETE CASCADE,
    mcp_server_id BIGINT NOT NULL REFERENCES mcp_server (id),
    PRIMARY KEY (agent_id, mcp_server_id)
);

-- ===== 会话与消息 =====
CREATE TABLE chat_session (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT       NOT NULL REFERENCES sys_user (id),
    agent_id       BIGINT REFERENCES agent (id),
    title          VARCHAR(256),
    last_active_at TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE chat_message (
    id         BIGSERIAL PRIMARY KEY,
    session_id BIGINT      NOT NULL REFERENCES chat_session (id) ON DELETE CASCADE,
    role       VARCHAR(16) NOT NULL CHECK (role IN ('USER', 'ASSISTANT', 'TOOL', 'SYSTEM')),
    content    TEXT,
    agui_meta  JSONB,
    seq        INT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ===== Token 审计 =====
CREATE TABLE token_usage (
    id                BIGSERIAL PRIMARY KEY,
    user_id           BIGINT REFERENCES sys_user (id),
    session_id        BIGINT,
    agent_id          BIGINT,
    model_id          BIGINT,
    provider_type     VARCHAR(32),
    prompt_tokens     INT         NOT NULL DEFAULT 0,
    completion_tokens INT         NOT NULL DEFAULT 0,
    total_tokens      INT         NOT NULL DEFAULT 0,
    request_id        VARCHAR(64),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ===== 审批 =====
CREATE TABLE approval_record (
    id         BIGSERIAL PRIMARY KEY,
    session_id BIGINT      NOT NULL REFERENCES chat_session (id) ON DELETE CASCADE,
    request_id VARCHAR(64),
    tool_name  VARCHAR(256),
    args_json  JSONB,
    status     VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'APPROVED', 'DENIED', 'EXPIRED')),
    decided_by BIGINT,
    decided_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ===== 索引 =====
CREATE INDEX idx_llm_model_provider       ON llm_model (provider_id);
CREATE INDEX idx_agent_model              ON agent (model_id);
CREATE INDEX idx_chat_session_user        ON chat_session (user_id);
CREATE INDEX idx_chat_message_session     ON chat_message (session_id, seq);
CREATE INDEX idx_token_usage_user_created ON token_usage (user_id, created_at);
CREATE INDEX idx_token_usage_request      ON token_usage (request_id);
CREATE INDEX idx_approval_session         ON approval_record (session_id, status);
