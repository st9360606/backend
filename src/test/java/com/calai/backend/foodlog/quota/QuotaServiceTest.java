package com.calai.backend.foodlog.quota.service;

import com.calai.backend.entitlement.service.EntitlementService;
import com.calai.backend.foodlog.quota.entity.UserAiQuotaStateEntity;
import com.calai.backend.foodlog.quota.model.CooldownReason;
import com.calai.backend.foodlog.quota.model.ModelTier;
import com.calai.backend.foodlog.quota.repo.UserAiQuotaStateRepository;
import com.calai.backend.foodlog.quota.web.CooldownActiveException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class QuotaServiceTest {

    private UserAiQuotaStateRepository repo;
    private EntitlementService entitlementService;
    private QuotaService service;

    @BeforeEach
    void setUp() {
        repo = mock(UserAiQuotaStateRepository.class);
        entitlementService = mock(EntitlementService.class);
        service = new QuotaService(repo, entitlementService);

        ReflectionTestUtils.setField(service, "cooldownStepMinutes", 10);
        ReflectionTestUtils.setField(service, "maxCooldownMinutes", 30);
        ReflectionTestUtils.setField(service, "trialPremiumLimit", 2);
        ReflectionTestUtils.setField(service, "trialTotalLimit", 3);
        ReflectionTestUtils.setField(service, "paidPremiumLimit", 5);
        ReflectionTestUtils.setField(service, "paidTotalLimit", 10);
    }

    @Test
    void consumeOperationOrThrow_should_fallback_to_utc_when_userTz_is_null() {
        UserAiQuotaStateEntity s = newState();
        s.setDailyKey("2026-03-03@UTC");
        s.setMonthlyKey("2026-03@UTC");
        s.setDailyCount(0);
        s.setMonthlyCount(0);
        s.setCooldownStrikes(0);

        when(repo.findForUpdate(1L)).thenReturn(Optional.of(s));
        when(repo.save(any(UserAiQuotaStateEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        QuotaService.Decision d = service.consumeOperationOrThrow(
                1L,
                EntitlementService.Tier.TRIAL,
                null,
                Instant.parse("2026-03-03T00:00:00Z")
        );

        assertThat(d.tierUsed()).isEqualTo(ModelTier.MODEL_TIER_HIGH);
    }

    @Test
    void consumeOperationOrThrow_should_return_low_when_trial_used_exceeds_premium_limit() {
        UserAiQuotaStateEntity s = newState();
        s.setDailyKey("2026-03-03@UTC");
        s.setMonthlyKey("2026-03@UTC");
        s.setDailyCount(2); // +1 => 3，超過 premiumLimit=2，但未超 totalLimit=3
        s.setMonthlyCount(0);

        when(repo.findForUpdate(1L)).thenReturn(Optional.of(s));
        when(repo.save(any(UserAiQuotaStateEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        QuotaService.Decision d = service.consumeOperationOrThrow(
                1L,
                EntitlementService.Tier.TRIAL,
                ZoneId.of("UTC"),
                Instant.parse("2026-03-03T00:00:00Z")
        );

        assertThat(d.tierUsed()).isEqualTo(ModelTier.MODEL_TIER_LOW);
    }

    @Test
    void consumeOperationOrThrow_should_throw_cooldown_when_trial_exceeds_total_limit() {
        UserAiQuotaStateEntity s = newState();
        s.setDailyKey("2026-03-03@UTC");
        s.setMonthlyKey("2026-03@UTC");
        s.setDailyCount(3); // +1 => 4，超過 totalLimit=3
        s.setMonthlyCount(0);
        s.setCooldownStrikes(0);

        when(repo.findForUpdate(1L)).thenReturn(Optional.of(s));
        when(repo.save(any(UserAiQuotaStateEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> service.consumeOperationOrThrow(
                1L,
                EntitlementService.Tier.TRIAL,
                ZoneId.of("UTC"),
                Instant.parse("2026-03-03T00:00:00Z")
        )).isInstanceOf(CooldownActiveException.class);
    }

    private static UserAiQuotaStateEntity newState() {
        UserAiQuotaStateEntity s = new UserAiQuotaStateEntity();
        s.setUserId(1L);
        s.setCooldownReason(CooldownReason.OVER_QUOTA.name());
        s.setNextAllowedAtUtc(null);
        s.setForceLowUntilUtc(null);
        return s;
    }
}