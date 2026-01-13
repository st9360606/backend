package com.calai.backend.foodlog;

import com.calai.backend.foodlog.dto.FoodLogStatus;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.entity.FoodLogTaskEntity;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.repo.FoodLogTaskRepository;
import com.calai.backend.foodlog.service.FoodLogService;
import com.calai.backend.foodlog.service.IdempotencyService;
import com.calai.backend.foodlog.service.ImageBlobService;
import com.calai.backend.foodlog.service.QuotaService;
import com.calai.backend.foodlog.storage.StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

class FoodLogRetryTest {

    @Test
    void retry_should_reset_failed_to_pending_and_queue_task() {
        FoodLogRepository repo = Mockito.mock(FoodLogRepository.class);
        FoodLogTaskRepository taskRepo = Mockito.mock(FoodLogTaskRepository.class);
        StorageService storage = Mockito.mock(StorageService.class);
        ObjectMapper om = new ObjectMapper();
        QuotaService quota = Mockito.mock(QuotaService.class);
        IdempotencyService idem = Mockito.mock(IdempotencyService.class);
        FoodLogEntity log = new FoodLogEntity();
        ImageBlobService imageblobservice = Mockito.mock(ImageBlobService.class);


        log.setId("log1");
        log.setUserId(1L);
        log.setStatus(FoodLogStatus.FAILED);
        log.setLastErrorCode("PROVIDER_FAILED");
        log.setLastErrorMessage("boom");
        log.setCapturedTz("Asia/Taipei"); // ✅ retry() 會 ZoneId.of(...) 必填

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

        // ✅ 注意：consumeAiOrThrow 是 non-void，不需要 doNothing；mock 預設回 null 即可
        FoodLogService svc = new FoodLogService(repo, taskRepo, storage, om, quota, idem, imageblobservice);

        svc.retry(1L, "log1", "rid-1");

        assertEquals(FoodLogStatus.PENDING, log.getStatus());
        assertNull(log.getLastErrorCode());
        assertNull(log.getLastErrorMessage());

        assertEquals(FoodLogTaskEntity.TaskStatus.QUEUED, task.getTaskStatus());
        assertNull(task.getNextRetryAtUtc());
        assertEquals(0, task.getAttempts());

        Mockito.verify(quota, Mockito.times(1))
                .consumeAiOrThrow(eq(1L), eq(ZoneId.of("Asia/Taipei")), any(Instant.class));
    }

    @Test
    void retry_should_reject_draft() {
        FoodLogRepository repo = Mockito.mock(FoodLogRepository.class);
        FoodLogTaskRepository taskRepo = Mockito.mock(FoodLogTaskRepository.class);
        StorageService storage = Mockito.mock(StorageService.class);
        ObjectMapper om = new ObjectMapper();
        QuotaService quota = Mockito.mock(QuotaService.class);
        IdempotencyService idem = Mockito.mock(IdempotencyService.class);
        FoodLogEntity log = new FoodLogEntity();
        ImageBlobService imageblobservice = Mockito.mock(ImageBlobService.class);

        log.setId("log2");
        log.setUserId(1L);
        log.setStatus(FoodLogStatus.DRAFT);

        Mockito.when(repo.findByIdForUpdate("log2")).thenReturn(log);

        FoodLogService svc = new FoodLogService(repo, taskRepo, storage, om, quota, idem,imageblobservice);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> svc.retry(1L, "log2", "rid-2"));

        assertEquals("FOOD_LOG_NOT_RETRYABLE", ex.getMessage());

        // ✅ draft 直接擋掉，不該扣點
        Mockito.verifyNoInteractions(quota);
    }
}
