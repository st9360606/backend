// src/main/java/com/calai/backend/config/AsyncSchedulingConfig.java
package com.calai.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class AsyncSchedulingConfig {

    @Bean("aliasPromotionExecutor")
    public TaskExecutor aliasPromotionExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(2);
        ex.setQueueCapacity(100);
        ex.setThreadNamePrefix("alias-prom-");
        ex.initialize();
        return ex;
    }

    @Bean("aliasPurgeExecutor")
    public TaskExecutor aliasPurgeExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(2);
        ex.setQueueCapacity(100);
        ex.setThreadNamePrefix("alias-purge-");
        ex.initialize();
        return ex;
    }

    @Bean("retentionExecutor")
    public TaskExecutor retentionExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(2);
        ex.setQueueCapacity(100);
        ex.setThreadNamePrefix("retention-");
        ex.initialize();
        return ex;
    }
}
