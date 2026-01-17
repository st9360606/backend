package com.calai.backend.Integration_testing.config;

import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.storage.StorageService;
import com.calai.backend.foodlog.task.ProviderClient;
import com.calai.backend.foodlog.task.ProviderRouter;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;

@TestConfiguration
public class TestProviderFailConfig {

    @Bean
    public ProviderClient providerAlwaysTimeout() {
        return new ProviderClient() {

            @Override
            public String providerCode() {
                return "TEST_TIMEOUT";
            }

            @Override
            public ProviderResult process(FoodLogEntity log, StorageService storage) {
                throw new ResourceAccessException("Read timed out", new SocketTimeoutException("timeout"));
            }
        };
    }

    @Bean
    @Primary
    public ProviderRouter providerRouterOverride(ProviderClient providerAlwaysTimeout) {
        // ✅ 直接覆蓋 router：測試時永遠挑這個 provider
        return new ProviderRouter(java.util.Map.of("TEST_TIMEOUT", providerAlwaysTimeout), "TEST_TIMEOUT") {
            @Override
            public ProviderClient pick(FoodLogEntity log) {
                return providerAlwaysTimeout;
            }
        };
    }
}
