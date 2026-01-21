package com.calai.backend.foodlog;

import com.calai.backend.foodlog.dto.FoodLogEnvelope;
import com.calai.backend.foodlog.dto.FoodLogStatus;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.entity.FoodLogTaskEntity;
import com.calai.backend.foodlog.mapper.ClientActionMapper;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.repo.FoodLogTaskRepository;
import com.calai.backend.foodlog.service.*;
import com.calai.backend.foodlog.service.limiter.UserInFlightLimiter;
import com.calai.backend.foodlog.service.limiter.UserRateLimiter;
import com.calai.backend.foodlog.storage.StorageService;
import com.calai.backend.foodlog.task.EffectivePostProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FoodLogServiceTaskVisibilityTest {

    @Mock FoodLogRepository repo;
    @Mock FoodLogTaskRepository taskRepo;
    @Mock StorageService storage;

    @Mock QuotaService quota;
    @Mock IdempotencyService idem;
    @Mock ImageBlobService imageBlobService;
    @Mock UserInFlightLimiter inFlight;
    @Mock UserRateLimiter rateLimiter;

    @Mock EffectivePostProcessor postProcessor;

    // ✅ 新增：ClientActionMapper
    @Mock ClientActionMapper clientActionMapper;

    private FoodLogService service;

    @BeforeEach
    void setUp() {
        service = new FoodLogService(
                repo, taskRepo, storage,
                new ObjectMapper(),
                quota, idem, imageBlobService,
                inFlight, rateLimiter,
                postProcessor,
                clientActionMapper
        );
    }

    @Test
    void pending_should_query_task_and_include_task_in_envelope() {
        FoodLogEntity e = new FoodLogEntity();
        e.setId("log-1");
        e.setUserId(1L);
        e.setStatus(FoodLogStatus.PENDING);
        e.setMethod("ALBUM");
        e.setProvider("STUB");
        e.setDegradeLevel("DG-0");
        e.setEffective(null);

        FoodLogTaskEntity t = new FoodLogTaskEntity();
        t.setId("task-1");
        t.setFoodLogId("log-1");
        t.setTaskStatus(FoodLogTaskEntity.TaskStatus.QUEUED);
        t.setPollAfterSec(2);

        when(repo.findByIdAndUserId("log-1", 1L)).thenReturn(Optional.of(e));
        when(taskRepo.findByFoodLogId("log-1")).thenReturn(Optional.of(t));

        FoodLogEnvelope env = service.getOne(1L, "log-1", "rid-1");

        assertNotNull(env.task());
        assertEquals("task-1", env.task().taskId());

        verify(taskRepo, times(1)).findByFoodLogId("log-1");
        verifyNoInteractions(quota);
    }

    @Test
    void draft_should_not_query_task_and_should_not_include_task_in_envelope() {
        FoodLogEntity e = new FoodLogEntity();
        e.setId("log-2");
        e.setUserId(1L);
        e.setStatus(FoodLogStatus.DRAFT);
        e.setMethod("ALBUM");
        e.setProvider("STUB");
        e.setDegradeLevel("DG-0");
        e.setEffective(null);

        when(repo.findByIdAndUserId("log-2", 1L)).thenReturn(Optional.of(e));

        FoodLogEnvelope env = service.getOne(1L, "log-2", "rid-2");

        assertNull(env.task());
        verify(taskRepo, never()).findByFoodLogId(anyString());
        verifyNoInteractions(quota);
    }

    @Test
    void failed_should_query_task_and_include_task_in_envelope() {
        FoodLogEntity e = new FoodLogEntity();
        e.setId("log-3");
        e.setUserId(1L);
        e.setStatus(FoodLogStatus.FAILED);
        e.setMethod("ALBUM");
        e.setProvider("STUB");
        e.setDegradeLevel("DG-0");
        e.setEffective(null);
        e.setLastErrorCode("PROVIDER_FAILED");
        e.setLastErrorMessage("boom");

        FoodLogTaskEntity t = new FoodLogTaskEntity();
        t.setId("task-3");
        t.setFoodLogId("log-3");
        t.setTaskStatus(FoodLogTaskEntity.TaskStatus.FAILED);
        t.setPollAfterSec(2);

        when(repo.findByIdAndUserId("log-3", 1L)).thenReturn(Optional.of(e));
        when(taskRepo.findByFoodLogId("log-3")).thenReturn(Optional.of(t));

        FoodLogEnvelope env = service.getOne(1L, "log-3", "rid-3");

        assertNotNull(env.task());
        assertEquals("task-3", env.task().taskId());
        assertNotNull(env.error());
        assertEquals("PROVIDER_FAILED", env.error().errorCode());

        verify(taskRepo, times(1)).findByFoodLogId("log-3");
        verifyNoInteractions(quota);
    }
}
