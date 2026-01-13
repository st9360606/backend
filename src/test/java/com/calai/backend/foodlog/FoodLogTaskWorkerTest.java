package com.calai.backend.foodlog;

import com.calai.backend.foodlog.dto.FoodLogStatus;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.entity.FoodLogTaskEntity;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.repo.FoodLogTaskRepository;
import com.calai.backend.foodlog.storage.StorageService;
import com.calai.backend.foodlog.task.FoodLogTaskWorker;
import com.calai.backend.foodlog.task.ProviderClient;
import com.calai.backend.foodlog.task.TaskRetryPolicy;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

class FoodLogTaskWorkerTest {

    @Test
    void deleted_log_should_cancel_without_calling_provider() throws Exception {
        FoodLogTaskRepository taskRepo = Mockito.mock(FoodLogTaskRepository.class);
        FoodLogRepository logRepo = Mockito.mock(FoodLogRepository.class);
        ProviderClient provider = Mockito.mock(ProviderClient.class);
        StorageService storage = Mockito.mock(StorageService.class);

        FoodLogTaskEntity task = new FoodLogTaskEntity();
        task.setId("t1");
        task.setFoodLogId("log1");
        task.setTaskStatus(FoodLogTaskEntity.TaskStatus.QUEUED);

        FoodLogEntity log = new FoodLogEntity();
        log.setId("log1");
        log.setStatus(FoodLogStatus.DELETED);

        Mockito.when(taskRepo.claimRunnableForUpdate(any(Instant.class), anyInt())).thenReturn(List.of(task));
        Mockito.when(logRepo.findByIdForUpdate("log1")).thenReturn(log);

        FoodLogTaskWorker worker = new FoodLogTaskWorker(taskRepo, logRepo, provider, storage);
        worker.runOnce();

        assertEquals(FoodLogTaskEntity.TaskStatus.CANCELLED, task.getTaskStatus());
        Mockito.verify(provider, Mockito.never()).process(anyString(), anyString(), any());
    }

    @Test
    void transient_failure_should_schedule_retry() throws Exception {
        FoodLogTaskRepository taskRepo = Mockito.mock(FoodLogTaskRepository.class);
        FoodLogRepository logRepo = Mockito.mock(FoodLogRepository.class);
        ProviderClient provider = Mockito.mock(ProviderClient.class);
        StorageService storage = Mockito.mock(StorageService.class);

        FoodLogTaskEntity task = new FoodLogTaskEntity();
        task.setId("t2");
        task.setFoodLogId("log2");
        task.setTaskStatus(FoodLogTaskEntity.TaskStatus.QUEUED);

        FoodLogEntity log = new FoodLogEntity();
        log.setId("log2");
        log.setStatus(FoodLogStatus.PENDING);
        log.setImageObjectKey("user-1/food-log/log2/original.jpg");

        Mockito.when(taskRepo.claimRunnableForUpdate(any(Instant.class), anyInt())).thenReturn(List.of(task));
        Mockito.when(logRepo.findByIdForUpdate("log2")).thenReturn(log);
        Mockito.when(provider.process(eq("log2"), anyString(), eq(storage)))
                .thenThrow(new RuntimeException("boom"));

        FoodLogTaskWorker worker = new FoodLogTaskWorker(taskRepo, logRepo, provider, storage);
        worker.runOnce();

        assertEquals(FoodLogTaskEntity.TaskStatus.FAILED, task.getTaskStatus());
        assertNotNull(task.getNextRetryAtUtc());
        assertEquals(1, task.getAttempts()); // markRunning 後 +1
        assertEquals(FoodLogStatus.FAILED, log.getStatus());
    }

    @Test
    void reach_max_attempts_should_give_up() throws Exception {
        FoodLogTaskRepository taskRepo = Mockito.mock(FoodLogTaskRepository.class);
        FoodLogRepository logRepo = Mockito.mock(FoodLogRepository.class);
        ProviderClient provider = Mockito.mock(ProviderClient.class);
        StorageService storage = Mockito.mock(StorageService.class);

        FoodLogTaskEntity task = new FoodLogTaskEntity();
        task.setId("t3");
        task.setFoodLogId("log3");
        task.setTaskStatus(FoodLogTaskEntity.TaskStatus.QUEUED);
        task.setAttempts(TaskRetryPolicy.MAX_ATTEMPTS - 1); // 下一次就達上限

        FoodLogEntity log = new FoodLogEntity();
        log.setId("log3");
        log.setStatus(FoodLogStatus.PENDING);
        log.setImageObjectKey("user-1/food-log/log3/original.jpg");

        Mockito.when(taskRepo.claimRunnableForUpdate(any(Instant.class), anyInt())).thenReturn(List.of(task));
        Mockito.when(logRepo.findByIdForUpdate("log3")).thenReturn(log);
        Mockito.when(provider.process(eq("log3"), anyString(), eq(storage)))
                .thenThrow(new RuntimeException("always fail"));

        FoodLogTaskWorker worker = new FoodLogTaskWorker(taskRepo, logRepo, provider, storage);
        worker.runOnce();

        assertEquals(FoodLogTaskEntity.TaskStatus.CANCELLED, task.getTaskStatus());
        assertNull(task.getNextRetryAtUtc());
        assertEquals(TaskRetryPolicy.MAX_ATTEMPTS, task.getAttempts()); // markRunning 後 +1
        assertEquals("PROVIDER_GIVE_UP", log.getLastErrorCode());
    }
}
