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
import com.calai.backend.foodlog.time.CapturedTimeResolver;
import com.calai.backend.foodlog.web.error.FoodLogAppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.time.Clock;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FoodLogServiceCreateGuardInvalidInputTest {

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
    @Mock CapturedTimeResolver timeResolver;
    @Mock UserDailyNutritionSummaryService dailySummaryService;

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
                timeResolver,
                abuseGuard,
                entitlementService,
                envelopeAssembler,
                queryService,
                imageAccessService,
                retryService,
                barcodeService,
                createSupport,
                dailySummaryService
        );
    }

    // ========= createPhoto =========

    @Test
    void createPhoto_should_fail_with_FILE_REQUIRED_when_file_is_null() throws Exception {
        assertFileRequiredGuard((requestId) ->
                svc.createPhoto(1L, "Asia/Taipei", "dev-1", "2026-03-03T01:02:03Z", null, requestId)
        );
    }

    @Test
    void createPhoto_should_fail_with_FILE_TOO_LARGE_when_file_size_exceeds_limit() throws Exception {
        assertFileTooLargeGuard((file, requestId) ->
                svc.createPhoto(1L, "Asia/Taipei", "dev-1", "2026-03-03T01:02:03Z", file, requestId)
        );
    }

    @Test
    void createPhoto_should_fail_and_release_idem_when_rate_check_fails() throws Exception {
        assertRateCheckFailureGuard((file, requestId) ->
                svc.createPhoto(1L, "Asia/Taipei", "dev-1", "2026-03-03T01:02:03Z", file, requestId)
        );
    }

    @Test
    void createPhoto_should_fail_and_release_idem_when_acquire_inflight_fails() throws Exception {
        assertAcquireInFlightFailureGuard((file, requestId) ->
                svc.createPhoto(1L, "Asia/Taipei", "dev-1", "2026-03-03T01:02:03Z", file, requestId)
        );
    }

    // ========= createLabel =========

    @Test
    void createLabel_should_fail_with_FILE_REQUIRED_when_file_is_null() throws Exception {
        assertFileRequiredGuard((requestId) ->
                svc.createLabel(1L, "Asia/Taipei", "dev-1", "2026-03-03T01:02:03Z", null, requestId)
        );
    }

    @Test
    void createLabel_should_fail_with_FILE_TOO_LARGE_when_file_size_exceeds_limit() throws Exception {
        assertFileTooLargeGuard((file, requestId) ->
                svc.createLabel(1L, "Asia/Taipei", "dev-1", "2026-03-03T01:02:03Z", file, requestId)
        );
    }

    @Test
    void createLabel_should_fail_and_release_idem_when_rate_check_fails() throws Exception {
        assertRateCheckFailureGuard((file, requestId) ->
                svc.createLabel(1L, "Asia/Taipei", "dev-1", "2026-03-03T01:02:03Z", file, requestId)
        );
    }

    @Test
    void createLabel_should_fail_and_release_idem_when_acquire_inflight_fails() throws Exception {
        assertAcquireInFlightFailureGuard((file, requestId) ->
                svc.createLabel(1L, "Asia/Taipei", "dev-1", "2026-03-03T01:02:03Z", file, requestId)
        );
    }

    @Test
    void createPhoto_should_fail_and_release_idem_when_resolveTier_itself_fails() throws Exception {
        Instant fixedNow = Instant.parse("2026-03-03T00:00:00Z");
        String requestId = "rid-resolve-tier-fail";

        MultipartFile validFile = mock(MultipartFile.class);
        when(validFile.isEmpty()).thenReturn(false);
        when(validFile.getSize()).thenReturn(1024L);

        RuntimeException resolveFailure = new RuntimeException("RESOLVE_TIER_FAILED");

        when(clock.instant()).thenReturn(fixedNow);
        when(idem.reserveOrGetExisting(1L, requestId, fixedNow)).thenReturn(null);
        doThrow(resolveFailure).when(entitlementService).resolveTier(1L, fixedNow);
        doNothing().when(idem).failAndReleaseIfNeeded(1L, requestId, true);

        RuntimeException ex = assertThrows(
                RuntimeException.class,
                () -> svc.createPhoto(1L, "Asia/Taipei", "dev-1", "2026-03-03T01:02:03Z", validFile, requestId)
        );

        assertSame(resolveFailure, ex);
        verify(idem, atLeastOnce()).failAndReleaseIfNeeded(1L, requestId, true);
        verifyNoInteractions(rateLimiter);
        verify(inFlight, never()).acquireOrThrow(anyLong());
        verifyNoInteractions(createSupport);
        verifyNoInteractions(dailySummaryService);
    }

    // ========= shared helpers =========

    /**
     * FILE_REQUIRED:
     * - 先 reserve request
     * - validateUploadBasics 失敗
     * - idem fail/release
     * - 不會進到 entitlement/rate/inflight/upload/quota/repo/task
     */
    private void assertFileRequiredGuard(NullFileInvoker invoker) throws Exception {
        Instant fixedNow = Instant.parse("2026-03-03T00:00:00Z");
        String requestId = "rid-file-required";

        when(clock.instant()).thenReturn(fixedNow);
        when(idem.reserveOrGetExisting(1L, requestId, fixedNow)).thenReturn(null);
        doNothing().when(idem).failAndReleaseIfNeeded(1L, requestId, true);

        FoodLogAppException ex = assertThrows(
                FoodLogAppException.class,
                () -> invoker.invoke(requestId)
        );

        assertEquals("FILE_REQUIRED", ex.getMessage());

        verify(idem).reserveOrGetExisting(1L, requestId, fixedNow);
        verify(idem, atLeastOnce()).failAndReleaseIfNeeded(1L, requestId, true);

        verifyNoInteractions(entitlementService);
        verifyNoInteractions(rateLimiter);
        verifyNoInteractions(inFlight);
        verifyNoInteractions(createSupport);
        verifyNoInteractions(quota);
        verifyNoInteractions(dailySummaryService);
        verify(repo, never()).save(any());
        verify(taskRepo, never()).save(any());
        verify(idem, never()).attach(anyLong(), any(), any(), any());
    }

    /**
     * FILE_TOO_LARGE:
     * - reserve request
     * - validateUploadBasics 看到過大檔案即失敗
     * - idem fail/release
     * - 不會進到後續流程
     */
    private void assertFileTooLargeGuard(FileInvoker invoker) throws Exception {
        Instant fixedNow = Instant.parse("2026-03-03T00:00:00Z");
        String requestId = "rid-file-too-large";

        MultipartFile tooLargeFile = mock(MultipartFile.class);
        when(tooLargeFile.isEmpty()).thenReturn(false);
        when(tooLargeFile.getSize()).thenReturn(100_000_000L);

        when(clock.instant()).thenReturn(fixedNow);
        when(idem.reserveOrGetExisting(1L, requestId, fixedNow)).thenReturn(null);
        doNothing().when(idem).failAndReleaseIfNeeded(1L, requestId, true);

        FoodLogAppException ex = assertThrows(
                FoodLogAppException.class,
                () -> invoker.invoke(tooLargeFile, requestId)
        );

        assertEquals("FILE_TOO_LARGE", ex.getMessage());

        verify(idem).reserveOrGetExisting(1L, requestId, fixedNow);
        verify(idem, atLeastOnce()).failAndReleaseIfNeeded(1L, requestId, true);

        verifyNoInteractions(entitlementService);
        verifyNoInteractions(rateLimiter);
        verifyNoInteractions(inFlight);
        verifyNoInteractions(createSupport);
        verifyNoInteractions(quota);
        verifyNoInteractions(dailySummaryService);
        verify(repo, never()).save(any());
        verify(taskRepo, never()).save(any());
        verify(idem, never()).attach(anyLong(), any(), any(), any());
    }

    /**
     * resolveTierAndCheckRateOrRelease() 失敗：
     * 這裡用 rateLimiter.checkOrThrow() 拋錯模擬 helper 失敗。
     *
     * 驗證：
     * - reserve 已做
     * - entitlement resolve 已做
     * - rate check 拋錯
     * - idem fail/release
     * - 不會進到 acquire/upload/quota/repo/task
     */
    private void assertRateCheckFailureGuard(FileInvoker invoker) throws Exception {
        Instant fixedNow = Instant.parse("2026-03-03T00:00:00Z");
        String requestId = "rid-rate-fail";

        MultipartFile validFile = mock(MultipartFile.class);
        when(validFile.isEmpty()).thenReturn(false);
        when(validFile.getSize()).thenReturn(1024L);

        RuntimeException rateFailure = new RuntimeException("RATE_LIMITED");

        when(clock.instant()).thenReturn(fixedNow);
        when(idem.reserveOrGetExisting(1L, requestId, fixedNow)).thenReturn(null);

        doReturn(EntitlementService.Tier.TRIAL)
                .when(entitlementService)
                .resolveTier(1L, fixedNow);

        doThrow(rateFailure)
                .when(rateLimiter)
                .checkOrThrow(1L, EntitlementService.Tier.TRIAL, fixedNow);

        doNothing().when(idem).failAndReleaseIfNeeded(1L, requestId, true);

        RuntimeException ex = assertThrows(
                RuntimeException.class,
                () -> invoker.invoke(validFile, requestId)
        );

        assertSame(rateFailure, ex);

        verify(idem).reserveOrGetExisting(1L, requestId, fixedNow);
        verify(entitlementService).resolveTier(1L, fixedNow);
        verify(rateLimiter).checkOrThrow(1L, EntitlementService.Tier.TRIAL, fixedNow);
        verify(idem, atLeastOnce()).failAndReleaseIfNeeded(1L, requestId, true);

        verify(inFlight, never()).acquireOrThrow(anyLong());
        verifyNoInteractions(createSupport);
        verifyNoInteractions(quota);
        verifyNoInteractions(dailySummaryService);
        verify(repo, never()).save(any());
        verify(taskRepo, never()).save(any());
        verify(idem, never()).attach(anyLong(), any(), any(), any());
    }

    /**
     * acquireInFlightOrRelease() 失敗：
     * - reserve 已做
     * - resolve tier / rate check 已做
     * - acquireOrThrow 拋錯
     * - idem fail/release
     * - 不會進到 upload/quota/repo/task
     *
     * 注意：
     * acquire 失敗表示 lease 尚未拿到，所以不應呼叫 release(lease)
     */
    private void assertAcquireInFlightFailureGuard(FileInvoker invoker) throws Exception {
        Instant fixedNow = Instant.parse("2026-03-03T00:00:00Z");
        String requestId = "rid-inflight-fail";

        MultipartFile validFile = mock(MultipartFile.class);
        when(validFile.isEmpty()).thenReturn(false);
        when(validFile.getSize()).thenReturn(1024L);

        RuntimeException acquireFailure = new RuntimeException("TOO_MANY_IN_FLIGHT");

        when(clock.instant()).thenReturn(fixedNow);
        when(idem.reserveOrGetExisting(1L, requestId, fixedNow)).thenReturn(null);

        doReturn(EntitlementService.Tier.TRIAL)
                .when(entitlementService)
                .resolveTier(1L, fixedNow);

        doNothing().when(rateLimiter).checkOrThrow(1L, EntitlementService.Tier.TRIAL, fixedNow);

        doThrow(acquireFailure)
                .when(inFlight)
                .acquireOrThrow(1L);

        doNothing().when(idem).failAndReleaseIfNeeded(1L, requestId, true);

        RuntimeException ex = assertThrows(
                RuntimeException.class,
                () -> invoker.invoke(validFile, requestId)
        );

        assertSame(acquireFailure, ex);

        verify(idem).reserveOrGetExisting(1L, requestId, fixedNow);
        verify(entitlementService).resolveTier(1L, fixedNow);
        verify(rateLimiter).checkOrThrow(1L, EntitlementService.Tier.TRIAL, fixedNow);
        verify(inFlight).acquireOrThrow(1L);
        verify(idem, atLeastOnce()).failAndReleaseIfNeeded(1L, requestId, true);

        verifyNoInteractions(createSupport);
        verifyNoInteractions(quota);
        verifyNoInteractions(dailySummaryService);
        verify(repo, never()).save(any());
        verify(taskRepo, never()).save(any());
        verify(idem, never()).attach(anyLong(), any(), any(), any());

        verify(inFlight, never()).release(any());
    }

    @FunctionalInterface
    private interface NullFileInvoker {
        void invoke(String requestId) throws Exception;
    }

    @FunctionalInterface
    private interface FileInvoker {
        void invoke(MultipartFile file, String requestId) throws Exception;
    }
}
