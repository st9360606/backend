package com.calai.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }

    /**
     * ✅ 測試環境不要啟動排程
     * 避免 H2 尚未建表時，@Scheduled worker 去查 food_log_tasks / deletion_jobs 造成噴錯
     */
    @Configuration
    @Profile("!test")
    @EnableScheduling
    static class SchedulingEnabledConfig {
        // no-op
    }
}
