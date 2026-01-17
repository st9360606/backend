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
import com.calai.backend.foodlog.task.ProviderRouter;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;

class FoodLogTaskWorkerTest {

    @Test
    void deleted_log_should_cancel_without_calling_provider() throws Exception {
        FoodLogTaskRepository taskRepo = Mockito.mock(FoodLogTaskRepository.class);
        FoodLogRepository logRepo = Mockito.mock(FoodLogRepository.class);
        ProviderRouter router = Mockito.mock(ProviderRouter.class);     // ✅ 改這裡
        ProviderClient provider = Mockito.mock(ProviderClient.class);   // ✅ 仍可保留，用於 verify
        StorageService storage = Mockito.mock(StorageService.class);

        FoodLogTaskEntity task = new FoodLogTaskEntity();
        task.setId("t1");
        task.setFoodLogId("log1");
        task.setTaskStatus(FoodLogTaskEntity.TaskStatus.QUEUED);

        FoodLogEntity log = new FoodLogEntity();
        log.setId("log1");
        log.setStatus(FoodLogStatus.DELETED);

        Mockito.when(taskRepo.claimRunnableForUpdate(any(Instant.class), anyInt()))
                .thenReturn(List.of(task));
        Mockito.when(logRepo.findByIdForUpdate("log1"))
                .thenReturn(log);

        // ✅ 注意：DELETED 會在 pick 前就 continue，所以不用 stub router.pick
        FoodLogTaskWorker worker = new FoodLogTaskWorker(taskRepo, logRepo, router, storage);
        worker.runOnce();

        assertEquals(FoodLogTaskEntity.TaskStatus.CANCELLED, task.getTaskStatus());

        Mockito.verify(provider, never()).process(any(FoodLogEntity.class), any(StorageService.class));
        Mockito.verify(router, never()).pick(any()); // ✅ 甚至可加這個
    }


    @Test
    void transient_failure_should_schedule_retry() throws Exception {
        FoodLogTaskRepository taskRepo = Mockito.mock(FoodLogTaskRepository.class);
        FoodLogRepository logRepo = Mockito.mock(FoodLogRepository.class);

        ProviderRouter router = Mockito.mock(ProviderRouter.class);     // ✅
        ProviderClient provider = Mockito.mock(ProviderClient.class);   // ✅
        StorageService storage = Mockito.mock(StorageService.class);

        FoodLogTaskEntity task = new FoodLogTaskEntity();
        task.setId("t2");
        task.setFoodLogId("log2");
        task.setTaskStatus(FoodLogTaskEntity.TaskStatus.QUEUED);

        FoodLogEntity log = new FoodLogEntity();
        log.setId("log2");
        log.setStatus(FoodLogStatus.PENDING);
        log.setImageObjectKey("user-1/food-log/log2/original.jpg");

        Mockito.when(taskRepo.claimRunnableForUpdate(any(Instant.class), anyInt()))
                .thenReturn(List.of(task));
        Mockito.when(logRepo.findByIdForUpdate("log2"))
                .thenReturn(log);

        // ✅ router.pick(log) 回 provider
        Mockito.when(router.pick(eq(log))).thenReturn(provider);

        // ✅ provider.process 丟錯
        Mockito.when(provider.process(eq(log), eq(storage)))
                .thenThrow(new RuntimeException("boom"));

        FoodLogTaskWorker worker = new FoodLogTaskWorker(taskRepo, logRepo, router, storage);
        worker.runOnce();

        assertEquals(FoodLogTaskEntity.TaskStatus.FAILED, task.getTaskStatus());
        assertNotNull(task.getNextRetryAtUtc());
        assertEquals(1, task.getAttempts());
        assertEquals(FoodLogStatus.FAILED, log.getStatus());
    }

    @Test
    void reach_max_attempts_should_give_up() throws Exception {
        FoodLogTaskRepository taskRepo = Mockito.mock(FoodLogTaskRepository.class);
        FoodLogRepository logRepo = Mockito.mock(FoodLogRepository.class);

        ProviderRouter router = Mockito.mock(ProviderRouter.class);     // ✅
        ProviderClient provider = Mockito.mock(ProviderClient.class);   // ✅
        StorageService storage = Mockito.mock(StorageService.class);

        FoodLogTaskEntity task = new FoodLogTaskEntity();
        task.setId("t3");
        task.setFoodLogId("log3");
        task.setTaskStatus(FoodLogTaskEntity.TaskStatus.QUEUED);
        task.setAttempts(TaskRetryPolicy.MAX_ATTEMPTS - 1);

        FoodLogEntity log = new FoodLogEntity();
        log.setId("log3");
        log.setStatus(FoodLogStatus.PENDING);
        log.setImageObjectKey("user-1/food-log/log3/original.jpg");

        Mockito.when(taskRepo.claimRunnableForUpdate(any(Instant.class), anyInt()))
                .thenReturn(List.of(task));
        Mockito.when(logRepo.findByIdForUpdate("log3"))
                .thenReturn(log);

        Mockito.when(router.pick(eq(log))).thenReturn(provider);

        Mockito.when(provider.process(eq(log), eq(storage)))
                .thenThrow(new RuntimeException("always fail"));

        FoodLogTaskWorker worker = new FoodLogTaskWorker(taskRepo, logRepo, router, storage);
        worker.runOnce();

        assertEquals(FoodLogTaskEntity.TaskStatus.CANCELLED, task.getTaskStatus());
        assertNull(task.getNextRetryAtUtc());
        assertEquals(TaskRetryPolicy.MAX_ATTEMPTS, task.getAttempts());
        assertEquals("PROVIDER_GIVE_UP", log.getLastErrorCode());
    }
}
