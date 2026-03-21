package com.calai.backend.foodlog.job.retention;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(FoodLogRetentionProperties.class)
public class RetentionConfig {}
