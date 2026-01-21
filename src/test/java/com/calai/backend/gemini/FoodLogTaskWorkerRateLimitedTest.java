package com.calai.backend.foodlog.task;

import com.calai.backend.foodlog.dto.FoodLogStatus;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.entity.FoodLogTaskEntity;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.repo.FoodLogTaskRepository;
import com.calai.backend.foodlog.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mock;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FoodLogTaskWorkerRateLimitedTest {

    @Mock FoodLogTaskRepository taskRepo;
    @Mock FoodLogRepository logRepo;
    @Mock ProviderRouter router;
    @Mock StorageService storage;
    @Mock EffectivePostProcessor postProcessor;
    @Mock ProviderClient providerClient;

    @Test
    void when_429_then_task_cancelled_and_log_failed_and_message_contains_suggestedRetryAfterSec() throws Exception {
        // Arrange: one QUEUED task
        FoodLogTaskEntity task = new FoodLogTaskEntity();
        task.setId("task-1");
        task.setFoodLogId("log-1");
        task.setTaskStatus(FoodLogTaskEntity.TaskStatus.QUEUED);
        task.setCreatedAtUtc(Instant.now());
        task.setUpdatedAtUtc(Instant.now());

        when(taskRepo.claimRunnableForUpdate(any(Instant.class), anyInt()))
                .thenReturn(List.of(task));

        FoodLogEntity log = new FoodLogEntity();
        log.setId("log-1");
        log.setStatus(FoodLogStatus.PENDING);
        log.setImageObjectKey("user-1/blobs/sha256/xxx.jpg");

        when(logRepo.findByIdForUpdate("log-1")).thenReturn(log);

        when(router.pickStrict(log)).thenReturn(providerClient);

        // Simulate 429 body contains retryDelay: "34s"
        String body = """
                {
                  "error": {
                    "code": 429,
                    "message": "Please retry in 34.543919126s.",
                    "status": "RESOURCE_EXHAUSTED",
                    "details": [
                      { "@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "34s" }
                    ]
                  }
                }
                """;

        HttpClientErrorException ex = HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests",
                HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8
        );

        when(providerClient.process(any(FoodLogEntity.class), any(StorageService.class)))
                .thenThrow(ex);

        FoodLogTaskWorker worker = new FoodLogTaskWorker(taskRepo, logRepo, router, storage, postProcessor);

        // Act
        worker.runOnce();

        // Assert: task cancelled
        assertThat(task.getTaskStatus()).isEqualTo(FoodLogTaskEntity.TaskStatus.CANCELLED);
        assertThat(task.getLastErrorCode()).isEqualTo("PROVIDER_RATE_LIMITED");
        assertThat(task.getLastErrorMessage()).contains("suggestedRetryAfterSec=34");
        assertThat(task.getNextRetryAtUtc()).isNull();

        // Assert: log failed
        assertThat(log.getStatus()).isEqualTo(FoodLogStatus.FAILED);
        assertThat(log.getLastErrorCode()).isEqualTo("PROVIDER_RATE_LIMITED");
        assertThat(log.getLastErrorMessage()).contains("suggestedRetryAfterSec=34");

        // 並且 postProcessor 不應被呼叫（因為失敗）
        verify(postProcessor, never()).apply(any(), anyString());
    }
}
