package com.calai.backend.foodlog;

import com.calai.backend.foodlog.model.FoodLogStatus;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.entity.FoodLogTaskEntity;
import com.calai.backend.foodlog.mapper.ClientActionMapper;
import com.calai.backend.foodlog.quota.model.ModelTier;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.repo.FoodLogTaskRepository;
import com.calai.backend.foodlog.service.IdempotencyService;
import com.calai.backend.foodlog.service.ImageBlobService;
import com.calai.backend.foodlog.service.FoodLogService;
import com.calai.backend.foodlog.service.limiter.UserInFlightLimiter;
import com.calai.backend.foodlog.service.limiter.UserRateLimiter;
import com.calai.backend.foodlog.storage.StorageService;
import com.calai.backend.foodlog.task.EffectivePostProcessor;
import com.calai.backend.foodlog.quota.service.AiQuotaEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import com.calai.backend.foodlog.barcode.OpenFoodFactsClient;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;

class FoodLogRetryTest {

    @Test
    void retry_should_reset_failed_to_pending_and_queue_task() {
        FoodLogRepository repo = Mockito.mock(FoodLogRepository.class);
        FoodLogTaskRepository taskRepo = Mockito.mock(FoodLogTaskRepository.class);
        StorageService storage = Mockito.mock(StorageService.class);
        ObjectMapper om = new ObjectMapper();

        // ✅ 改成 AiQuotaEngine
        AiQuotaEngine aiQuota = Mockito.mock(AiQuotaEngine.class);

        IdempotencyService idem = Mockito.mock(IdempotencyService.class);
        ImageBlobService imageBlobService = Mockito.mock(ImageBlobService.class);
        UserInFlightLimiter inFlight = mock(UserInFlightLimiter.class);
        UserRateLimiter rateLimiter = mock(UserRateLimiter.class);

        EffectivePostProcessor postProcessor = mock(EffectivePostProcessor.class);
        ClientActionMapper clientActionMapper = mock(ClientActionMapper.class);
        OpenFoodFactsClient offClient = Mockito.mock(OpenFoodFactsClient.class);
        FoodLogEntity log = new FoodLogEntity();
        log.setId("log1");
        log.setUserId(1L);
        log.setStatus(FoodLogStatus.FAILED);
        log.setLastErrorCode("PROVIDER_FAILED");
        log.setLastErrorMessage("boom");
        log.setCapturedTz("Asia/Taipei");

        FoodLogTaskEntity task = new FoodLogTaskEntity();
        task.setId("t1");
        task.setFoodLogId("log1");
        task.setTaskStatus(FoodLogTaskEntity.TaskStatus.FAILED);
        task.setNextRetryAtUtc(Instant.now().plusSeconds(999));
        task.setAttempts(3);

        Mockito.when(repo.findByIdForUpdate("log1")).thenReturn(log);
        Mockito.when(taskRepo.findByFoodLogIdForUpdate("log1")).thenReturn(Optional.of(task));
        Mockito.when(repo.findByIdAndUserId("log1", 1L)).thenReturn(Optional.of(log));
        Mockito.when(taskRepo.findByFoodLogId("log1")).thenReturn(Optional.of(task));

        // ✅ retry() 內會扣一次 Operation，所以要 stub Decision
        Mockito.when(aiQuota.consumeOperationOrThrow(eq(1L), eq(ZoneId.of("Asia/Taipei")), any(Instant.class)))
                .thenReturn(new AiQuotaEngine.Decision(ModelTier.MODEL_TIER_HIGH));

        FoodLogService svc = new FoodLogService(
                repo, taskRepo, storage, om,
                aiQuota, idem, imageBlobService,
                inFlight, rateLimiter,
                postProcessor,
                clientActionMapper,
                offClient
        );

        svc.retry(1L, "log1", "rid-1");

        assertEquals(FoodLogStatus.PENDING, log.getStatus());
        assertNull(log.getLastErrorCode());
        assertNull(log.getLastErrorMessage());

        assertEquals(FoodLogTaskEntity.TaskStatus.QUEUED, task.getTaskStatus());
        assertNull(task.getNextRetryAtUtc());
        assertEquals(0, task.getAttempts());

        // ✅ verify 改成 AiQuotaEngine
        Mockito.verify(aiQuota, Mockito.times(1))
                .consumeOperationOrThrow(eq(1L), eq(ZoneId.of("Asia/Taipei")), any(Instant.class));
    }

    @Test
    void retry_should_reject_draft() {
        FoodLogRepository repo = Mockito.mock(FoodLogRepository.class);
        FoodLogTaskRepository taskRepo = Mockito.mock(FoodLogTaskRepository.class);
        StorageService storage = Mockito.mock(StorageService.class);
        ObjectMapper om = new ObjectMapper();
        OpenFoodFactsClient offClient = Mockito.mock(OpenFoodFactsClient.class);
        // ✅ 改成 AiQuotaEngine
        AiQuotaEngine aiQuota = Mockito.mock(AiQuotaEngine.class);

        IdempotencyService idem = Mockito.mock(IdempotencyService.class);
        ImageBlobService imageBlobService = Mockito.mock(ImageBlobService.class);
        UserInFlightLimiter inFlight = mock(UserInFlightLimiter.class);
        UserRateLimiter rateLimiter = mock(UserRateLimiter.class);

        EffectivePostProcessor postProcessor = mock(EffectivePostProcessor.class);
        ClientActionMapper clientActionMapper = mock(ClientActionMapper.class);

        FoodLogEntity log = new FoodLogEntity();
        log.setId("log2");
        log.setUserId(1L);
        log.setStatus(FoodLogStatus.DRAFT);

        Mockito.when(repo.findByIdForUpdate("log2")).thenReturn(log);

        FoodLogService svc = new FoodLogService(
                repo, taskRepo, storage, om,
                aiQuota, idem, imageBlobService,
                inFlight, rateLimiter,
                postProcessor,
                clientActionMapper,
                offClient
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> svc.retry(1L, "log2", "rid-2"));

        assertEquals("FOOD_LOG_NOT_RETRYABLE", ex.getMessage());

        // ✅ verifyNoInteractions 改成 aiQuota
        Mockito.verifyNoInteractions(aiQuota);
    }
}