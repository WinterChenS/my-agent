-- tier1-upgrades ④：支出配额与告警。
--
-- 口径沿用 Phase 15：成本 = 输入 tokens/百万 × 输入单价 + 输出 tokens/百万 × 输出单价，
-- 货币取模型配置（不做汇率换算）。空额度 = 不限。
--
-- 校验时机：**发起 run 之前**（spec 明确不做"事后强制中断"）。

ALTER TABLE sys_user
    ADD COLUMN daily_quota    NUMERIC(18, 6),
    ADD COLUMN monthly_quota  NUMERIC(18, 6),
    ADD COLUMN quota_currency VARCHAR(8) NOT NULL DEFAULT 'CNY',
    -- 超限行为：BLOCK=拒绝发起；WARN=只告警
    ADD COLUMN quota_mode     VARCHAR(8) NOT NULL DEFAULT 'BLOCK';

ALTER TABLE sys_user
    ADD CONSTRAINT ck_sys_user_quota_mode CHECK (quota_mode IN ('BLOCK', 'WARN'));

ALTER TABLE agent
    ADD COLUMN daily_quota    NUMERIC(18, 6),
    ADD COLUMN monthly_quota  NUMERIC(18, 6),
    ADD COLUMN quota_currency VARCHAR(8);

COMMENT ON COLUMN sys_user.daily_quota IS '日额度（空=不限），货币见 quota_currency';
COMMENT ON COLUMN sys_user.quota_mode IS '超限行为：BLOCK（拒绝发起）/ WARN（仅告警）';
COMMENT ON COLUMN agent.daily_quota IS 'Agent 级日额度（空=不限）；与用户额度同时校验';
