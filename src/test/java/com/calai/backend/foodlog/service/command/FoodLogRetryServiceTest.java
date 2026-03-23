package com.calai.backend.foodlog.service.command;

import com.calai.backend.entitlement.service.EntitlementService;
import com.calai.backend.foodlog.dto.FoodLogEnvelope;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.entity.FoodLogTaskEntity;
import com.calai.backend.foodlog.model.FoodLogErrorCode;
import com.calai.backend.foodlog.model.FoodLogStatus;
import com.calai.backend.foodlog.quota.guard.AbuseGuardService;
import com.calai.backend.foodlog.quota.model.ModelTier;
import com.calai.backend.foodlog.quota.service.QuotaService;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.repo.FoodLogTaskRepository;
import com.calai.backend.foodlog.service.limiter.UserRateLimiter;
import com.calai.backend.foodlog.service.support.FoodLogEnvelopeAssembler;
import com.calai.backend.foodlog.web.error.FoodLogAppException;
import com.calai.backend.foodlog.web.error.RateLimitedException;
import com.calai.backend.foodlog.web.error.SubscriptionRequiredException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FoodLogRetryServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-03T10:00:00Z");

    private FoodLogRepository repo;
    private FoodLogTaskRepository taskRepo;
    private EntitlementService entitlementService;
    private UserRateLimiter rateLimiter;
    private AbuseGuardService abuseGuard;
    private QuotaService quota;
    private FoodLogEnvelopeAssembler envelopeAssembler;

    private Clock clock;
    private FoodLogRetryService service;

    @BeforeEach
    void setUp() {
        repo = mock(FoodLogRepository.class);
        taskRepo = mock(FoodLogTaskRepository.class);
        entitlementService = mock(EntitlementService.class);
        rateLimiter = mock(UserRateLimiter.class);
        abuseGuard = mock(AbuseGuardService.class);
        quota = mock(QuotaService.class);
        envelopeAssembler = mock(FoodLogEnvelopeAssembler.class);

        clock = Clock.fixed(NOW, ZoneOffset.UTC);

        service = new FoodLogRetryService(
                repo,
                taskRepo,
                entitlementService,
                rateLimiter,
                abuseGuard,
                quota,
                envelopeAssembler,
                clock
        );
    }

    @Test
    void retry_should_reset_failed_photo_and_requeue_existing_task() {
        Long userId = 1L;
        String foodLogId = "log-1";
        String requestId = "req-1";

        FoodLogEntity log = failedLog(foodLogId, userId, "PHOTO");
        FoodLogTaskEntity task = existingTask("task-1", foodLogId);
        FoodLogEnvelope expected = mock(FoodLogEnvelope.class);

        when(repo.findByIdForUpdate(foodLogId)).thenReturn(Optional.of(log));
        when(taskRepo.findByFoodLogIdForUpdate(foodLogId)).thenReturn(Optional.of(task));

        when(entitlementService.resolveTier(userId, NOW)).thenReturn(EntitlementService.Tier.TRIAL);
        when(quota.consumeOperationOrThrow(userId, EntitlementService.Tier.TRIAL, ZoneOffset.UTC, NOW))
                .thenReturn(new QuotaService.Decision(ModelTier.MODEL_TIER_HIGH));

        when(envelopeAssembler.assemble(log, task, requestId)).thenReturn(expected);

        FoodLogEnvelope result = service.retry(userId, foodLogId, null, requestId);

        assertThat(result).isSameAs(expected);
        assertThat(log.getStatus()).isEqualTo(FoodLogStatus.PENDING);
        assertThat(log.getEffective()).isNull();
        assertThat(log.getLastErrorCode()).isNull();
        assertThat(log.getLastErrorMessage()).isNull();
        assertThat(log.getDegradeLevel()).isEqualTo("DG-0");

        assertThat(task.getTaskStatus()).isEqualTo(FoodLogTaskEntity.TaskStatus.QUEUED);
        assertThat(task.getNextRetryAtUtc()).isNull();
        assertThat(task.getPollAfterSec()).isEqualTo(2);
        assertThat(task.getAttempts()).isEqualTo(0);
        assertThat(task.getLastErrorCode()).isNull();
        assertThat(task.getLastErrorMessage()).isNull();

        verify(entitlementService).resolveTier(userId, NOW);
        verify(rateLimiter).checkOrThrow(userId, EntitlementService.Tier.TRIAL, NOW);
        verify(abuseGuard).onOperationAttempt(userId, "uid-1", false, NOW, ZoneOffset.UTC);
        verify(quota).consumeOperationOrThrow(userId, EntitlementService.Tier.TRIAL, ZoneOffset.UTC, NOW);
        verify(taskRepo).save(task);
        verify(repo).save(log);
        verify(envelopeAssembler).assemble(log, task, requestId);
    }

    @Test
    void retry_should_create_task_when_missing() {
        Long userId = 1L;
        String foodLogId = "log-2";
        String requestId = "req-2";

        FoodLogEntity log = failedLog(foodLogId, userId, "LABEL");
        AtomicReference<FoodLogTaskEntity> savedTaskRef = new AtomicReference<>();
        FoodLogEnvelope expected = mock(FoodLogEnvelope.class);

        when(repo.findByIdForUpdate(foodLogId)).thenReturn(Optional.of(log));
        when(taskRepo.findByFoodLogIdForUpdate(foodLogId)).thenReturn(Optional.empty());

        when(entitlementService.resolveTier(userId, NOW)).thenReturn(EntitlementService.Tier.MONTHLY);
        when(quota.consumeOperationOrThrow(userId, EntitlementService.Tier.MONTHLY, ZoneOffset.UTC, NOW))
                .thenReturn(new QuotaService.Decision(ModelTier.MODEL_TIER_LOW));

        when(taskRepo.save(any(FoodLogTaskEntity.class))).thenAnswer(inv -> {
            FoodLogTaskEntity t = inv.getArgument(0);
            t.setId("task-new");
            savedTaskRef.set(t);
            return t;
        });

        when(envelopeAssembler.assemble(eq(log), any(FoodLogTaskEntity.class), eq(requestId)))
                .thenReturn(expected);

        FoodLogEnvelope result = service.retry(userId, foodLogId, "device-abc", requestId);

        assertThat(result).isSameAs(expected);

        FoodLogTaskEntity savedTask = savedTaskRef.get();
        assertThat(savedTask).isNotNull();
        assertThat(savedTask.getFoodLogId()).isEqualTo(foodLogId);
        assertThat(savedTask.getTaskStatus()).isEqualTo(FoodLogTaskEntity.TaskStatus.QUEUED);
        assertThat(savedTask.getPollAfterSec()).isEqualTo(2);
        assertThat(savedTask.getAttempts()).isEqualTo(0);
        assertThat(savedTask.getNextRetryAtUtc()).isNull();

        assertThat(log.getStatus()).isEqualTo(FoodLogStatus.PENDING);
        assertThat(log.getDegradeLevel()).isEqualTo("DG-2");

        verify(abuseGuard).onOperationAttempt(userId, "device-abc", false, NOW, ZoneOffset.UTC);
        verify(taskRepo).save(any(FoodLogTaskEntity.class));
        verify(repo).save(log);
        verify(envelopeAssembler).assemble(eq(log), any(FoodLogTaskEntity.class), eq(requestId));
    }

    @Test
    void retry_should_throw_when_food_log_deleted() {
        Long userId = 1L;
        String foodLogId = "log-deleted";

        FoodLogEntity log = failedLog(foodLogId, userId, "PHOTO");
        log.setStatus(FoodLogStatus.DELETED);

        when(repo.findByIdForUpdate(foodLogId)).thenReturn(Optional.of(log));

        assertThatThrownBy(() -> service.retry(userId, foodLogId, null, "req-x"))
                .isInstanceOfSatisfying(FoodLogAppException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(FoodLogErrorCode.FOOD_LOG_DELETED);
                    assertThat(ex.code()).isEqualTo("FOOD_LOG_DELETED");
                    assertThat(ex.getMessage()).isEqualTo("FOOD_LOG_DELETED");
                });

        verifyNoInteractions(entitlementService, rateLimiter, abuseGuard, quota, envelopeAssembler);
        verify(taskRepo, never()).save(any());
        verify(repo, never()).save(any(FoodLogEntity.class));
    }

    @ParameterizedTest
    @EnumSource(value = FoodLogStatus.class, names = {"DRAFT", "SAVED", "PENDING"})
    void retry_should_throw_when_status_is_not_retryable(FoodLogStatus status) {
        Long userId = 1L;
        String foodLogId = "log-status";

        FoodLogEntity log = failedLog(foodLogId, userId, "PHOTO");
        log.setStatus(status);

        when(repo.findByIdForUpdate(foodLogId)).thenReturn(Optional.of(log));

        assertThatThrownBy(() -> service.retry(userId, foodLogId, null, "req-status"))
                .isInstanceOfSatisfying(FoodLogAppException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(FoodLogErrorCode.FOOD_LOG_NOT_RETRYABLE);
                    assertThat(ex.code()).isEqualTo("FOOD_LOG_NOT_RETRYABLE");
                    assertThat(ex.getMessage()).isEqualTo("FOOD_LOG_NOT_RETRYABLE");
                });

        verifyNoInteractions(entitlementService, rateLimiter, abuseGuard, quota, envelopeAssembler);
    }

    @Test
    void retry_should_throw_when_method_is_barcode() {
        Long userId = 1L;
        String foodLogId = "log-barcode";

        FoodLogEntity log = failedLog(foodLogId, userId, "BARCODE");

        when(repo.findByIdForUpdate(foodLogId)).thenReturn(Optional.of(log));

        assertThatThrownBy(() -> service.retry(userId, foodLogId, null, "req-barcode"))
                .isInstanceOfSatisfying(FoodLogAppException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(FoodLogErrorCode.FOOD_LOG_NOT_RETRYABLE);
                    assertThat(ex.code()).isEqualTo("FOOD_LOG_NOT_RETRYABLE");
                    assertThat(ex.getMessage()).isEqualTo("FOOD_LOG_NOT_RETRYABLE");
                });

        verifyNoInteractions(entitlementService, rateLimiter, abuseGuard, quota, envelopeAssembler);
    }

    @Test
    void retry_should_propagate_rate_limited_and_stop_before_quota() {
        Long userId = 1L;
        String foodLogId = "log-rate";

        FoodLogEntity log = failedLog(foodLogId, userId, "PHOTO");

        when(repo.findByIdForUpdate(foodLogId)).thenReturn(Optional.of(log));
        when(entitlementService.resolveTier(userId, NOW)).thenReturn(EntitlementService.Tier.TRIAL);

        doThrow(new RateLimitedException("RATE_LIMITED", 12, "RETRY_LATER"))
                .when(rateLimiter).checkOrThrow(userId, EntitlementService.Tier.TRIAL, NOW);

        assertThatThrownBy(() -> service.retry(userId, foodLogId, "device-x", "req-rate"))
                .isInstanceOf(RateLimitedException.class)
                .hasMessage("RATE_LIMITED");

        verify(entitlementService).resolveTier(userId, NOW);
        verify(rateLimiter).checkOrThrow(userId, EntitlementService.Tier.TRIAL, NOW);
        verifyNoInteractions(abuseGuard, quota, envelopeAssembler);
        verify(taskRepo, never()).save(any());
        verify(repo, never()).save(any(FoodLogEntity.class));
    }

    @Test
    void retry_should_propagate_subscription_required_and_not_save_anything() {
        Long userId = 1L;
        String foodLogId = "log-sub";

        FoodLogEntity log = failedLog(foodLogId, userId, "PHOTO");

        when(repo.findByIdForUpdate(foodLogId)).thenReturn(Optional.of(log));
        when(entitlementService.resolveTier(userId, NOW)).thenReturn(EntitlementService.Tier.NONE);

        doThrow(new SubscriptionRequiredException("SUBSCRIPTION_REQUIRED"))
                .when(quota).consumeOperationOrThrow(userId, EntitlementService.Tier.NONE, ZoneOffset.UTC, NOW);

        assertThatThrownBy(() -> service.retry(userId, foodLogId, "device-y", "req-sub"))
                .isInstanceOf(SubscriptionRequiredException.class)
                .hasMessage("SUBSCRIPTION_REQUIRED");

        verify(entitlementService).resolveTier(userId, NOW);
        verify(rateLimiter).checkOrThrow(userId, EntitlementService.Tier.NONE, NOW);
        verify(abuseGuard).onOperationAttempt(userId, "device-y", false, NOW, ZoneOffset.UTC);
        verify(quota).consumeOperationOrThrow(userId, EntitlementService.Tier.NONE, ZoneOffset.UTC, NOW);

        verify(taskRepo, never()).save(any());
        verify(repo, never()).save(any(FoodLogEntity.class));
        verifyNoInteractions(envelopeAssembler);
    }

    private static FoodLogEntity failedLog(String id, Long userId, String method) {
        FoodLogEntity e = new FoodLogEntity();
        e.setId(id);
        e.setUserId(userId);
        e.setMethod(method);
        e.setProvider("GEMINI");
        e.setStatus(FoodLogStatus.FAILED);
        e.setDegradeLevel("DG-2");
        e.setLastErrorCode("PROVIDER_TIMEOUT");
        e.setLastErrorMessage("old error");
        e.setCapturedAtUtc(NOW.minusSeconds(60));
        e.setServerReceivedAtUtc(NOW.minusSeconds(55));
        return e;
    }

    private static FoodLogTaskEntity existingTask(String id, String foodLogId) {
        FoodLogTaskEntity t = new FoodLogTaskEntity();
        t.setId(id);
        t.setFoodLogId(foodLogId);
        t.setTaskStatus(FoodLogTaskEntity.TaskStatus.FAILED);
        t.setPollAfterSec(9);
        t.setAttempts(3);
        t.setLastErrorCode("OLD_TASK_ERROR");
        t.setLastErrorMessage("OLD_TASK_MESSAGE");
        return t;
    }
}
