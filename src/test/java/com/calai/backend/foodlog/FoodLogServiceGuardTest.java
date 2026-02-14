package com.calai.backend.foodlog;

import com.calai.backend.entitlement.service.EntitlementService;
import com.calai.backend.foodlog.barcode.OpenFoodFactsClient;
import com.calai.backend.foodlog.mapper.ClientActionMapper;
import com.calai.backend.foodlog.quota.guard.AbuseGuardService;
import com.calai.backend.foodlog.quota.service.AiQuotaEngine;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.repo.FoodLogTaskRepository;
import com.calai.backend.foodlog.service.FoodLogService;
import com.calai.backend.foodlog.service.IdempotencyService;
import com.calai.backend.foodlog.service.ImageBlobService;
import com.calai.backend.foodlog.service.limiter.UserInFlightLimiter;
import com.calai.backend.foodlog.service.limiter.UserRateLimiter;
import com.calai.backend.foodlog.storage.StorageService;
import com.calai.backend.foodlog.task.EffectivePostProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FoodLogServiceGuardTest {

    @Mock FoodLogRepository repo;
    @Mock FoodLogTaskRepository taskRepo;
    @Mock StorageService storage;
    @Mock ObjectMapper om;

    @Mock AiQuotaEngine aiQuota;
    @Mock IdempotencyService idem;
    @Mock ImageBlobService blobService;
    @Mock UserInFlightLimiter inFlight;
    @Mock UserRateLimiter rateLimiter;

    @Mock OpenFoodFactsClient offClient;
    @Mock EffectivePostProcessor postProcessor;
    @Mock ClientActionMapper clientActionMapper;

    @Mock AbuseGuardService abuseGuard;

    // ✅ NEW：FoodLogService 會先 resolveTier 再丟給 rateLimiter
    @Mock EntitlementService entitlementService;

    // ✅ 用 InjectMocks 讓 Mockito 自動依照 constructor 參數注入（不用手動 new）
    @InjectMocks FoodLogService svc;

    @Test
    void createPhoto_releaseInFlight_whenUnsupportedFormat() throws Exception {
        when(idem.reserveOrGetExisting(anyLong(), anyString(), any(Instant.class))).thenReturn(null);

        // ✅ 讓 rateLimiter 需要的 tier 有值
        when(entitlementService.resolveTier(anyLong(), any(Instant.class)))
                .thenReturn(EntitlementService.Tier.TRIAL);

        // ✅ NEW：rateLimiter.checkOrThrow(userId, tier, now)
        doNothing().when(rateLimiter).checkOrThrow(
                anyLong(),
                any(EntitlementService.Tier.class),
                any(Instant.class)
        );

        doNothing().when(idem).failAndReleaseIfNeeded(anyLong(), anyString(), anyString(), anyString(), anyBoolean());

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "x.bin",
                "application/octet-stream",
                new byte[]{1, 2, 3, 4, 5}
        );

        // ✅ createPhoto 6 個參數（含 deviceCapturedAtUtc）
        assertThrows(IllegalArgumentException.class, () ->
                svc.createPhoto(1L, "Asia/Taipei", "dev-1", null, file, "rid-1")
        );

        // ✅ 會先 resolveTier，再做 rate limit
        verify(entitlementService, atMost(1)).resolveTier(eq(1L), any(Instant.class));
        verify(rateLimiter, atMost(1)).checkOrThrow(eq(1L), eq(EntitlementService.Tier.TRIAL), any(Instant.class));

        verify(inFlight).acquireOrThrow(1L);
        verify(inFlight).release(1L);

        // （可選）這條路徑在 detect 就炸，理論上不會扣 quota
        verifyNoInteractions(aiQuota);
    }
}
