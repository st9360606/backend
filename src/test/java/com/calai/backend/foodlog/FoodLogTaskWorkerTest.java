package com.calai.backend.foodlog;

import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.entity.FoodLogTaskEntity;
import com.calai.backend.foodlog.model.FoodLogStatus;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.repo.FoodLogTaskRepository;
import com.calai.backend.foodlog.storage.StorageService;
import com.calai.backend.foodlog.task.EffectivePostProcessor;
import com.calai.backend.foodlog.task.FoodLogTaskWorker;
import com.calai.backend.foodlog.task.ProviderClient;
import com.calai.backend.foodlog.task.ProviderRouter;
import com.calai.backend.foodlog.task.TaskRetryPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;

class FoodLogTaskWorkerTest {

    @Test
    void deleted_log_should_cancel_without_calling_provider() {
        FoodLogTaskRepository taskRepo = Mockito.mock(FoodLogTaskRepository.class);
        FoodLogRepository logRepo = Mockito.mock(FoodLogRepository.class);
        ProviderRouter router = Mockito.mock(ProviderRouter.class);
        StorageService storage = Mockito.mock(StorageService.class);
        EffectivePostProcessor postProcessor = Mockito.mock(EffectivePostProcessor.class);

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

        FoodLogTaskWorker worker = new FoodLogTaskWorker(taskRepo, logRepo, router, storage, postProcessor);
        worker.runOnce();

        assertEquals(FoodLogTaskEntity.TaskStatus.CANCELLED, task.getTaskStatus());

        Mockito.verify(router, never()).pick(any());
        Mockito.verify(router, never()).pickStrict(any());
        Mockito.verifyNoInteractions(postProcessor);
    }

    @Test
    void transient_failure_should_schedule_retry() throws Exception {
        FoodLogTaskRepository taskRepo = Mockito.mock(FoodLogTaskRepository.class);
        FoodLogRepository logRepo = Mockito.mock(FoodLogRepository.class);
        ProviderRouter router = Mockito.mock(ProviderRouter.class);
        ProviderClient provider = Mockito.mock(ProviderClient.class);
        StorageService storage = Mockito.mock(StorageService.class);
        EffectivePostProcessor postProcessor = Mockito.mock(EffectivePostProcessor.class);

        FoodLogTaskEntity task = new FoodLogTaskEntity();
        task.setId("t2");
        task.setFoodLogId("log2");
        task.setTaskStatus(FoodLogTaskEntity.TaskStatus.QUEUED);

        FoodLogEntity log = new FoodLogEntity();
        log.setId("log2");
        log.setStatus(FoodLogStatus.PENDING);
        log.setMethod("PHOTO"); // ✅ 讓 worker 的 method-aware 流程一致
        log.setImageObjectKey("user-1/food-log/log2/original.jpg");

        Mockito.when(taskRepo.claimRunnableForUpdate(any(Instant.class), anyInt()))
                .thenReturn(List.of(task));
        Mockito.when(logRepo.findByIdForUpdate("log2"))
                .thenReturn(log);

        Mockito.when(router.pick(eq(log))).thenReturn(provider);
        Mockito.when(router.pickStrict(eq(log))).thenReturn(provider);

        Mockito.when(provider.process(eq(log), eq(storage)))
                .thenThrow(new RuntimeException("boom"));

        FoodLogTaskWorker worker = new FoodLogTaskWorker(taskRepo, logRepo, router, storage, postProcessor);
        worker.runOnce();

        assertEquals(FoodLogTaskEntity.TaskStatus.FAILED, task.getTaskStatus());
        assertNotNull(task.getNextRetryAtUtc());
        assertEquals(1, task.getAttempts());
        assertEquals(FoodLogStatus.FAILED, log.getStatus());

        Mockito.verifyNoInteractions(postProcessor);
    }

