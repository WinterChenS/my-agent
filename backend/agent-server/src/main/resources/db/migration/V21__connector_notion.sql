-- 连接器新增类型：NOTION（Notion REST API，凭据按用户隔离）
ALTER TABLE connector DROP CONSTRAINT connector_type_ck;
ALTER TABLE connector ADD CONSTRAINT connector_type_ck CHECK (type IN ('MAIL', 'NOTION'));
