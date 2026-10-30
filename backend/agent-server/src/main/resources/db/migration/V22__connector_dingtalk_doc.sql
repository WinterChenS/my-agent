-- 连接器新增类型：DINGTALK_DOC（钉钉文档 / 知识库；企业应用凭据 + operatorId）
ALTER TABLE connector DROP CONSTRAINT connector_type_ck;
ALTER TABLE connector ADD CONSTRAINT connector_type_ck CHECK (type IN ('MAIL', 'NOTION', 'DINGTALK_DOC'));
