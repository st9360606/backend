package com.calai.backend.foodlog.job.worker;

import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.entity.FoodLogTaskEntity;
import com.calai.backend.foodlog.model.FoodLogStatus;
import com.calai.backend.foodlog.processing.effective.FoodLogEffectivePostProcessor;
import com.calai.backend.foodlog.provider.routing.ProviderRouter;
import com.calai.backend.foodlog.provider.spi.ProviderClient;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.repo.FoodLogTaskRepository;
import com.calai.backend.foodlog.service.UserDailyNutritionSummaryService;
import com.calai.backend.foodlog.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FoodLogTaskWorkerRateLimitedTest {

    @Mock FoodLogTaskRepository taskRepo;
    @Mock FoodLogRepository logRepo;
    @Mock ProviderRouter router;
    @Mock StorageService storage;
    @Mock FoodLogEffectivePostProcessor postProcessor;
    @Mock UserDailyNutritionSummaryService dailySummaryService;
    @Mock ProviderClient providerClient;
    @Mock PlatformTransactionManager txManager;

    @Test
    void when_429_then_task_cancelled_and_log_failed_and_message_contains_suggestedRetryAfterSec() throws Exception {
        // Arrange
        Instant now = Instant.parse("2026-03-03T00:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);

        // TransactionTemplate 需要 getTransaction 有回傳即可
        SimpleTransactionStatus txStatus = new SimpleTransactionStatus();
        when(txManager.getTransaction(any())).thenReturn(txStatus);

        FoodLogTaskEntity task = new FoodLogTaskEntity();
        task.setId("task-1");
        task.setFoodLogId("log-1");
        task.setTaskStatus(FoodLogTaskEntity.TaskStatus.QUEUED);
        task.setCreatedAtUtc(now);
        task.setUpdatedAtUtc(now);
        task.setAttempts(0);

        when(taskRepo.claimRunnableForUpdate(any(Instant.class), anyInt()))
                .thenReturn(List.of(task));
        when(taskRepo.findByIdForUpdate("task-1"))
                .thenReturn(Optional.of(task));

        FoodLogEntity log = new FoodLogEntity();
        log.setId("log-1");
        log.setUserId(1L);
        log.setCapturedLocalDate(java.time.LocalDate.of(2026, 3, 3));
        log.setStatus(FoodLogStatus.PENDING);
        log.setMethod("PHOTO");
        log.setImageObjectKey("user-1/blobs/sha256/xxx.jpg");

        when(logRepo.findByIdForUpdate("log-1"))
                .thenReturn(Optional.of(log));

        when(router.pickStrict(any(FoodLogEntity.class))).thenReturn(providerClient);

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

        FoodLogTaskWorker worker = new FoodLogTaskWorker(
                taskRepo,
                logRepo,
                router,
                storage,
                postProcessor,
                dailySummaryService,
                txManager,
                clock
        );

        // Act
        worker.runOnce();

        // Assert
        assertThat(task.getTaskStatus()).isEqualTo(FoodLogTaskEntity.TaskStatus.CANCELLED);
        assertThat(task.getLastErrorCode()).isEqualTo("PROVIDER_RATE_LIMITED");
        assertThat(task.getLastErrorMessage()).contains("suggestedRetryAfterSec=34");
        assertThat(task.getNextRetryAtUtc()).isNull();

        assertThat(log.getStatus()).isEqualTo(FoodLogStatus.FAILED);
        assertThat(log.getLastErrorCode()).isEqualTo("PROVIDER_RATE_LIMITED");
        assertThat(log.getLastErrorMessage()).contains("suggestedRetryAfterSec=34");

        verify(postProcessor, never()).apply(any(), anyString(), anyString());
        verify(dailySummaryService, never()).recomputeDay(any(), any());
    }
}
