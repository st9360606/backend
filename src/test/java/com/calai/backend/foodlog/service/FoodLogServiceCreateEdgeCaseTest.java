package com.calai.backend.foodlog.service;

import com.calai.backend.entitlement.service.EntitlementService;
import com.calai.backend.foodlog.dto.FoodLogEnvelope;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.entity.FoodLogTaskEntity;
import com.calai.backend.foodlog.model.FoodLogStatus;
import com.calai.backend.foodlog.provider.spi.ProviderClient;
import com.calai.backend.foodlog.quota.guard.AbuseGuardService;
import com.calai.backend.foodlog.quota.service.QuotaService;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.repo.FoodLogTaskRepository;
import com.calai.backend.foodlog.service.barcode.FoodLogBarcodeService;
import com.calai.backend.foodlog.service.command.FoodLogRetryService;
import com.calai.backend.foodlog.service.image.FoodLogImageAccessService;
import com.calai.backend.foodlog.service.limiter.UserInFlightLimiter;
import com.calai.backend.foodlog.service.limiter.UserRateLimiter;
import com.calai.backend.foodlog.service.query.FoodLogQueryService;
import com.calai.backend.foodlog.service.request.IdempotencyService;
import com.calai.backend.foodlog.service.support.FoodLogCreateSupport;
import com.calai.backend.foodlog.service.support.FoodLogEnvelopeAssembler;
import com.calai.backend.foodlog.storage.StorageService;
import com.calai.backend.foodlog.time.ExifTimeExtractor;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * createPhoto / createLabel 邊角情境測試
 *
 * 覆蓋：
 * 1. reusable hit 的 effective 不是 object -> fallback miss
 * 2. applyPendingMiss 後若 status = FAILED -> 仍應走 finalize 的非 DRAFT 路徑（建 task + assemble）
 */
@ExtendWith(MockitoExtension.class)
class FoodLogServiceCreateEdgeCaseTest {

    @Mock ProviderClient providerClient;
    @Mock FoodLogRepository repo;
    @Mock FoodLogTaskRepository taskRepo;
    @Mock StorageService storage;
    @Mock QuotaService quota;
    @Mock IdempotencyService idem;
    @Mock UserInFlightLimiter inFlight;
    @Mock UserRateLimiter rateLimiter;
    @Mock Clock clock;
    @Mock AbuseGuardService abuseGuard;
    @Mock EntitlementService entitlementService;
    @Mock FoodLogEnvelopeAssembler envelopeAssembler;
    @Mock FoodLogQueryService queryService;
    @Mock FoodLogImageAccessService imageAccessService;
    @Mock FoodLogRetryService retryService;
    @Mock FoodLogBarcodeService barcodeService;
    @Mock FoodLogCreateSupport createSupport;

    private FoodLogService svc;

    @BeforeEach
    void setUp() {
        svc = new FoodLogService(
                providerClient,
                repo,
                taskRepo,
                storage,
                quota,
                idem,
                inFlight,
                rateLimiter,
                clock,
                abuseGuard,
                entitlementService,
                envelopeAssembler,
                queryService,
                imageAccessService,
                retryService,
                barcodeService,
                createSupport
        );
    }

    @Test
    void createPhoto_should_fallback_to_miss_when_reusable_effective_is_not_object() throws Exception {
        assertNonObjectEffectiveFallsBackToMiss((file, requestId) ->
                svc.createPhoto(
                        1L,
                        "Asia/Taipei",
                        "dev-1",
                        "2026-03-03T01:02:03Z",
                        file,
                        requestId
                )
        );
    }

    @Test
    void createLabel_should_fallback_to_miss_when_reusable_effective_is_not_object() throws Exception {
        assertNonObjectEffectiveFallsBackToMiss((file, requestId) ->
                svc.createLabel(
                        1L,
                        "Asia/Taipei",
                        "dev-1",
                        "2026-03-03T01:02:03Z",
                        file,
                        requestId
                )
        );
    }

