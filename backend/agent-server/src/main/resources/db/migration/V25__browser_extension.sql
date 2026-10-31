-- 本机浏览器扩展（方案：docs/superpowers/specs/2026-10-31-local-browser-extension-design.md）
--
-- 设计要点：
--  * 扩展是**设备**，不是网页登录态。设备凭证与网页 JWT 完全分离，scope 仅限浏览器控制。
--  * 配对码 / refresh token 只存摘要（sha256），明文只在响应里出现一次。
--  * 控制会话绑定 conversation + device + 单个标签页；lease_version 用于「暂停后旧命令全部失效」。

create table if not exists browser_device (
    id                 varchar(32)  primary key,
    user_id            bigint       not null,
    install_id         varchar(64)  not null,
    display_name       varchar(120),
    browser            varchar(32),
    extension_version  varchar(32),
    protocol_version   integer      not null default 1,
    credential_version integer      not null default 1,
    status             varchar(16)  not null default 'ACTIVE',
    created_at         timestamptz  not null default now(),
    last_seen_at       timestamptz,
    revoked_at         timestamptz
);
create unique index if not exists uk_browser_device_install on browser_device (install_id);
create index if not exists idx_browser_device_user on browser_device (user_id);

create table if not exists browser_pairing (
    id               varchar(32) primary key,
    device_code_hash varchar(64) not null,
    user_code_hash   varchar(64) not null,
    pkce_challenge   varchar(64) not null,
    install_id       varchar(64) not null,
    display_name     varchar(120),
    browser          varchar(32),
    status           varchar(16) not null default 'PENDING',
    approved_by      bigint,
    approved_at      timestamptz,
    consumed_at      timestamptz,
    attempt_count    integer     not null default 0,
    expires_at       timestamptz not null,
    created_at       timestamptz not null default now()
);
create unique index if not exists uk_browser_pairing_device_code on browser_pairing (device_code_hash);
create index if not exists idx_browser_pairing_user_code on browser_pairing (user_code_hash);

create table if not exists browser_device_credential (
    id               varchar(32) primary key,
    device_id        varchar(32) not null,
    refresh_hash     varchar(64) not null,
    token_family     varchar(32) not null,
    rotation_version integer     not null default 1,
    expires_at       timestamptz not null,
    consumed_at      timestamptz,
    revoked_at       timestamptz,
    created_at       timestamptz not null default now()
);
create unique index if not exists uk_browser_credential_refresh on browser_device_credential (refresh_hash);
create index if not exists idx_browser_credential_device on browser_device_credential (device_id);

create table if not exists browser_control_session (
    id              varchar(32)  primary key,
    user_id         bigint       not null,
    conversation_id varchar(64),
    device_id       varchar(32)  not null,
    tab_handle      varchar(64),
    origin          varchar(255),
    title           varchar(255),
    page_revision   integer      not null default 0,
    state           varchar(24)  not null,
    lease_version   integer      not null default 1,
    last_error      varchar(255),
    created_at      timestamptz  not null default now(),
    last_active_at  timestamptz  not null default now(),
    expires_at      timestamptz  not null,
    ended_at        timestamptz
);
create index if not exists idx_browser_session_user on browser_control_session (user_id, created_at desc);
create index if not exists idx_browser_session_conversation on browser_control_session (conversation_id);

create table if not exists browser_action_audit (
    id          bigserial   primary key,
    request_id  varchar(48) not null,
    session_id  varchar(32),
    user_id     bigint,
    device_id   varchar(32),
    action      varchar(32) not null,
    origin      varchar(255),
    risk        varchar(16),
    status      varchar(16) not null,
    error_code  varchar(32),
    duration_ms integer,
    created_at  timestamptz not null default now()
);
create index if not exists idx_browser_audit_session on browser_action_audit (session_id, created_at desc);
