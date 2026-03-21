package com.calai.backend.foodlog.job.worker;

import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.entity.FoodLogTaskEntity;
import com.calai.backend.foodlog.model.FoodLogStatus;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.repo.FoodLogTaskRepository;
import com.calai.backend.foodlog.storage.StorageService;
import com.calai.backend.foodlog.processing.effective.FoodLogEffectivePostProcessor;
import com.calai.backend.foodlog.provider.spi.ProviderClient;
import com.calai.backend.foodlog.provider.routing.ProviderRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FoodLogTaskWorkerTest {

    private final Clock clock = Clock.fixed(
            Instant.parse("2026-03-03T00:00:00Z"),
            ZoneOffset.UTC
    );

    private PlatformTransactionManager newTxManager() {
        PlatformTransactionManager txManager = Mockito.mock(PlatformTransactionManager.class);

        Mockito.when(txManager.getTransaction(any()))
                .thenReturn(new SimpleTransactionStatus());

        Mockito.doNothing().when(txManager).commit(any(TransactionStatus.class));
        Mockito.doNothing().when(txManager).rollback(any(TransactionStatus.class));

        return txManager;
    }

    @Test
    void deleted_log_should_cancel_without_calling_provider() {
        FoodLogTaskRepository taskRepo = Mockito.mock(FoodLogTaskRepository.class);
        FoodLogRepository logRepo = Mockito.mock(FoodLogRepository.class);
        ProviderRouter router = Mockito.mock(ProviderRouter.class);
        StorageService storage = Mockito.mock(StorageService.class);
        FoodLogEffectivePostProcessor postProcessor = Mockito.mock(FoodLogEffectivePostProcessor.class);
        PlatformTransactionManager txManager = newTxManager();

        FoodLogTaskEntity task = new FoodLogTaskEntity();
        task.setId("t1");
        task.setFoodLogId("log1");
        task.setTaskStatus(FoodLogTaskEntity.TaskStatus.QUEUED);
        task.setCreatedAtUtc(clock.instant());
        task.setUpdatedAtUtc(clock.instant());

        FoodLogEntity log = new FoodLogEntity();
        log.setId("log1");
        log.setStatus(FoodLogStatus.DELETED);

        Mockito.when(taskRepo.claimRunnableForUpdate(any(Instant.class), anyInt()))
                .thenReturn(List.of(task));
        Mockito.when(taskRepo.findByIdForUpdate("t1"))
                .thenReturn(Optional.of(task));
        Mockito.when(logRepo.findByIdForUpdate("log1"))
                .thenReturn(Optional.of(log));

        FoodLogTaskWorker worker = new FoodLogTaskWorker(
                taskRepo, logRepo, router, storage, postProcessor, txManager, clock
        );
        worker.runOnce();

        assertEquals(FoodLogTaskEntity.TaskStatus.CANCELLED, task.getTaskStatus());

        Mockito.verify(router, never()).pick(any());
        Mockito.verify(router, never()).pickStrict(any());
        Mockito.verifyNoInteractions(postProcessor);
    }

    @Test
    void failure_should_cancel_without_retry() throws Exception {
        FoodLogTaskRepository taskRepo = Mockito.mock(FoodLogTaskRepository.class);
        FoodLogRepository logRepo = Mockito.mock(FoodLogRepository.class);
        ProviderRouter router = Mockito.mock(ProviderRouter.class);
        ProviderClient provider = Mockito.mock(ProviderClient.class);
        StorageService storage = Mockito.mock(StorageService.class);
        FoodLogEffectivePostProcessor postProcessor = Mockito.mock(FoodLogEffectivePostProcessor.class);
        PlatformTransactionManager txManager = newTxManager();

        FoodLogTaskEntity task = new FoodLogTaskEntity();
        task.setId("t2");
        task.setFoodLogId("log2");
        task.setTaskStatus(FoodLogTaskEntity.TaskStatus.QUEUED);
        task.setAttempts(0);
        task.setCreatedAtUtc(clock.instant());
        task.setUpdatedAtUtc(clock.instant());

        FoodLogEntity log = new FoodLogEntity();
        log.setId("log2");
        log.setStatus(FoodLogStatus.PENDING);
        log.setMethod("PHOTO");
        log.setImageObjectKey("user-1/food-log/log2/original.jpg");

        Mockito.when(taskRepo.claimRunnableForUpdate(any(Instant.class), anyInt()))
                .thenReturn(List.of(task));
        Mockito.when(taskRepo.findByIdForUpdate("t2"))
                .thenReturn(Optional.of(task));
        Mockito.when(logRepo.findByIdForUpdate("log2"))
                .thenReturn(Optional.of(log));

        Mockito.when(router.pickStrict(eq(log))).thenReturn(provider);

        Mockito.when(provider.process(eq(log), eq(storage)))
                .thenThrow(new RuntimeException("boom"));

        FoodLogTaskWorker worker = new FoodLogTaskWorker(
                taskRepo, logRepo, router, storage, postProcessor, txManager, clock
        );
        worker.runOnce();

        assertEquals(FoodLogTaskEntity.TaskStatus.CANCELLED, task.getTaskStatus());
        assertNull(task.getNextRetryAtUtc());
        assertEquals(1, task.getAttempts());

        assertEquals(FoodLogStatus.FAILED, log.getStatus());
        assertNotNull(log.getLastErrorCode());
        assertNotNull(log.getLastErrorMessage());

        Mockito.verifyNoInteractions(postProcessor);
    }

    @Test
    void queued_task_with_attempts_already_at_limit_should_cancel_before_provider() {
        FoodLogTaskRepository taskRepo = Mockito.mock(FoodLogTaskRepository.class);
        FoodLogRepository logRepo = Mockito.mock(FoodLogRepository.class);
        ProviderRouter router = Mockito.mock(ProviderRouter.class);
        StorageService storage = Mockito.mock(StorageService.class);
        FoodLogEffectivePostProcessor postProcessor = Mockito.mock(FoodLogEffectivePostProcessor.class);
        PlatformTransactionManager txManager = newTxManager();

        FoodLogTaskEntity task = new FoodLogTaskEntity();
        task.setId("t3");
        task.setFoodLogId("log3");
        task.setTaskStatus(FoodLogTaskEntity.TaskStatus.QUEUED);
        task.setAttempts(1); // maxAttemptsForMethod() 現在固定回 1
        task.setCreatedAtUtc(clock.instant());
        task.setUpdatedAtUtc(clock.instant());

        FoodLogEntity log = new FoodLogEntity();
        log.setId("log3");
        log.setStatus(FoodLogStatus.PENDING);
        log.setMethod("PHOTO");
        log.setImageObjectKey("user-1/food-log/log3/original.jpg");

        Mockito.when(taskRepo.claimRunnableForUpdate(any(Instant.class), anyInt()))
                .thenReturn(List.of(task));
        Mockito.when(taskRepo.findByIdForUpdate("t3"))
                .thenReturn(Optional.of(task));
        Mockito.when(logRepo.findByIdForUpdate("log3"))
                .thenReturn(Optional.of(log));

        FoodLogTaskWorker worker = new FoodLogTaskWorker(
                taskRepo, logRepo, router, storage, postProcessor, txManager, clock
        );
        worker.runOnce();

        assertEquals(FoodLogTaskEntity.TaskStatus.CANCELLED, task.getTaskStatus());
        assertNull(task.getNextRetryAtUtc());
        assertEquals(1, task.getAttempts());

        assertEquals(FoodLogStatus.FAILED, log.getStatus());
        assertEquals("MAX_ATTEMPTS_EXCEEDED", log.getLastErrorCode());

        Mockito.verify(router, never()).pick(any());
        Mockito.verify(router, never()).pickStrict(any());
        Mockito.verifyNoInteractions(postProcessor);
    }

    @Test
    void success_should_apply_post_processor_and_mark_draft() throws Exception {
        FoodLogTaskRepository taskRepo = Mockito.mock(FoodLogTaskRepository.class);
        FoodLogRepository logRepo = Mockito.mock(FoodLogRepository.class);
        ProviderRouter router = Mockito.mock(ProviderRouter.class);
        ProviderClient provider = Mockito.mock(ProviderClient.class);
        StorageService storage = Mockito.mock(StorageService.class);
        FoodLogEffectivePostProcessor postProcessor = Mockito.mock(FoodLogEffectivePostProcessor.class);
        PlatformTransactionManager txManager = newTxManager();

        FoodLogTaskEntity task = new FoodLogTaskEntity();
        task.setId("t4");
        task.setFoodLogId("log4");
        task.setTaskStatus(FoodLogTaskEntity.TaskStatus.QUEUED);
        task.setAttempts(0);
        task.setCreatedAtUtc(clock.instant());
        task.setUpdatedAtUtc(clock.instant());

        FoodLogEntity log = new FoodLogEntity();
        log.setId("log4");
        log.setStatus(FoodLogStatus.PENDING);
        log.setMethod("PHOTO");
        log.setImageObjectKey("user-1/food-log/log4/original.jpg");

        Mockito.when(taskRepo.claimRunnableForUpdate(any(Instant.class), anyInt()))
                .thenReturn(List.of(task));
        Mockito.when(taskRepo.findByIdForUpdate("t4"))
                .thenReturn(Optional.of(task));
        Mockito.when(logRepo.findByIdForUpdate("log4"))
                .thenReturn(Optional.of(log));

        Mockito.when(router.pickStrict(eq(log))).thenReturn(provider);

        ObjectMapper om = new ObjectMapper();
        ObjectNode eff = (ObjectNode) om.readTree("""
        {
          "foodName":"White Bread",
          "quantity":{"value":1,"unit":"SERVING"},
          "nutrients":{"kcal":75,"protein":2.5,"fat":1,"carbs":14,"fiber":0.8,"sugar":1.5,"sodium":140},
          "confidence":0.9
        }
        """);

        Mockito.when(provider.process(eq(log), eq(storage)))
                .thenReturn(new ProviderClient.ProviderResult(eff, "GEMINI"));

        Mockito.when(postProcessor.apply(any(ObjectNode.class), eq("GEMINI"), eq("PHOTO")))
                .thenAnswer(inv -> inv.getArgument(0));

        FoodLogTaskWorker worker = new FoodLogTaskWorker(
                taskRepo, logRepo, router, storage, postProcessor, txManager, clock
        );
        worker.runOnce();

        assertEquals(FoodLogTaskEntity.TaskStatus.SUCCEEDED, task.getTaskStatus());
        assertEquals(FoodLogStatus.DRAFT, log.getStatus());
        assertNotNull(log.getEffective());
        assertEquals("GEMINI", log.getProvider());

        Mockito.verify(postProcessor, Mockito.times(1))
                .apply(any(ObjectNode.class), eq("GEMINI"), eq("PHOTO"));
    }
}
