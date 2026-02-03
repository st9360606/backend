package com.calai.backend.foodlog.quota;

import com.calai.backend.entitlement.service.EntitlementService;
import com.calai.backend.foodlog.quota.model.ModelTier;
import com.calai.backend.foodlog.quota.service.AiQuotaEngine;
import com.calai.backend.foodlog.quota.web.CooldownActiveException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

// ✅ NEW (Spring Boot 3.4+)
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@SpringBootTest
class AiQuotaEngineTest {

    @Autowired
    AiQuotaEngine engine;

    // ✅ NEW: replaces @MockBean
    @MockitoBean
    EntitlementService entitlement;

    @Test
    void trial_split_high_low_and_cooldown_on_31st() {
        when(entitlement.resolveTier(any(Long.class), any(Instant.class)))
                .thenReturn(EntitlementService.Tier.TRIAL);

        ZoneId tz = ZoneId.of("Asia/Taipei");
        Instant t0 = Instant.parse("2026-02-03T00:00:00Z");

        // 1..15 => HIGH
        for (int i = 1; i <= 15; i++) {
            var d = engine.consumeOperationOrThrow(1L, tz, t0.plusSeconds(i));
            assertEquals(ModelTier.MODEL_TIER_HIGH, d.tierUsed());
        }

        // 16..30 => LOW
        for (int i = 16; i <= 30; i++) {
            var d = engine.consumeOperationOrThrow(1L, tz, t0.plusSeconds(i));
            assertEquals(ModelTier.MODEL_TIER_LOW, d.tierUsed());
        }

        // 31 => cooldown strike 1 (>= 10m)
        CooldownActiveException ex = assertThrows(
                CooldownActiveException.class,
                () -> engine.consumeOperationOrThrow(1L, tz, t0.plusSeconds(31))
        );
        assertEquals(1, ex.cooldownLevel());
        assertEquals("OVER_QUOTA", ex.cooldownReason().name());
        assertTrue(ex.cooldownSeconds() >= 600);
    }
}