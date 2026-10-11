package com.myagent.artifact.config;

import com.myagent.artifact.storage.MinioStorageService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

/** 启用 MinIO 配置，并在应用就绪后确保 bucket 存在（失败只告警）。 */
@Configuration
@EnableConfigurationProperties(MinioProperties.class)
public class ArtifactStorageConfig {

    private final MinioStorageService minio;

    public ArtifactStorageConfig(MinioStorageService minio) {
        this.minio = minio;
    }

    @EventListener(ContextRefreshedEvent.class)
    public void ensureBucketOnStartup() {
        minio.ensureBucket();
    }
}
