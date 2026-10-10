-- V2__seed.sql
-- 初始数据：管理员账号 + 一条 DISABLED 的示例 provider/model。
-- admin 初始口令 Admin@123（BCrypt 哈希，首次登录后请修改）。

INSERT INTO sys_user (username, password_hash, display_name, role, status)
VALUES ('admin',
        '$2a$10$6E7k19rrdjfhqJZdMuJGFeyNfFRDuWxsAQRpZ5jjxgZ9YdisyHs26',
        '平台管理员',
        'ADMIN',
        'ENABLED');

INSERT INTO llm_provider (name, provider_type, base_url, api_key_cipher, status)
VALUES ('示例供应商（OpenAI 兼容）', 'OPENAI_COMPATIBLE', 'https://api.openai.com/v1', NULL, 'DISABLED');

INSERT INTO llm_model (provider_id, model_id, display_name, status)
SELECT id, 'gpt-4o-mini', '示例模型', 'DISABLED'
FROM llm_provider
WHERE name = '示例供应商（OpenAI 兼容）';
