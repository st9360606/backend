package com.calai.backend.foodlog.job.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        LocalTempBlobCleanerProperties.class,
        Sha256BlobOrphanCleanerProperties.class
})
public class StorageCleanupPropertiesConfig {
}
