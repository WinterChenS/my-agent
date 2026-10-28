package com.myagent.connectorconfig;

import java.time.OffsetDateTime;

/**
 * 连接器的一行数据（含**加密后**的凭据）。只在服务端内部流转，不直接出接口。
 *
 * @param alias 工具/提示里用的短别名（同一用户多个邮箱时区分）
 * @param configEnc AES-GCM 加密后的配置 JSON
 * @param inboundMode OFF / NOTIFY / AUTOREPLY
 * @param uidValidity IMAP 游标所属的 UIDVALIDITY（变了说明邮箱被重建，要重置游标）
 * @param lastUid 已处理到的最新 UID
 */
public record ConnectorRecord(
        long id,
        String type,
        String name,
        String alias,
        long ownerUserId,
        String configEnc,
        boolean enabled,
        String status,
        String lastError,
        OffsetDateTime lastCheckedAt,
        String inboundMode,
        Long uidValidity,
        long lastUid,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /** 界面上用的显示名：有别名时带上别名，方便用户认出是哪个邮箱。 */
    public String displayName() {
        return alias == null || alias.isBlank() ? name : name + "（" + alias + "）";
    }
}
