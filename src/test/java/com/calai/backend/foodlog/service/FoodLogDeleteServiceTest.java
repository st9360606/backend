package com.calai.backend.foodlog.service;

import com.calai.backend.foodlog.dto.FoodLogEnvelope;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.model.FoodLogErrorCode;
import com.calai.backend.foodlog.model.FoodLogStatus;
import com.calai.backend.foodlog.repo.DeletionJobRepository;
import com.calai.backend.foodlog.repo.FoodLogOverrideRepository;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.repo.FoodLogRequestRepository;
import com.calai.backend.foodlog.repo.FoodLogTaskRepository;
import com.calai.backend.foodlog.web.error.FoodLogAppException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class FoodLogDeleteServiceTest {

    @Test
    void delete_should_hard_delete_children_and_release_blob() {
        FoodLogRepository logRepo = mock(FoodLogRepository.class);
        FoodLogTaskRepository taskRepo = mock(FoodLogTaskRepository.class);
        FoodLogOverrideRepository overrideRepo = mock(FoodLogOverrideRepository.class);
        DeletionJobRepository deletionRepo = mock(DeletionJobRepository.class);
        FoodLogRequestRepository requestRepo = mock(FoodLogRequestRepository.class);
        ImageBlobService blobService = mock(ImageBlobService.class);
        UserDailyNutritionSummaryService dailySummaryService = mock(UserDailyNutritionSummaryService.class);

        LocalDate capturedLocalDate = LocalDate.of(2026, 3, 3);

        FoodLogEntity log = new FoodLogEntity();
        log.setId("L1");
        log.setUserId(10L);
        log.setStatus(FoodLogStatus.SAVED);
        log.setImageSha256("sha256-abc");
        log.setCapturedLocalDate(capturedLocalDate);

        when(logRepo.findByIdForUpdate("L1")).thenReturn(Optional.of(log));

        FoodLogDeleteService svc = new FoodLogDeleteService(
                logRepo,
                taskRepo,
                overrideRepo,
                deletionRepo,
                requestRepo,
                blobService,
                dailySummaryService
        );

        FoodLogEnvelope env = svc.deleteOne(10L, "L1", "RID-1");

        assertEquals("L1", env.foodLogId());
        assertEquals("DELETED", env.status());

        verify(taskRepo).deleteByFoodLogId("L1");
        verify(overrideRepo).deleteByFoodLogId("L1");
        verify(deletionRepo).deleteByFoodLogId("L1");
        verify(requestRepo).deleteByFoodLogId("L1");
        verify(logRepo).delete(log);
        verify(logRepo).flush();
        verify(dailySummaryService).recomputeDay(10L, capturedLocalDate);
        verify(blobService).release(10L, "sha256-abc");

        verifyNoMoreInteractions(taskRepo, overrideRepo, deletionRepo, requestRepo, blobService, dailySummaryService);
    }

    @Test
    void delete_should_not_release_blob_when_sha256_blank() {
        FoodLogRepository logRepo = mock(FoodLogRepository.class);
        FoodLogTaskRepository taskRepo = mock(FoodLogTaskRepository.class);
        FoodLogOverrideRepository overrideRepo = mock(FoodLogOverrideRepository.class);
        DeletionJobRepository deletionRepo = mock(DeletionJobRepository.class);
        FoodLogRequestRepository requestRepo = mock(FoodLogRequestRepository.class);
        ImageBlobService blobService = mock(ImageBlobService.class);
        UserDailyNutritionSummaryService dailySummaryService = mock(UserDailyNutritionSummaryService.class);

        LocalDate capturedLocalDate = LocalDate.of(2026, 3, 4);

        FoodLogEntity log = new FoodLogEntity();
        log.setId("L2");
        log.setUserId(10L);
        log.setStatus(FoodLogStatus.DRAFT);
        log.setImageSha256("   ");
        log.setCapturedLocalDate(capturedLocalDate);

        when(logRepo.findByIdForUpdate("L2")).thenReturn(Optional.of(log));

        FoodLogDeleteService svc = new FoodLogDeleteService(
                logRepo,
                taskRepo,
                overrideRepo,
                deletionRepo,
                requestRepo,
                blobService,
                dailySummaryService
        );

        FoodLogEnvelope env = svc.deleteOne(10L, "L2", "RID-2");

        assertEquals("DELETED", env.status());

        verify(taskRepo).deleteByFoodLogId("L2");
        verify(overrideRepo).deleteByFoodLogId("L2");
        verify(deletionRepo).deleteByFoodLogId("L2");
        verify(requestRepo).deleteByFoodLogId("L2");
        verify(logRepo).delete(log);
        verify(logRepo).flush();
        verify(dailySummaryService).recomputeDay(10L, capturedLocalDate);
        verify(blobService, never()).release(anyLong(), anyString());
    }

    @Test
    void delete_should_throw_not_found_when_user_not_owner() {
        FoodLogRepository logRepo = mock(FoodLogRepository.class);
        FoodLogTaskRepository taskRepo = mock(FoodLogTaskRepository.class);
        FoodLogOverrideRepository overrideRepo = mock(FoodLogOverrideRepository.class);
        DeletionJobRepository deletionRepo = mock(DeletionJobRepository.class);
        FoodLogRequestRepository requestRepo = mock(FoodLogRequestRepository.class);
        ImageBlobService blobService = mock(ImageBlobService.class);
        UserDailyNutritionSummaryService dailySummaryService = mock(UserDailyNutritionSummaryService.class);

        FoodLogEntity log = new FoodLogEntity();
        log.setId("L3");
        log.setUserId(999L);
        log.setStatus(FoodLogStatus.SAVED);

        when(logRepo.findByIdForUpdate("L3")).thenReturn(Optional.of(log));

        FoodLogDeleteService svc = new FoodLogDeleteService(
                logRepo,
                taskRepo,
                overrideRepo,
                deletionRepo,
                requestRepo,
                blobService,
                dailySummaryService
        );

        FoodLogAppException ex = assertThrows(
                FoodLogAppException.class,
                () -> svc.deleteOne(10L, "L3", "RID-3")
        );

        assertEquals(FoodLogErrorCode.FOOD_LOG_NOT_FOUND, ex.getErrorCode());

        verify(taskRepo, never()).deleteByFoodLogId(anyString());
        verify(overrideRepo, never()).deleteByFoodLogId(anyString());
        verify(deletionRepo, never()).deleteByFoodLogId(anyString());
        verify(requestRepo, never()).deleteByFoodLogId(anyString());
        verify(logRepo, never()).delete(any());
        verify(logRepo, never()).flush();
        verify(blobService, never()).release(anyLong(), anyString());
        verifyNoInteractions(dailySummaryService);
    }
}
