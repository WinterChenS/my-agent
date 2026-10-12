-- Phase 18（tier1-upgrades ①）：模型是否支持多模态（图片输入）
--
-- 用户要求：模型维护里要有一个开关控制"是否多模态"。
-- 默认关闭：此时即使消息里带了图片，也会被剥离并明确提示（而不是让厂商报 400）。

ALTER TABLE llm_model
    ADD COLUMN multimodal BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN llm_model.multimodal IS '是否支持图片输入（多模态）；关闭时图片内容会被剥离并提示';
