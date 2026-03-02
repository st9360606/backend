package com.calai.backend.foodlog.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    /**
     * 預設系統 UTC Clock。
     * 測試時可用 @TestConfiguration 或 @MockBean / @Primary 覆蓋。
     */
    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}