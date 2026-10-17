-- V13__cache_price.sql
-- 缓存命中价格（可选）+ 缓存命中 token 数。
--
-- 背景：带 prompt caching 的厂商会区分「缓存未命中 / 命中」两种输入价（命中通常便宜很多），
-- 用量里也会单独给出命中的 token 数。之前只按输入价算，命中部分被高估。

ALTER TABLE llm_model
    -- 每百万 token 的缓存命中价；为空表示"不区分"——命中部分仍按 price_input 计
    ADD COLUMN price_cache NUMERIC(14, 6);

ALTER TABLE token_usage
    -- 本次调用中命中缓存的输入 token 数（厂商不返回时为 0）
    ADD COLUMN cache_tokens INT NOT NULL DEFAULT 0;

-- 审计按日/按模型聚合时会用到
CREATE INDEX idx_token_usage_cache_tokens ON token_usage (user_id, created_at);
