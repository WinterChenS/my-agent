package com.myagent.artifact.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MinIO 配置（技术方案 §12.2）。全部由环境变量注入，不硬编码。
 *
 * @param endpoint 内部访问地址（后端读写用）
 * @param publicEndpoint 对外访问地址（生成下载直链用；与 endpoint 不同时用独立客户端签名）
 * @param accessKey 访问密钥
 * @param secretKey 私钥
 * @param bucket 桶名（不存在则启动时创建）
 * @param maxFileSizeMb 单文件大小上限
 */
@ConfigurationProperties(prefix = "myagent.minio")
public record MinioProperties(
        String endpoint,
        String publicEndpoint,
        String accessKey,
        String secretKey,
        String bucket,
        long maxFileSizeMb) {}