    @Test
    void createPhoto_should_create_task_when_status_is_failed_after_pending_miss() throws Exception {
        assertFailedStatusStillCreatesTask((file, requestId) ->
                svc.createPhoto(
                        1L,
                        "Asia/Taipei",
                        "dev-1",
                        "2026-03-03T01:02:03Z",
                        file,
                        requestId
                )
        );
    }

    @Test
    void createLabel_should_create_task_when_status_is_failed_after_pending_miss() throws Exception {
        assertFailedStatusStillCreatesTask((file, requestId) ->
                svc.createLabel(
                        1L,
                        "Asia/Taipei",
                        "dev-1",
                        "2026-03-03T01:02:03Z",
                        file,
                        requestId
                )
        );
    }

    /**
     * reusable hit 存在，但 effective 不是 object：
     * 應被視為 miss，而不是 cache hit。
     *
     * 驗證：
     * - quota 會被扣
     * - applyPendingMiss() 會被呼叫
     * - 建 task
     * - applyCacheHitDraft() 不會被呼叫
     */
    private void assertNonObjectEffectiveFallsBackToMiss(FileInvoker invoker) throws Exception {
        Instant fixedNow = Instant.parse("2026-03-03T00:00:00Z");
        String requestId = "rid-non-object-hit";
        String tempKey = "temp/u1/rid-non-object-hit.jpg";
        String sha256 = "sha-non-object-hit";

        UserInFlightLimiter.Lease lease = new UserInFlightLimiter.Lease(1L, "lease-non-object-hit");

        MultipartFile validFile = new MockMultipartFile(
                "file",
                "meal.jpg",
                "image/jpeg",
                new byte[]{1, 2, 3, 4}
        );

        FoodLogCreateSupport.UploadTempResult upload = mock(FoodLogCreateSupport.UploadTempResult.class);
        StorageService.SaveResult saved = mock(StorageService.SaveResult.class);
        QuotaService.Decision decision = mock(QuotaService.Decision.class);

        FoodLogEntity reusableHit = new FoodLogEntity();
        reusableHit.setId("log-old-1");
        reusableHit.setStatus(FoodLogStatus.DRAFT);
        reusableHit.setEffective(JsonNodeFactory.instance.textNode("not-an-object"));

        FoodLogEntity newEntity = new FoodLogEntity();
        newEntity.setId("log-new-1");

        FoodLogTaskEntity task = new FoodLogTaskEntity();
        task.setFoodLogId("log-new-1");

        FoodLogEnvelope expected = mock(FoodLogEnvelope.class);

        when(clock.instant()).thenReturn(fixedNow);
        when(idem.reserveOrGetExisting(1L, requestId, fixedNow)).thenReturn(null);

        doReturn(EntitlementService.Tier.TRIAL)
                .when(entitlementService)
                .resolveTier(1L, fixedNow);

        doNothing().when(rateLimiter).checkOrThrow(1L, EntitlementService.Tier.TRIAL, fixedNow);
        when(inFlight.acquireOrThrow(1L)).thenReturn(lease);

        when(createSupport.uploadTempImage(1L, requestId, validFile)).thenReturn(upload);
        when(upload.tempKey()).thenReturn(tempKey);
        when(upload.saved()).thenReturn(saved);
        when(saved.sha256()).thenReturn(sha256);

        when(repo.findFirstByUserIdAndMethodInAndImageSha256AndStatusInOrderByCreatedAtUtcDesc(
                eq(1L),
                anyCollection(),
                eq(sha256),
                anyCollection()
        )).thenReturn(Optional.of(reusableHit));

        when(createSupport.newBaseEntity(
                eq(1L),
                anyString(),
                any(Instant.class),
                eq("Asia/Taipei"),
                any(),
                eq(fixedNow),
                any(),
                any(Boolean.class)
        )).thenReturn(newEntity);

        when(quota.consumeOperationOrThrow(
                eq(1L),
                eq(EntitlementService.Tier.TRIAL),
                any(),
                eq(fixedNow)
        )).thenReturn(decision);

        when(providerClient.providerCode()).thenReturn("gemini");

        // fallback miss 路徑：手動把 status 補成 PENDING
        doAnswer(invocation -> {
            FoodLogEntity e = invocation.getArgument(0);
            e.setStatus(FoodLogStatus.PENDING);
            return null;
        }).when(createSupport).applyPendingMiss(
                same(newEntity),
                any(),
                eq("GEMINI")
        );

        when(createSupport.createQueuedTask("log-new-1")).thenReturn(task);
        when(envelopeAssembler.assemble(newEntity, task, requestId)).thenReturn(expected);

        try (MockedStatic<ExifTimeExtractor> exifMock = mockStatic(ExifTimeExtractor.class)) {
            exifMock.when(() -> ExifTimeExtractor.tryReadCapturedAtUtc(
                    storage,
                    tempKey,
                    ZoneId.of("Asia/Taipei")
            )).thenReturn(Optional.empty());

            FoodLogEnvelope actual = invoker.invoke(validFile, requestId);

            assertSame(expected, actual);

            // reusable hit 雖存在，但因 effective 非 object，應 fallback 成 miss
            verify(createSupport, never()).applyCacheHitDraft(any(), any());

            verify(abuseGuard).onOperationAttempt(
                    1L,
                    "dev-1",
                    false,
                    fixedNow,
                    ZoneOffset.UTC
            );

            verify(quota).consumeOperationOrThrow(
                    1L,
                    EntitlementService.Tier.TRIAL,
                    ZoneOffset.UTC,
                    fixedNow
            );

            verify(createSupport).applyPendingMiss(
                    same(newEntity),
                    any(),
                    eq("GEMINI")
            );

            verify(repo, times(2)).save(newEntity);
            verify(idem).attach(1L, requestId, "log-new-1", fixedNow);
            verify(createSupport).retainBlobAndAttach(newEntity, 1L, upload);

            verify(createSupport).createQueuedTask("log-new-1");
            verify(taskRepo).save(task);
            verify(envelopeAssembler).assemble(newEntity, task, requestId);

            verify(idem, never()).failAndReleaseIfNeeded(anyLong(), anyString(), any(Boolean.class));
            verify(inFlight).release(lease);
        }
    }

