package com.calai.backend.foodlog.service;

import com.calai.backend.entitlement.service.EntitlementService;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.time.Clock;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * createPhoto / createLabel guard failure tests
 *
 * 驗證範圍：
 * 1. FILE_REQUIRED
 * 2. FILE_TOO_LARGE
 * 3. resolveTierAndCheckRateOrRelease() 失敗 -> idem.failAndReleaseIfNeeded()
 * 4. acquireInFlightOrRelease() 失敗 -> idem.failAndReleaseIfNeeded()
 */
@ExtendWith(MockitoExtension.class)
class FoodLogServiceCreateGuardFailureTest {

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

    // =========================================================
    // FILE_REQUIRED
    // =========================================================

    @Test
    void createPhoto_should_throw_FILE_REQUIRED_and_release_request_when_file_is_null() throws Exception {
        assertInvalidFileGuard(
                (file, requestId) -> svc.createPhoto(
                        1L,
                        "Asia/Taipei",
                        "dev-1",
                        "2026-03-03T01:02:03Z",
                        file,
                        requestId
                ),
                null,
                "FILE_REQUIRED"
        );
    }

    @Test
    void createPhoto_should_throw_FILE_REQUIRED_and_release_request_when_file_is_empty() throws Exception {
        MultipartFile emptyFile = new MockMultipartFile(
                "file",
                "empty.jpg",
                "image/jpeg",
                new byte[0]
        );

        assertInvalidFileGuard(
                (file, requestId) -> svc.createPhoto(
                        1L,
                        "Asia/Taipei",
                        "dev-1",
                        "2026-03-03T01:02:03Z",
                        file,
                        requestId
                ),
                emptyFile,
                "FILE_REQUIRED"
        );
    }

    @Test
    void createLabel_should_throw_FILE_REQUIRED_and_release_request_when_file_is_null() throws Exception {
        assertInvalidFileGuard(
                (file, requestId) -> svc.createLabel(
                        1L,
                        "Asia/Taipei",
                        "dev-1",
                        "2026-03-03T01:02:03Z",
                        file,
                        requestId
                ),
                null,
                "FILE_REQUIRED"
        );
    }

    @Test
    void createLabel_should_throw_FILE_REQUIRED_and_release_request_when_file_is_empty() throws Exception {
        MultipartFile emptyFile = new MockMultipartFile(
                "file",
                "empty.jpg",
                "image/jpeg",
                new byte[0]
        );

        assertInvalidFileGuard(
                (file, requestId) -> svc.createLabel(
                        1L,
                        "Asia/Taipei",
                        "dev-1",
                        "2026-03-03T01:02:03Z",
                        file,
                        requestId
                ),
                emptyFile,
                "FILE_REQUIRED"
        );
    }

    // =========================================================
    // FILE_TOO_LARGE
    // =========================================================

    @Test
    void createPhoto_should_throw_FILE_TOO_LARGE_and_release_request_when_file_exceeds_limit() throws Exception {
        MultipartFile tooLargeFile = new MockMultipartFile(
                "file",
                "large.jpg",
                "image/jpeg",
                new byte[(15 * 1024 * 1024) + 1]
        );

        assertInvalidFileGuard(
                (file, requestId) -> svc.createPhoto(
                        1L,
                        "Asia/Taipei",
                        "dev-1",
                        "2026-03-03T01:02:03Z",
                        file,
                        requestId
                ),
                tooLargeFile,
                "FILE_TOO_LARGE"
        );
    }

    @Test
    void createLabel_should_throw_FILE_TOO_LARGE_and_release_request_when_file_exceeds_limit() throws Exception {
        MultipartFile tooLargeFile = new MockMultipartFile(
                "file",
                "large.jpg",
                "image/jpeg",
                new byte[(15 * 1024 * 1024) + 1]
        );

        assertInvalidFileGuard(
                (file, requestId) -> svc.createLabel(
                        1L,
                        "Asia/Taipei",
                        "dev-1",
                        "2026-03-03T01:02:03Z",
                        file,
                        requestId
                ),
                tooLargeFile,
                "FILE_TOO_LARGE"
        );
    }

    // =========================================================
    // resolveTierAndCheckRateOrRelease() fail
    // =========================================================

    @Test
    void createPhoto_should_fail_and_release_request_when_resolveTier_fails() throws Exception {
        RuntimeException tierFailure = new RuntimeException("RESOLVE_TIER_FAILED");

        assertRateStageFailureGuard(
                (file, requestId) -> svc.createPhoto(
                        1L,
                        "Asia/Taipei",
                        "dev-1",
                        "2026-03-03T01:02:03Z",
                        file,
                        requestId
                ),
                tierFailure,
                true
        );
    }

    @Test
    void createLabel_should_fail_and_release_request_when_resolveTier_fails() throws Exception {
        RuntimeException tierFailure = new RuntimeException("RESOLVE_TIER_FAILED");

        assertRateStageFailureGuard(
                (file, requestId) -> svc.createLabel(
                        1L,
                        "Asia/Taipei",
                        "dev-1",
                        "2026-03-03T01:02:03Z",
                        file,
                        requestId
                ),
                tierFailure,
                true
        );
    }

