-- V17__channel.sql
-- Phase 19：IM 渠道接入（钉钉优先，飞书/企业微信后续复用同一套表）
--
-- 设计要点：
-- 1) 渠道应用（channel_app）是"一个机器人 = 一个 Agent + 一套凭据"，凭据加密存 config_enc；
-- 2) 渠道会话（channel_peer）把 IM 的 peer（单聊的 staffId / 群聊的 conversationId）锚到平台
--    chat_session，Web 端能看到同一段历史（chat_session.origin 区分来源）；
-- 3) 入站事件去重与 access_token 缓存落库：官方提供了 InboundEventDeduplicator /
--    AccessTokenStore 两个 SPI，用数据库实现才能在多副本部署时不重复消费、不各拿一份 token。

CREATE TABLE channel_app (
    id             BIGSERIAL PRIMARY KEY,
    channel_type   VARCHAR(24)  NOT NULL CHECK (channel_type IN ('DINGTALK', 'FEISHU')),
    name           VARCHAR(128) NOT NULL,
    enabled        BOOLEAN      NOT NULL DEFAULT FALSE,
    agent_id       BIGINT       NOT NULL REFERENCES agent (id),
    -- 渠道会话的属主：工作区/记忆/产物按属主隔离，一个机器人对应一个平台账号
    owner_user_id  BIGINT       NOT NULL REFERENCES sys_user (id),
    -- AES-GCM 加密后的凭据 JSON（appKey/appSecret/robotCode/...）
    config_enc     TEXT         NOT NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'STOPPED'
        CHECK (status IN ('STOPPED', 'RUNNING', 'ERROR')),
    last_error     TEXT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON COLUMN channel_app.config_enc IS '渠道凭据（AES-GCM 加密的 JSON）';
COMMENT ON COLUMN channel_app.status IS '渠道运行状态：STOPPED/RUNNING/ERROR';

CREATE TABLE channel_peer (
    id               BIGSERIAL PRIMARY KEY,
    channel_app_id   BIGINT       NOT NULL REFERENCES channel_app (id) ON DELETE CASCADE,
    -- IM 侧会话标识：单聊=senderStaffId/openid，群聊=conversationId/chat_id
    peer_key         VARCHAR(256) NOT NULL,
    peer_kind        VARCHAR(16)  NOT NULL CHECK (peer_kind IN ('DIRECT', 'GROUP')),
    peer_name        VARCHAR(128),
    -- 主动推送要用的地址（OutboundAddress 的 JSON 快照）
    outbound_address TEXT,
    chat_session_id  BIGINT       REFERENCES chat_session (id) ON DELETE SET NULL,
    last_active_at   TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_channel_peer UNIQUE (channel_app_id, peer_key)
);

COMMENT ON TABLE channel_peer IS 'IM 会话 ↔ 平台 chat_session 的映射；同时保存主动推送地址';

-- 官方 InboundEventDeduplicator 的持久化实现（钉钉/飞书回调可能重复推送同一条消息）
CREATE TABLE channel_event_seen (
    channel_app_id BIGINT       NOT NULL REFERENCES channel_app (id) ON DELETE CASCADE,
    event_key      VARCHAR(256) NOT NULL,
    seen_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (channel_app_id, event_key)
);

-- 官方 AccessTokenStore 的持久化实现（多副本共享 token，避免互相顶掉）
CREATE TABLE channel_access_token (
    channel_app_id BIGINT      PRIMARY KEY REFERENCES channel_app (id) ON DELETE CASCADE,
    token_value    TEXT        NOT NULL,
    refresh_at_ms  BIGINT      NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 会话来源：Web 端聊天列表要能区分「这个会话是钉钉来的」
ALTER TABLE chat_session ADD COLUMN origin VARCHAR(16) NOT NULL DEFAULT 'WEB';
ALTER TABLE chat_session ADD COLUMN origin_ref VARCHAR(256);
COMMENT ON COLUMN chat_session.origin IS '会话来源：WEB / DINGTALK / FEISHU';
COMMENT ON COLUMN chat_session.origin_ref IS '来源标识（渠道应用名 + peer），仅用于展示';

CREATE INDEX idx_chat_session_origin ON chat_session (origin);
