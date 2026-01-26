package com.calai.backend.foodlog;

import com.calai.backend.foodlog.repo.UsageCounterRepository;
import com.calai.backend.entitlement.service.EntitlementService;
import com.calai.backend.foodlog.service.QuotaService;
import com.calai.backend.foodlog.web.QuotaExceededException;
import com.calai.backend.foodlog.web.SubscriptionRequiredException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

class QuotaServiceTest {

    @Test
    void should_throw_subscription_required_when_no_entitlement() {
        UsageCounterRepository repo = Mockito.mock(UsageCounterRepository.class);
        EntitlementService ent = Mockito.mock(EntitlementService.class);

        Mockito.when(ent.resolveTier(eq(1L), any(Instant.class)))
                .thenReturn(EntitlementService.Tier.NONE);

        QuotaService qs = new QuotaService(repo, ent);
        // 不需要設 limits，因為 tier=NONE 一定會丟 SUBSCRIPTION_REQUIRED

        assertThrows(SubscriptionRequiredException.class,
                () -> qs.consumeAiOrThrow(1L, ZoneOffset.UTC, Instant.now()));
    }

    @Test
    void should_throw_quota_exceeded_when_limit_reached() {
        UsageCounterRepository repo = Mockito.mock(UsageCounterRepository.class);
        EntitlementService ent = Mockito.mock(EntitlementService.class);

        Mockito.when(ent.resolveTier(eq(1L), any(Instant.class)))
                .thenReturn(EntitlementService.Tier.TRIAL);

        // 模擬：row 已存在（insert=0），而且 update 失敗（used_count >= limit）
        Mockito.when(repo.insertFirstUse(eq(1L), any(), any(Instant.class))).thenReturn(0);
        Mockito.when(repo.incrementIfBelowLimit(eq(1L), any(), anyInt(), any(Instant.class))).thenReturn(0);

        QuotaService qs = new QuotaService(repo, ent);

        // ✅ 關鍵：unit test 直接 new 不會套用 @Value，必須手動設 limit
        setIntField(qs, "trialLimit", 1);     // 1 就夠用來觸發 QUOTA_EXCEEDED
        setIntField(qs, "monthlyLimit", 200);
        setIntField(qs, "yearlyLimit", 400);

        QuotaExceededException ex = assertThrows(QuotaExceededException.class,
                () -> qs.consumeAiOrThrow(1L, ZoneId.of("Asia/Taipei"), Instant.now()));

        assertEquals("QUOTA_EXCEEDED", ex.getMessage());
        assertTrue(ex.retryAfterSec() >= 0);
        assertEquals("WAIT_TOMORROW", ex.clientAction());
    }

    // ===== helper =====
    private static void setIntField(Object target, String fieldName, int value) {
        try {
            Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.setInt(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field: " + fieldName, e);
        }
    }
}