    @Test
    void reach_max_attempts_should_give_up() throws Exception {
        FoodLogTaskRepository taskRepo = Mockito.mock(FoodLogTaskRepository.class);
        FoodLogRepository logRepo = Mockito.mock(FoodLogRepository.class);
        ProviderRouter router = Mockito.mock(ProviderRouter.class);
        ProviderClient provider = Mockito.mock(ProviderClient.class);
        StorageService storage = Mockito.mock(StorageService.class);
        EffectivePostProcessor postProcessor = Mockito.mock(EffectivePostProcessor.class);

        FoodLogTaskEntity task = new FoodLogTaskEntity();
        task.setId("t3");
        task.setFoodLogId("log3");
        task.setTaskStatus(FoodLogTaskEntity.TaskStatus.QUEUED);
        task.setAttempts(TaskRetryPolicy.MAX_ATTEMPTS - 1);

        FoodLogEntity log = new FoodLogEntity();
        log.setId("log3");
        log.setStatus(FoodLogStatus.PENDING);
        log.setMethod("PHOTO"); // ✅ 一致性
        log.setImageObjectKey("user-1/food-log/log3/original.jpg");

        Mockito.when(taskRepo.claimRunnableForUpdate(any(Instant.class), anyInt()))
                .thenReturn(List.of(task));
        Mockito.when(logRepo.findByIdForUpdate("log3"))
                .thenReturn(log);

        Mockito.when(router.pick(eq(log))).thenReturn(provider);
        Mockito.when(router.pickStrict(eq(log))).thenReturn(provider);

        Mockito.when(provider.process(eq(log), eq(storage)))
                .thenThrow(new RuntimeException("always fail"));

        FoodLogTaskWorker worker = new FoodLogTaskWorker(taskRepo, logRepo, router, storage, postProcessor);
        worker.runOnce();

        assertEquals(FoodLogTaskEntity.TaskStatus.CANCELLED, task.getTaskStatus());
        assertNull(task.getNextRetryAtUtc());
        assertEquals(TaskRetryPolicy.MAX_ATTEMPTS, task.getAttempts());

        assertEquals("PROVIDER_GIVE_UP", log.getLastErrorCode());

        Mockito.verifyNoInteractions(postProcessor);
    }

    @Test
    void success_should_apply_post_processor_and_mark_draft() throws Exception {
        FoodLogTaskRepository taskRepo = Mockito.mock(FoodLogTaskRepository.class);
        FoodLogRepository logRepo = Mockito.mock(FoodLogRepository.class);
        ProviderRouter router = Mockito.mock(ProviderRouter.class);
        ProviderClient provider = Mockito.mock(ProviderClient.class);
        StorageService storage = Mockito.mock(StorageService.class);
        EffectivePostProcessor postProcessor = Mockito.mock(EffectivePostProcessor.class);

        FoodLogTaskEntity task = new FoodLogTaskEntity();
        task.setId("t4");
        task.setFoodLogId("log4");
        task.setTaskStatus(FoodLogTaskEntity.TaskStatus.QUEUED);

        FoodLogEntity log = new FoodLogEntity();
        log.setId("log4");
        log.setStatus(FoodLogStatus.PENDING);
        log.setMethod("PHOTO"); // ✅ 很重要：worker 會把 method 傳進 postProcessor.apply(..., method)
        log.setImageObjectKey("user-1/food-log/log4/original.jpg");

        Mockito.when(taskRepo.claimRunnableForUpdate(any(Instant.class), anyInt()))
                .thenReturn(List.of(task));
        Mockito.when(logRepo.findByIdForUpdate("log4"))
                .thenReturn(log);

        Mockito.when(router.pick(eq(log))).thenReturn(provider);
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

        // ✅ 關鍵修正：stub 三參數版本（worker 用的是 apply(eff, provider, method)）
        Mockito.when(postProcessor.apply(any(ObjectNode.class), eq("GEMINI"), eq("PHOTO")))
                .thenAnswer(inv -> inv.getArgument(0));

        FoodLogTaskWorker worker = new FoodLogTaskWorker(taskRepo, logRepo, router, storage, postProcessor);
        worker.runOnce();

        assertEquals(FoodLogTaskEntity.TaskStatus.SUCCEEDED, task.getTaskStatus());
        assertEquals(FoodLogStatus.DRAFT, log.getStatus());
        assertNotNull(log.getEffective());
        assertEquals("GEMINI", log.getProvider());

        Mockito.verify(postProcessor, Mockito.times(1))
                .apply(any(ObjectNode.class), eq("GEMINI"), eq("PHOTO"));
    }
}