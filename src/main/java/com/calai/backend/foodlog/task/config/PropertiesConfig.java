package com.calai.backend.foodlog.task.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(LocalTempBlobCleanerProperties.class)
public class PropertiesConfig {
}