    @Test
    void createPhoto_should_fail_and_release_request_when_rateLimiter_fails() throws Exception {
        RuntimeException rateFailure = new RuntimeException("RATE_LIMITED");

        assertRateStageFailureGuard(
                (file, requestId) -> svc.createPhoto(
                        1L,
                        "Asia/Taipei",
                        "dev-1",
                        "2026-03-03T01:02:03Z",
                        file,
                        requestId
                ),
                rateFailure,
                false
        );
    }

    @Test
    void createLabel_should_fail_and_release_request_when_rateLimiter_fails() throws Exception {
        RuntimeException rateFailure = new RuntimeException("RATE_LIMITED");

        assertRateStageFailureGuard(
                (file, requestId) -> svc.createLabel(
                        1L,
                        "Asia/Taipei",
                        "dev-1",
                        "2026-03-03T01:02:03Z",
                        file,
                        requestId
                ),
                rateFailure,
                false
        );
    }

    // =========================================================
    // acquireInFlightOrRelease() fail
    // =========================================================

    @Test
    void createPhoto_should_fail_and_release_request_when_acquireInFlight_fails() throws Exception {
        assertAcquireInFlightFailureGuard(
                (file, requestId) -> svc.createPhoto(
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
    void createLabel_should_fail_and_release_request_when_acquireInFlight_fails() throws Exception {
        assertAcquireInFlightFailureGuard(
                (file, requestId) -> svc.createLabel(
                        1L,
                        "Asia/Taipei",
                        "dev-1",
                        "2026-03-03T01:02:03Z",
                        file,
                        requestId
                )
        );
    }

    // =========================================================
    // helper：invalid file
    // =========================================================

    /**
     * validateUploadBasicsOrRelease() 失敗時：
     * - idem.failAndReleaseIfNeeded() 要被呼叫
     * - 不可進到 resolveTier / inFlight / upload / quota / save / task
     */
    private void assertInvalidFileGuard(
            FileInvoker invoker,
            MultipartFile file,
            String expectedMessage
    ) throws Exception {
        Instant fixedNow = Instant.parse("2026-03-03T00:00:00Z");
        String requestId = "rid-invalid-file";

        when(clock.instant()).thenReturn(fixedNow);
        when(idem.reserveOrGetExisting(1L, requestId, fixedNow)).thenReturn(null);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> invoker.invoke(file, requestId)
        );

        assertEquals(expectedMessage, ex.getMessage());

        verify(idem).reserveOrGetExisting(1L, requestId, fixedNow);
        verify(idem).failAndReleaseIfNeeded(1L, requestId, true);

        verifyNoInteractions(entitlementService);
        verifyNoInteractions(rateLimiter);
        verifyNoInteractions(inFlight);
        verifyNoInteractions(quota);
        verifyNoInteractions(abuseGuard);
        verifyNoInteractions(providerClient);

        verify(createSupport, never()).uploadTempImage(anyLong(), anyString(), any());
        verify(createSupport, never()).newBaseEntity(anyLong(), anyString(), any(), anyString(), any(), any(), any(), anyBoolean());
        verify(createSupport, never()).applyCacheHitDraft(any(), any());
        verify(createSupport, never()).applyPendingMiss(any(), any(), anyString());
        verify(createSupport, never()).retainBlobAndAttach(any(), anyLong(), any());
        verify(createSupport, never()).createQueuedTask(anyString());

        verify(repo, never()).save(any());
        verify(taskRepo, never()).save(any());
        verify(idem, never()).attach(anyLong(), anyString(), anyString(), any());
        verifyNoInteractions(envelopeAssembler);

        verifyNoInteractions(queryService);
        verifyNoInteractions(imageAccessService);
        verifyNoInteractions(retryService);
        verifyNoInteractions(barcodeService);
    }

    // =========================================================
    // helper：resolveTier / rate fail
    // =========================================================

    /**
     * resolveTierAndCheckRateOrRelease() 失敗時：
     * - idem.failAndReleaseIfNeeded() 要被呼叫
     * - 不可進到 inFlight / upload / quota / save / task
     *
     * @param failAtResolveTier true=resolveTier 拋錯；false=rateLimiter 拋錯
     */
    private void assertRateStageFailureGuard(
            FileInvoker invoker,
            RuntimeException expectedFailure,
            boolean failAtResolveTier
    ) throws Exception {
        Instant fixedNow = Instant.parse("2026-03-03T00:00:00Z");
        String requestId = "rid-rate-fail";

        MultipartFile validFile = new MockMultipartFile(
                "file",
                "meal.jpg",
                "image/jpeg",
                new byte[]{1, 2, 3, 4}
        );

        when(clock.instant()).thenReturn(fixedNow);
        when(idem.reserveOrGetExisting(1L, requestId, fixedNow)).thenReturn(null);

        if (failAtResolveTier) {
            when(entitlementService.resolveTier(1L, fixedNow)).thenThrow(expectedFailure);
        } else {
            when(entitlementService.resolveTier(1L, fixedNow))
                    .thenReturn(EntitlementService.Tier.TRIAL);
            doThrow(expectedFailure).when(rateLimiter)
                    .checkOrThrow(1L, EntitlementService.Tier.TRIAL, fixedNow);
        }

        RuntimeException ex = assertThrows(
                RuntimeException.class,
                () -> invoker.invoke(validFile, requestId)
        );

        assertSame(expectedFailure, ex);

        verify(idem).reserveOrGetExisting(1L, requestId, fixedNow);
        verify(idem).failAndReleaseIfNeeded(1L, requestId, true);

        verify(entitlementService, atLeastOnce()).resolveTier(1L, fixedNow);

        if (!failAtResolveTier) {
            verify(rateLimiter).checkOrThrow(1L, EntitlementService.Tier.TRIAL, fixedNow);
        } else {
            verifyNoInteractions(rateLimiter);
        }

        verifyNoInteractions(inFlight);
        verifyNoInteractions(quota);
        verifyNoInteractions(abuseGuard);
        verifyNoInteractions(providerClient);

        verify(createSupport, never()).uploadTempImage(anyLong(), anyString(), any());
        verify(createSupport, never()).newBaseEntity(anyLong(), anyString(), any(), anyString(), any(), any(), any(), anyBoolean());
        verify(createSupport, never()).applyCacheHitDraft(any(), any());
        verify(createSupport, never()).applyPendingMiss(any(), any(), anyString());
        verify(createSupport, never()).retainBlobAndAttach(any(), anyLong(), any());
        verify(createSupport, never()).createQueuedTask(anyString());

        verify(repo, never()).save(any());
        verify(taskRepo, never()).save(any());
        verify(idem, never()).attach(anyLong(), anyString(), anyString(), any());
        verifyNoInteractions(envelopeAssembler);

        verifyNoInteractions(queryService);
        verifyNoInteractions(imageAccessService);
        verifyNoInteractions(retryService);
        verifyNoInteractions(barcodeService);
    }

    // =========================================================
    // helper：acquireInFlight fail
    // =========================================================

    /**
     * acquireInFlightOrRelease() 失敗時：
     * - idem.failAndReleaseIfNeeded() 要被呼叫
     * - upload / quota / save / task 不應發生
     * - 不會有 lease，所以也不會呼叫 release(lease)
     */
    private void assertAcquireInFlightFailureGuard(FileInvoker invoker) throws Exception {
        Instant fixedNow = Instant.parse("2026-03-03T00:00:00Z");
        String requestId = "rid-inflight-fail";
        RuntimeException acquireFailure = new RuntimeException("TOO_MANY_IN_FLIGHT");

        MultipartFile validFile = new MockMultipartFile(
                "file",
                "meal.jpg",
                "image/jpeg",
                new byte[]{1, 2, 3, 4}
        );

        when(clock.instant()).thenReturn(fixedNow);
        when(idem.reserveOrGetExisting(1L, requestId, fixedNow)).thenReturn(null);
        when(entitlementService.resolveTier(1L, fixedNow))
                .thenReturn(EntitlementService.Tier.TRIAL);

        doNothing().when(rateLimiter).checkOrThrow(1L, EntitlementService.Tier.TRIAL, fixedNow);
        when(inFlight.acquireOrThrow(1L)).thenThrow(acquireFailure);

        RuntimeException ex = assertThrows(
                RuntimeException.class,
                () -> invoker.invoke(validFile, requestId)
        );

        assertSame(acquireFailure, ex);

        verify(idem).reserveOrGetExisting(1L, requestId, fixedNow);
        verify(entitlementService).resolveTier(1L, fixedNow);
        verify(rateLimiter).checkOrThrow(1L, EntitlementService.Tier.TRIAL, fixedNow);
        verify(inFlight).acquireOrThrow(1L);

        verify(idem).failAndReleaseIfNeeded(1L, requestId, true);

        // 沒有 lease 拿到，所以不應 release
        verify(inFlight, never()).release(any());

        verifyNoInteractions(quota);
        verifyNoInteractions(abuseGuard);
        verifyNoInteractions(providerClient);

        verify(createSupport, never()).uploadTempImage(anyLong(), anyString(), any());
        verify(createSupport, never()).newBaseEntity(anyLong(), anyString(), any(), anyString(), any(), any(), any(), anyBoolean());
        verify(createSupport, never()).applyCacheHitDraft(any(), any());
        verify(createSupport, never()).applyPendingMiss(any(), any(), anyString());
        verify(createSupport, never()).retainBlobAndAttach(any(), anyLong(), any());
        verify(createSupport, never()).createQueuedTask(anyString());

        verify(repo, never()).save(any());
        verify(taskRepo, never()).save(any());
        verify(idem, never()).attach(anyLong(), anyString(), anyString(), any());
        verifyNoInteractions(envelopeAssembler);

        verifyNoInteractions(queryService);
        verifyNoInteractions(imageAccessService);
        verifyNoInteractions(retryService);
        verifyNoInteractions(barcodeService);
    }

    @FunctionalInterface
    private interface FileInvoker {
        void invoke(MultipartFile file, String requestId) throws Exception;
    }
}
