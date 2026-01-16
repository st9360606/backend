package com.calai.backend.Integration_testing.config;

import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.storage.StorageService;
import com.calai.backend.foodlog.task.ProviderClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;

/**
 * ✅ 測試用 ProviderClient：固定丟 timeout
 */
@TestConfiguration
public class TestProviderFailConfig {

    @Bean
    @Primary
    public ProviderClient providerClientAlwaysTimeout() {
        return new ProviderClient() {
            @Override
            public ProviderResult process(FoodLogEntity log, StorageService storage) {
                throw new ResourceAccessException("Read timed out", new SocketTimeoutException("timeout"));
            }
        };
    }
}
