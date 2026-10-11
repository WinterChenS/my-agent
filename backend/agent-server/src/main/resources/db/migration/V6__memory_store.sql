-- Phase 11 / Task 11.1：记忆与会话状态入库（D23）
-- 说明：两张表分别承载
--   1) agent_state  —— 官方 AgentStateStore 的 PostgreSQL 实现（框架会话状态）
--   2) memory_store —— 官方 BaseStore 的 PostgreSQL 实现（经官方 RemoteFilesystem 承载长期记忆）
-- 均只替换官方 SPI 的存储后端，不改框架记忆/状态逻辑。

CREATE TABLE agent_state (
    id         BIGSERIAL   PRIMARY KEY,
    agent_id   VARCHAR(128) NOT NULL,
    session_id VARCHAR(128) NOT NULL,
    state_key  VARCHAR(200) NOT NULL,
    -- SINGLE：单值状态；LIST：同一 key 下的列表状态（按 ordinal 排序）
    kind       VARCHAR(16)  NOT NULL,
    ordinal    INTEGER      NOT NULL DEFAULT 0,
    payload    JSONB        NOT NULL,
    version    BIGINT       NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_agent_state_key
    ON agent_state (agent_id, session_id, kind, state_key, ordinal);
CREATE INDEX idx_agent_state_session
    ON agent_state (agent_id, session_id);

CREATE TABLE memory_store (
    id             BIGSERIAL    PRIMARY KEY,
    -- 命名空间（由官方 NamespaceFactory 按 RuntimeContext 生成，用 / 连接）
    namespace_path VARCHAR(300) NOT NULL,
    item_key       VARCHAR(300) NOT NULL,
    payload        JSONB        NOT NULL,
    version        BIGINT       NOT NULL DEFAULT 1,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_memory_store_key
    ON memory_store (namespace_path, item_key);
