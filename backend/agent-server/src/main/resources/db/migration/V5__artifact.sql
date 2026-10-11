-- V5__artifact.sql
-- Phase 10：统一的产物/来源记录表（技术方案 §12.3）。
-- 一张表同时承载「用户上传的来源文件」(role=SOURCE) 与「Agent 产出的文件」(role=ARTIFACT)，
-- 用 storage 区分落地方式：文本类正文入库(DB)，其余写 MinIO。

CREATE TABLE artifact (
    id                 BIGSERIAL PRIMARY KEY,
    session_id         BIGINT       NOT NULL REFERENCES chat_session (id) ON DELETE CASCADE,
    user_id            BIGINT       NOT NULL REFERENCES sys_user (id),
    role               VARCHAR(16)  NOT NULL CHECK (role IN ('SOURCE', 'ARTIFACT')),
    storage            VARCHAR(16)  NOT NULL CHECK (storage IN ('MINIO', 'DB')),
    file_name          VARCHAR(512) NOT NULL,
    mime               VARCHAR(255),
    size_bytes         BIGINT       NOT NULL DEFAULT 0,
    sha256             VARCHAR(64),
    minio_bucket       VARCHAR(128),
    minio_object_key   VARCHAR(1024),
    content            TEXT,
    status             VARCHAR(16)  NOT NULL DEFAULT 'READY'
        CHECK (status IN ('PENDING', 'READY', 'FAILED')),
    error_message      VARCHAR(1024),
    source_message_id  BIGINT,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 会话内按角色列清单；sha256 用于去重查询
CREATE INDEX idx_artifact_session_role ON artifact (session_id, role, id);
CREATE INDEX idx_artifact_sha256       ON artifact (sha256);