    /**
     * 依你目前的 finalizeCreateResult()，
     * 只要不是 DRAFT，就會建立 task 並 assemble。
     *
     * 所以這裡把 applyPendingMiss 後的 status 故意設成 FAILED，
     * 驗證它仍然走「非 DRAFT 路徑」。
     */
    private void assertFailedStatusStillCreatesTask(FileInvoker invoker) throws Exception {
        Instant fixedNow = Instant.parse("2026-03-03T00:00:00Z");
        String requestId = "rid-failed-status";
        String tempKey = "temp/u1/rid-failed-status.jpg";
        String sha256 = "sha-failed-status";

        UserInFlightLimiter.Lease lease = new UserInFlightLimiter.Lease(1L, "lease-failed-status");

        MultipartFile validFile = new MockMultipartFile(
                "file",
                "meal.jpg",
                "image/jpeg",
                new byte[]{5, 6, 7, 8}
        );

        FoodLogCreateSupport.UploadTempResult upload = mock(FoodLogCreateSupport.UploadTempResult.class);
        StorageService.SaveResult saved = mock(StorageService.SaveResult.class);
        QuotaService.Decision decision = mock(QuotaService.Decision.class);

        FoodLogEntity newEntity = new FoodLogEntity();
        newEntity.setId("log-failed-1");

        FoodLogTaskEntity task = new FoodLogTaskEntity();
        task.setFoodLogId("log-failed-1");

        FoodLogEnvelope expected = mock(FoodLogEnvelope.class);

        when(clock.instant()).thenReturn(fixedNow);
        when(idem.reserveOrGetExisting(1L, requestId, fixedNow)).thenReturn(null);

        doReturn(EntitlementService.Tier.TRIAL)
                .when(entitlementService)
                .resolveTier(1L, fixedNow);

        doNothing().when(rateLimiter).checkOrThrow(1L, EntitlementService.Tier.TRIAL, fixedNow);
        when(inFlight.acquireOrThrow(1L)).thenReturn(lease);

        when(createSupport.uploadTempImage(1L, requestId, validFile)).thenReturn(upload);
        when(upload.tempKey()).thenReturn(tempKey);
        when(upload.saved()).thenReturn(saved);
        when(saved.sha256()).thenReturn(sha256);

        when(repo.findFirstByUserIdAndMethodInAndImageSha256AndStatusInOrderByCreatedAtUtcDesc(
                eq(1L),
                anyCollection(),
                eq(sha256),
                anyCollection()
        )).thenReturn(Optional.empty());

        when(createSupport.newBaseEntity(
                eq(1L),
                anyString(),
                any(Instant.class),
                eq("Asia/Taipei"),
                any(),
                eq(fixedNow),
                any(),
                any(Boolean.class)
        )).thenReturn(newEntity);

        when(quota.consumeOperationOrThrow(
                eq(1L),
                eq(EntitlementService.Tier.TRIAL),
                any(),
                eq(fixedNow)
        )).thenReturn(decision);

        when(providerClient.providerCode()).thenReturn("gemini");

        // 故意把 status 設成 FAILED，驗證目前 finalize 仍會建 task
        doAnswer(invocation -> {
            FoodLogEntity e = invocation.getArgument(0);
            e.setStatus(FoodLogStatus.FAILED);
            return null;
        }).when(createSupport).applyPendingMiss(
                same(newEntity),
                any(),
                eq("GEMINI")
        );

        when(createSupport.createQueuedTask("log-failed-1")).thenReturn(task);
        when(envelopeAssembler.assemble(newEntity, task, requestId)).thenReturn(expected);

        try (MockedStatic<ExifTimeExtractor> exifMock = mockStatic(ExifTimeExtractor.class)) {
            exifMock.when(() -> ExifTimeExtractor.tryReadCapturedAtUtc(
                    storage,
                    tempKey,
                    ZoneId.of("Asia/Taipei")
            )).thenReturn(Optional.empty());

            FoodLogEnvelope actual = invoker.invoke(validFile, requestId);

            assertSame(expected, actual);

            verify(createSupport, never()).applyCacheHitDraft(any(), any());

            verify(abuseGuard).onOperationAttempt(
                    1L,
                    "dev-1",
                    false,
                    fixedNow,
                    ZoneOffset.UTC
            );

            verify(quota).consumeOperationOrThrow(
                    1L,
                    EntitlementService.Tier.TRIAL,
                    ZoneOffset.UTC,
                    fixedNow
            );

            verify(createSupport).applyPendingMiss(
                    same(newEntity),
                    any(),
                    eq("GEMINI")
            );

            verify(repo, times(2)).save(newEntity);
            verify(idem).attach(1L, requestId, "log-failed-1", fixedNow);
            verify(createSupport).retainBlobAndAttach(newEntity, 1L, upload);

            verify(createSupport).createQueuedTask("log-failed-1");
            verify(taskRepo).save(task);
            verify(envelopeAssembler).assemble(newEntity, task, requestId);

            verify(idem, never()).failAndReleaseIfNeeded(anyLong(), anyString(), any(Boolean.class));
            verify(inFlight).release(lease);
        }
    }

    @FunctionalInterface
    private interface FileInvoker {
        FoodLogEnvelope invoke(MultipartFile file, String requestId) throws Exception;
    }
}
