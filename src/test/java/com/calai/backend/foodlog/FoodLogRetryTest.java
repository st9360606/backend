package com.calai.backend.foodlog.service;

import com.calai.backend.entitlement.service.EntitlementService;
import com.calai.backend.foodlog.barcode.BarcodeLookupService;
import com.calai.backend.foodlog.dto.FoodLogEnvelope;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.entity.FoodLogTaskEntity;
import com.calai.backend.foodlog.mapper.ClientActionMapper;
import com.calai.backend.foodlog.model.FoodLogStatus;
import com.calai.backend.foodlog.quota.guard.AbuseGuardService;
import com.calai.backend.foodlog.quota.model.ModelTier;
import com.calai.backend.foodlog.quota.service.QuotaService;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.repo.FoodLogTaskRepository;
import com.calai.backend.foodlog.service.limiter.UserInFlightLimiter;
import com.calai.backend.foodlog.service.limiter.UserRateLimiter;
import com.calai.backend.foodlog.service.request.IdempotencyService;
import com.calai.backend.foodlog.storage.StorageService;
import com.calai.backend.foodlog.processing.effective.FoodLogEffectivePostProcessor;
import com.calai.backend.foodlog.provider.spi.ProviderClient;
import com.calai.backend.foodlog.web.error.RateLimitedException;
import com.calai.backend.foodlog.web.error.SubscriptionRequiredException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FoodLogServiceRetryTest {

    private static final Instant NOW = Instant.parse("2026-03-03T10:00:00Z");

    private ProviderClient providerClient;
    private FoodLogRepository repo;
    private FoodLogTaskRepository taskRepo;
    private StorageService storage;
    private QuotaService quota;
    private IdempotencyService idem;
    private ImageBlobService blobService;
    private UserInFlightLimiter inFlight;
    private UserRateLimiter rateLimiter;
    private FoodLogEffectivePostProcessor postProcessor;
    private ClientActionMapper clientActionMapper;
    private AbuseGuardService abuseGuard;
    private EntitlementService entitlementService;
    private BarcodeLookupService barcodeLookupService;
    private TransactionTemplate txTemplate;

    private Clock clock;
    private FoodLogService service;

    @BeforeEach
    void setUp() {
        providerClient = mock(ProviderClient.class);
        repo = mock(FoodLogRepository.class);
        taskRepo = mock(FoodLogTaskRepository.class);
        storage = mock(StorageService.class);
        quota = mock(QuotaService.class);
        idem = mock(IdempotencyService.class);
        blobService = mock(ImageBlobService.class);
        inFlight = mock(UserInFlightLimiter.class);
        rateLimiter = mock(UserRateLimiter.class);
        postProcessor = mock(FoodLogEffectivePostProcessor.class);
        clientActionMapper = mock(ClientActionMapper.class);
        abuseGuard = mock(AbuseGuardService.class);
        entitlementService = mock(EntitlementService.class);
        barcodeLookupService = mock(BarcodeLookupService.class);
        txTemplate = mock(TransactionTemplate.class);

        clock = Clock.fixed(NOW, ZoneOffset.UTC);

        service = new FoodLogService(
                providerClient,
                repo,
                taskRepo,
                storage,
                quota,
                idem,
                blobService,
                inFlight,
                rateLimiter,
                postProcessor,
                clientActionMapper,
                clock,
                abuseGuard,
                entitlementService,
                barcodeLookupService,
                txTemplate
        );
    }

    @Test
    void retry_should_reset_failed_photo_and_requeue_existing_task() {
        Long userId = 1L;
        String foodLogId = "log-1";
        String requestId = "req-1";

        FoodLogEntity log = failedLog(foodLogId, userId, "PHOTO");
        FoodLogTaskEntity task = existingTask("task-1", foodLogId);

        when(repo.findByIdForUpdate(foodLogId)).thenReturn(Optional.of(log));
        when(taskRepo.findByFoodLogIdForUpdate(foodLogId)).thenReturn(Optional.of(task));

        when(entitlementService.resolveTier(userId, NOW)).thenReturn(EntitlementService.Tier.TRIAL);
        when(quota.consumeOperationOrThrow(userId, EntitlementService.Tier.TRIAL, ZoneOffset.UTC, NOW))
                .thenReturn(new QuotaService.Decision(ModelTier.MODEL_TIER_HIGH));

        FoodLogEnvelope result = service.retry(userId, foodLogId, null, requestId);

        assertThat(result).isNotNull();

        // log 應被重置為可再次處理
        assertThat(log.getStatus()).isEqualTo(FoodLogStatus.PENDING);
        assertThat(log.getEffective()).isNull();
        assertThat(log.getLastErrorCode()).isNull();
        assertThat(log.getLastErrorMessage()).isNull();
        assertThat(log.getDegradeLevel()).isEqualTo("DG-0");

        // task 應被重排
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
    }

    @Test
    void retry_should_create_task_when_missing() {
        Long userId = 1L;
        String foodLogId = "log-2";
        String requestId = "req-2";

        FoodLogEntity log = failedLog(foodLogId, userId, "LABEL");
        AtomicReference<FoodLogTaskEntity> savedTaskRef = new AtomicReference<>();

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

        FoodLogEnvelope result = service.retry(userId, foodLogId, "device-abc", requestId);

        assertThat(result).isNotNull();

        FoodLogTaskEntity savedTask = savedTaskRef.get();
        assertThat(savedTask).isNotNull();
        assertThat(savedTask.getFoodLogId()).isEqualTo(foodLogId);
        assertThat(savedTask.getTaskStatus()).isEqualTo(FoodLogTaskEntity.TaskStatus.QUEUED);
        assertThat(savedTask.getPollAfterSec()).isEqualTo(2);
        assertThat(savedTask.getAttempts()).isEqualTo(0);
        assertThat(savedTask.getNextRetryAtUtc()).isNull();
        assertThat(savedTask.getLastErrorCode()).isNull();
        assertThat(savedTask.getLastErrorMessage()).isNull();

        assertThat(log.getStatus()).isEqualTo(FoodLogStatus.PENDING);
        assertThat(log.getDegradeLevel()).isEqualTo("DG-2"); // LOW tier
        assertThat(log.getEffective()).isNull();
        assertThat(log.getLastErrorCode()).isNull();
        assertThat(log.getLastErrorMessage()).isNull();

        verify(abuseGuard).onOperationAttempt(userId, "device-abc", false, NOW, ZoneOffset.UTC);
        verify(taskRepo).save(any(FoodLogTaskEntity.class));
        verify(repo).save(log);
    }

    @Test
    void retry_should_throw_when_food_log_deleted() {
        Long userId = 1L;
        String foodLogId = "log-deleted";

        FoodLogEntity log = failedLog(foodLogId, userId, "PHOTO");
        log.setStatus(FoodLogStatus.DELETED);

        when(repo.findByIdForUpdate(foodLogId)).thenReturn(Optional.of(log));

        assertThatThrownBy(() -> service.retry(userId, foodLogId, null, "req-x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("FOOD_LOG_DELETED");

        verifyNoInteractions(entitlementService, rateLimiter, abuseGuard, quota);
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
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("FOOD_LOG_NOT_RETRYABLE");

        verifyNoInteractions(entitlementService, rateLimiter, abuseGuard, quota);
        verify(taskRepo, never()).save(any());
        verify(repo, never()).save(any(FoodLogEntity.class));
    }

    @Test
    void retry_should_throw_when_method_is_barcode() {
        Long userId = 1L;
        String foodLogId = "log-barcode";

        FoodLogEntity log = failedLog(foodLogId, userId, "BARCODE");

        when(repo.findByIdForUpdate(foodLogId)).thenReturn(Optional.of(log));

        assertThatThrownBy(() -> service.retry(userId, foodLogId, null, "req-barcode"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("FOOD_LOG_NOT_RETRYABLE");

        verifyNoInteractions(entitlementService, rateLimiter, abuseGuard, quota);
        verify(taskRepo, never()).save(any());
        verify(repo, never()).save(any(FoodLogEntity.class));
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
        verifyNoInteractions(abuseGuard, quota);
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

        doNothing().when(rateLimiter).checkOrThrow(userId, EntitlementService.Tier.NONE, NOW);

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
