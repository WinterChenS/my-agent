-- Phase 15：大模型配置补齐（温度 / Top-N / 上下文长度 / 输出长度 / 每百万 token 价格）
--
-- 背景：原先这些参数挤在 params_json 里，且只有 temperature/maxTokens 部分生效；
-- 上下文长度与价格完全没有，导致：上下文治理无法按模型窗口设定、审计看板算不出成本。
-- 本迁移把它们提升为**显式列**（便于校验、查询与聚合），并从 params_json 回填历史值。

ALTER TABLE llm_model
    -- 采样参数
    ADD COLUMN temperature      NUMERIC(4, 3),
    ADD COLUMN top_p            NUMERIC(4, 3),
    -- 窗口与输出长度（token）
    ADD COLUMN context_window   INTEGER,
    ADD COLUMN max_tokens       INTEGER,
    -- 价格：每百万 token，含货币（CNY / USD）
    ADD COLUMN price_input      NUMERIC(14, 6),
    ADD COLUMN price_output     NUMERIC(14, 6),
    ADD COLUMN price_currency   VARCHAR(3) DEFAULT 'CNY';

-- 从 params_json 回填历史值（temperature / maxTokens / topP / contextWindow / 价格）
UPDATE llm_model
SET temperature    = COALESCE(temperature, (params_json ->> 'temperature')::numeric),
    max_tokens     = COALESCE(max_tokens, (params_json ->> 'maxTokens')::integer),
    top_p          = COALESCE(top_p, (params_json ->> 'topP')::numeric),
    context_window = COALESCE(context_window, (params_json ->> 'contextWindow')::integer);

-- 约束：取值范围必须与厂商语义一致，避免又把"补全长度占满窗口"这种配置写进来
ALTER TABLE llm_model
    ADD CONSTRAINT ck_llm_model_temperature CHECK (temperature IS NULL OR (temperature >= 0 AND temperature <= 2)),
    ADD CONSTRAINT ck_llm_model_top_p       CHECK (top_p IS NULL OR (top_p > 0 AND top_p <= 1)),
    ADD CONSTRAINT ck_llm_model_context     CHECK (context_window IS NULL OR context_window > 0),
    ADD CONSTRAINT ck_llm_model_max_tokens  CHECK (max_tokens IS NULL OR max_tokens > 0),
    ADD CONSTRAINT ck_llm_model_price       CHECK (
        (price_input IS NULL OR price_input >= 0) AND (price_output IS NULL OR price_output >= 0)),
    ADD CONSTRAINT ck_llm_model_currency    CHECK (price_currency IS NULL OR price_currency IN ('CNY', 'USD'));

COMMENT ON COLUMN llm_model.temperature    IS '采样温度 0–2（未设时用默认）';
COMMENT ON COLUMN llm_model.top_p          IS 'Top-P 核采样 0–1（Top-N）';
COMMENT ON COLUMN llm_model.context_window IS '模型上下文窗口长度（token），用于校验与上下文治理阈值';
COMMENT ON COLUMN llm_model.max_tokens     IS '单次输出长度上限（token）';
COMMENT ON COLUMN llm_model.price_input    IS '输入价格：每百万 token（货币见 price_currency）';
COMMENT ON COLUMN llm_model.price_output   IS '输出价格：每百万 token（货币见 price_currency）';
COMMENT ON COLUMN llm_model.price_currency IS '价格货币：CNY / USD';
