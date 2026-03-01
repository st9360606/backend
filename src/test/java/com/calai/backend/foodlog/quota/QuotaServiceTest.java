package com.calai.backend.foodlog.quota;

import com.calai.backend.entitlement.service.EntitlementService;
import com.calai.backend.foodlog.quota.entity.UserAiQuotaStateEntity;
import com.calai.backend.foodlog.quota.model.ModelTier;
import com.calai.backend.foodlog.quota.repo.UserAiQuotaStateRepository;
import com.calai.backend.foodlog.quota.service.QuotaService;
import com.calai.backend.foodlog.quota.web.CooldownActiveException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ActiveProfiles("test")
@SpringBootTest
class QuotaServiceTest {

    @Autowired
    QuotaService engine;

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
    @Test
    void new_day_should_clear_over_quota_cooldown_before_throwing_429() {
        UserAiQuotaStateRepository repo = mock(UserAiQuotaStateRepository.class);
        EntitlementService entitlementService = mock(EntitlementService.class);

        QuotaService service = new QuotaService(repo, entitlementService);

        ReflectionTestUtils.setField(service, "cooldownStepMinutes", 10);
        ReflectionTestUtils.setField(service, "maxCooldownMinutes", 30);
        ReflectionTestUtils.setField(service, "trialPremiumLimit", 15);
        ReflectionTestUtils.setField(service, "trialTotalLimit", 30);
        ReflectionTestUtils.setField(service, "paidPremiumLimit", 600);
        ReflectionTestUtils.setField(service, "paidTotalLimit", 1000);

        UserAiQuotaStateEntity s = new UserAiQuotaStateEntity();
        s.setUserId(1L);
        s.setDailyKey("2026-02-28@Asia/Taipei");
        s.setMonthlyKey("2026-02@Asia/Taipei");
        s.setDailyCount(30);
        s.setMonthlyCount(30);
        s.setCooldownStrikes(3);
        s.setCooldownReason("OVER_QUOTA");
        s.setNextAllowedAtUtc(Instant.parse("2026-02-28T16:10:00Z")); // 台灣 00:10
        s.setForceLowUntilUtc(null);

        when(repo.findForUpdate(1L)).thenReturn(Optional.of(s));
        when(repo.save(any(UserAiQuotaStateEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Instant nowUtc = Instant.parse("2026-02-28T16:01:00Z"); // 台灣 2026-03-01 00:01
        ZoneId tz = ZoneId.of("Asia/Taipei");

        QuotaService.Decision decision = service.consumeOperationOrThrow(
                1L,
                EntitlementService.Tier.TRIAL,
                tz,
                nowUtc
        );

        assertEquals(ModelTier.MODEL_TIER_HIGH, decision.tierUsed());
        assertEquals("2026-03-01@Asia/Taipei", s.getDailyKey());
        assertEquals(1, s.getDailyCount());
        assertEquals(0, s.getCooldownStrikes());
        assertNull(s.getCooldownReason());
        assertNull(s.getNextAllowedAtUtc());

        verify(repo).save(s);
    }
    @Test
    void new_day_should_clear_force_low_together_with_over_quota_cooldown() {
        UserAiQuotaStateRepository repo = mock(UserAiQuotaStateRepository.class);
        EntitlementService entitlementService = mock(EntitlementService.class);

        QuotaService service = new QuotaService(repo, entitlementService);

        ReflectionTestUtils.setField(service, "cooldownStepMinutes", 10);
        ReflectionTestUtils.setField(service, "maxCooldownMinutes", 30);
        ReflectionTestUtils.setField(service, "trialPremiumLimit", 15);
        ReflectionTestUtils.setField(service, "trialTotalLimit", 30);
        ReflectionTestUtils.setField(service, "paidPremiumLimit", 600);
        ReflectionTestUtils.setField(service, "paidTotalLimit", 1000);

        UserAiQuotaStateEntity s = new UserAiQuotaStateEntity();
        s.setUserId(1L);
        s.setDailyKey("2026-02-28@Asia/Taipei");
        s.setMonthlyKey("2026-02@Asia/Taipei");
        s.setDailyCount(30);
        s.setMonthlyCount(30);
        s.setCooldownStrikes(3);
        s.setCooldownReason("OVER_QUOTA");
        s.setNextAllowedAtUtc(Instant.parse("2026-02-28T16:10:00Z"));
        s.setForceLowUntilUtc(Instant.parse("2026-02-28T16:00:50Z"));

        when(repo.findForUpdate(1L)).thenReturn(Optional.of(s));
        when(repo.save(any(UserAiQuotaStateEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Instant nowUtc = Instant.parse("2026-02-28T16:00:10Z"); // 台灣 2026-03-01 00:00:10
        ZoneId tz = ZoneId.of("Asia/Taipei");

        QuotaService.Decision decision = service.consumeOperationOrThrow(
                1L,
                EntitlementService.Tier.TRIAL,
                tz,
                nowUtc
        );

        assertEquals(ModelTier.MODEL_TIER_HIGH, decision.tierUsed());
        assertNull(s.getForceLowUntilUtc());
    }
}