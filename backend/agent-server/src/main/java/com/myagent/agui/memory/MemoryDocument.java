package com.myagent.agui.memory;

import java.time.OffsetDateTime;

/**
 * 一份长期记忆文档（给「记忆可视化」页面用）。
 *
 * @param name       文件名：`MEMORY.md`（精华长期记忆）或 `YYYY-MM-DD.md`（当日台账）
 * @param kind       `CURATED`（MEMORY.md）/ `DAILY`（每日台账）
 * @param sizeBytes  字符数（按 UTF-8 字节算更贴近“文件大小”直觉，这里用字节）
 * @param updatedAt  最后修改时间（拿不到时为 null）
 * @param content    正文；**列表接口不带正文**（null），详情接口才带
 */
public record MemoryDocument(
        String name, String kind, long sizeBytes, OffsetDateTime updatedAt, String content) {

    public static final String CURATED_NAME = "MEMORY.md";
    public static final String KIND_CURATED = "CURATED";
    public static final String KIND_DAILY = "DAILY";

    /** 列表用：不带正文。 */
    public MemoryDocument withoutContent() {
        return new MemoryDocument(name, kind, sizeBytes, updatedAt, null);
    }

    public MemoryDocument withContent(String text) {
        long size = text == null ? 0 : text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        return new MemoryDocument(name, kind, size, updatedAt, text);
    }
}
