package com.calai.backend.foodlog;

import com.calai.backend.entitlement.service.EntitlementService;
import com.calai.backend.foodlog.barcode.OpenFoodFactsClient;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.entity.FoodLogTaskEntity;
import com.calai.backend.foodlog.mapper.ClientActionMapper;
import com.calai.backend.foodlog.model.FoodLogStatus;
import com.calai.backend.foodlog.quota.guard.AbuseGuardService;
import com.calai.backend.foodlog.quota.model.ModelTier;
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

import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FoodLogRetryTest {

    @Test
    void retry_should_reset_failed_to_pending_and_queue_task() {
        FoodLogRepository repo = mock(FoodLogRepository.class);
        FoodLogTaskRepository taskRepo = mock(FoodLogTaskRepository.class);
        StorageService storage = mock(StorageService.class);
        ObjectMapper om = new ObjectMapper();

        AiQuotaEngine aiQuota = mock(AiQuotaEngine.class);
        IdempotencyService idem = mock(IdempotencyService.class);
        ImageBlobService blobService = mock(ImageBlobService.class);
        UserInFlightLimiter inFlight = mock(UserInFlightLimiter.class);
        UserRateLimiter rateLimiter = mock(UserRateLimiter.class);

        EffectivePostProcessor postProcessor = mock(EffectivePostProcessor.class);
        ClientActionMapper clientActionMapper = mock(ClientActionMapper.class);
        OpenFoodFactsClient offClient = mock(OpenFoodFactsClient.class);

        // ✅ NEW：若 FoodLogService 新增了 entitlementService（常見）
        EntitlementService entitlementService = mock(EntitlementService.class);
        when(entitlementService.resolveTier(anyLong(), any(Instant.class)))
                .thenReturn(EntitlementService.Tier.TRIAL);

        // ✅ NEW：FoodLogService 現在需要 abuseGuard
        AbuseGuardService abuseGuard = mock(AbuseGuardService.class);
        doNothing().when(abuseGuard).onOperationAttempt(anyLong(), anyString(), anyBoolean(), any(Instant.class), any(ZoneId.class));

        FoodLogEntity log = new FoodLogEntity();
        log.setId("log1");
        log.setUserId(1L);
        log.setStatus(FoodLogStatus.FAILED);
        log.setLastErrorCode("PROVIDER_FAILED");
        log.setLastErrorMessage("boom");
        log.setCapturedTz("Asia/Taipei");
        log.setMethod("PHOTO"); // ✅ 避免被 BARCODE 擋掉（保險）

        FoodLogTaskEntity task = new FoodLogTaskEntity();
        task.setId("t1");
        task.setFoodLogId("log1");
        task.setTaskStatus(FoodLogTaskEntity.TaskStatus.FAILED);
        task.setNextRetryAtUtc(Instant.now().plusSeconds(999));
        task.setAttempts(3);

        when(repo.findByIdForUpdate("log1")).thenReturn(log);
        when(taskRepo.findByFoodLogIdForUpdate("log1")).thenReturn(Optional.of(task));

        // retry() 最後會呼叫 getOne()，所以也要 stub
        when(repo.findByIdAndUserId("log1", 1L)).thenReturn(Optional.of(log));
        when(taskRepo.findByFoodLogId("log1")).thenReturn(Optional.of(task));

        when(aiQuota.consumeOperationOrThrow(eq(1L), eq(ZoneId.of("Asia/Taipei")), any(Instant.class)))
                .thenReturn(new AiQuotaEngine.Decision(ModelTier.MODEL_TIER_HIGH));

        FoodLogService svc = new FoodLogService(
                repo, taskRepo, storage, om,
                aiQuota, idem, blobService,
                inFlight, rateLimiter,
                postProcessor,
                clientActionMapper,
                offClient,
                abuseGuard,
                entitlementService // ✅ 補上第 14 個參數（若你 IDE 顯示位置不同，移到正確的位置）
        );

        // ✅ retry() 現在要 (userId, foodLogId, deviceId, requestId)
        svc.retry(1L, "log1", "device-1", "rid-1");

        assertEquals(FoodLogStatus.PENDING, log.getStatus());
        assertNull(log.getLastErrorCode());
        assertNull(log.getLastErrorMessage());

        assertEquals(FoodLogTaskEntity.TaskStatus.QUEUED, task.getTaskStatus());
        assertNull(task.getNextRetryAtUtc());
        assertEquals(0, task.getAttempts());

        verify(aiQuota, times(1))
                .consumeOperationOrThrow(eq(1L), eq(ZoneId.of("Asia/Taipei")), any(Instant.class));

        verify(abuseGuard, times(1))
                .onOperationAttempt(eq(1L), eq("device-1"), eq(false), any(Instant.class), eq(ZoneId.of("Asia/Taipei")));
    }

    @Test
    void retry_should_reject_draft() {
        FoodLogRepository repo = mock(FoodLogRepository.class);
        FoodLogTaskRepository taskRepo = mock(FoodLogTaskRepository.class);
        StorageService storage = mock(StorageService.class);
        ObjectMapper om = new ObjectMapper();

        AiQuotaEngine aiQuota = mock(AiQuotaEngine.class);
        IdempotencyService idem = mock(IdempotencyService.class);
        ImageBlobService blobService = mock(ImageBlobService.class);
        UserInFlightLimiter inFlight = mock(UserInFlightLimiter.class);
        UserRateLimiter rateLimiter = mock(UserRateLimiter.class);

        EffectivePostProcessor postProcessor = mock(EffectivePostProcessor.class);
        ClientActionMapper clientActionMapper = mock(ClientActionMapper.class);
        OpenFoodFactsClient offClient = mock(OpenFoodFactsClient.class);

        AbuseGuardService abuseGuard = mock(AbuseGuardService.class);

        // ✅ NEW：若 FoodLogService 新增了 entitlementService（常見）
        EntitlementService entitlementService = mock(EntitlementService.class);
        when(entitlementService.resolveTier(anyLong(), any(Instant.class)))
                .thenReturn(EntitlementService.Tier.TRIAL);

        FoodLogEntity log = new FoodLogEntity();
        log.setId("log2");
        log.setUserId(1L);
        log.setStatus(FoodLogStatus.DRAFT);

        when(repo.findByIdForUpdate("log2")).thenReturn(log);

        FoodLogService svc = new FoodLogService(
                repo, taskRepo, storage, om,
                aiQuota, idem, blobService,
                inFlight, rateLimiter,
                postProcessor,
                clientActionMapper,
                offClient,
                abuseGuard,
                entitlementService // ✅ 補上第 14 個參數
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> svc.retry(1L, "log2", "device-1", "rid-2"));

        assertEquals("FOOD_LOG_NOT_RETRYABLE", ex.getMessage());
        verifyNoInteractions(aiQuota);
        verifyNoInteractions(abuseGuard);
    }
}
