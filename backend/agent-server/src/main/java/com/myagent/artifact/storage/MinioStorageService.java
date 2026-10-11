package com.myagent.artifact.storage;

import com.myagent.artifact.config.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * MinIO 对象存储封装。
 *
 * <p>两个客户端：{@code internal} 用于后端读写；{@code publicClient} 用于生成下载直链。
 * 之所以分开，是因为 SigV4 预签名会覆盖 Host 头——若 `MINIO_PUBLIC_ENDPOINT` 与外网地址不同，
 * 用内部地址签名后改写 Host 会让签名失效（这里直接用公网地址的客户端签名）。
 *
 * <p>MinIO 不可用时不能让对话主链路挂掉：调用方负责捕获异常并落 FAILED 记录。
 */
@Component
public class MinioStorageService {

    private static final Logger log = LoggerFactory.getLogger(MinioStorageService.class);
    private static final int PRESIGN_MINUTES = 30;

    private final MinioProperties properties;
    private final MinioClient internal;
    private final MinioClient publicClient;

    public MinioStorageService(MinioProperties properties) {
        this.properties = properties;
        this.internal = build(properties.endpoint(), properties);
        this.publicClient = properties.publicEndpoint() != null
                        && !properties.publicEndpoint().isBlank()
                        && !properties.publicEndpoint().equals(properties.endpoint())
                ? build(properties.publicEndpoint(), properties)
                : this.internal;
    }

    private static MinioClient build(String endpoint, MinioProperties p) {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(p.accessKey(), p.secretKey())
                .build();
    }

    public String bucket() {
        return properties.bucket();
    }

    public long maxFileSizeBytes() {
        return Math.max(1, properties.maxFileSizeMb()) * 1024 * 1024;
    }

    /** 确保桶存在；失败只告警，不阻断应用启动。 */
    public void ensureBucket() {
        try {
            boolean exists = internal.bucketExists(
                    BucketExistsArgs.builder().bucket(properties.bucket()).build());
            if (!exists) {
                internal.makeBucket(MakeBucketArgs.builder().bucket(properties.bucket()).build());
                log.info("已创建 MinIO bucket: {}", properties.bucket());
            } else {
                log.info("MinIO bucket 已存在: {}", properties.bucket());
            }
        } catch (Exception ex) {
            log.warn("MinIO bucket 检查/创建失败（不影响启动，产物功能可能不可用）: {}", ex.toString());
        }
    }

    /** 上传字节数组。 */
    public void put(String objectKey, byte[] bytes, String contentType) throws Exception {
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            internal.putObject(PutObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .stream(in, bytes.length, -1)
                    .contentType(contentType == null ? "application/octet-stream" : contentType)
                    .build());
        }
    }

    public byte[] get(String objectKey) throws Exception {
        try (InputStream in = internal.getObject(GetObjectArgs.builder()
                .bucket(properties.bucket())
                .object(objectKey)
                .build())) {
            return in.readAllBytes();
        }
    }

    public void remove(String objectKey) {
        try {
            internal.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .build());
        } catch (Exception ex) {
            log.warn("删除 MinIO 对象失败 key={}: {}", objectKey, ex.toString());
        }
    }

    /** 生成下载直链（基于 publicEndpoint 签名）。 */
    public String presignedUrl(String objectKey) {
        try {
            return publicClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .expiry(PRESIGN_MINUTES, TimeUnit.MINUTES)
                    .build());
        } catch (Exception ex) {
            log.warn("生成 MinIO 直链失败 key={}: {}", objectKey, ex.toString());
            return null;
        }
    }
}
