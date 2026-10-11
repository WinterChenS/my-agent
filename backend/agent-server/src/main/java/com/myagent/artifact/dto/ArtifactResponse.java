package com.myagent.artifact.dto;

import java.time.OffsetDateTime;

/**
 * 产物/来源文件响应。
 *
 * @param id 主键
 * @param sessionId 会话
 * @param role SOURCE=用户上传 / ARTIFACT=Agent 产出
 * @param storage MINIO / DB
 * @param fileName 原始文件名
 * @param mime MIME
 * @param sizeBytes 字节数
 * @param sha256 内容摘要
 * @param status READY / FAILED
 * @param errorMessage 失败原因
 * @param previewable 是否可在线预览（文本类直接已有正文）
 * @param content DB 存储时的正文（MINIO 时为 null）
 * @param createdAt 创建时间
 */
public record ArtifactResponse(
        Long id,
        Long sessionId,
        String role,
        String storage,
        String fileName,
        String mime,
        Long sizeBytes,
        String sha256,
        String status,
        String errorMessage,
        boolean previewable,
        String content,
        OffsetDateTime createdAt) {}
