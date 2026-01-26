package com.calai.backend.testsupport;

import com.calai.backend.entitlement.repo.UserEntitlementRepository;
import com.calai.backend.entitlement.service.EntitlementService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.time.Instant;

/**
 * ✅ test profile 預設當付費用戶（避免 402 擋住一堆 contract/integration tests）
 */
@Configuration
@Profile("test")
public class TestOverridesConfiguration {

    @Bean
    @Primary
    public EntitlementService entitlementService(UserEntitlementRepository repo) {
        return new EntitlementService(repo) {
            @Override
            public Tier resolveTier(Long userId, Instant nowUtc) {
                return Tier.MONTHLY; // 或 YEARLY
            }
        };
    }
}
