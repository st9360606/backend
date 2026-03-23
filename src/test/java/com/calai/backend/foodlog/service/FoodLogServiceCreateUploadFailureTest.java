package com.calai.backend.foodlog.service;

import com.calai.backend.entitlement.service.EntitlementService;
import com.calai.backend.foodlog.model.FoodLogMethod;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.time.Clock;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * createPhoto / createLabel 在 uploadTempImage() 失敗時的 guard tests
 *
 * 驗證重點：
 * 1. idem.failAndReleaseIfNeeded() 會被呼叫
 * 2. inFlight lease 會被 release
 * 3. 不會扣 quota
 * 4. 不會 save log / task
 * 5. 不會 attach idempotency
 * 6. 不會 retain blob
 */
@ExtendWith(MockitoExtension.class)
class FoodLogServiceCreateUploadFailureTest {

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
                createSupport
        );
    }

    @Test
    void createPhoto_should_fail_and_cleanup_when_uploadTempImage_fails() throws Exception {
        assertUploadFailureGuard((file, requestId) ->
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
    void createLabel_should_fail_and_cleanup_when_uploadTempImage_fails() throws Exception {
        assertUploadFailureGuard((file, requestId) ->
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
     * uploadTempImage() 失敗路徑共用驗證
     *
     * 注意：
     * - 真正的 idem.failAndReleaseIfNeeded() 是在 createSupport.uploadTempImage() 裡面做的
     * - 因為這裡 createSupport 是 mock，所以要自己模擬那個 side effect
     */
    private void assertUploadFailureGuard(FileInvoker invoker) throws Exception {
        Instant fixedNow = Instant.parse("2026-03-03T00:00:00Z");
        String requestId = "rid-upload-fail";
        RuntimeException uploadFailure = new RuntimeException("UPLOAD_TEMP_FAILED");

        UserInFlightLimiter.Lease lease = new UserInFlightLimiter.Lease(1L, "lease-upload-fail");

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
        when(inFlight.acquireOrThrow(1L)).thenReturn(lease);

        // ✅ 重點：模擬 FoodLogCreateSupport.uploadTempImage() 內部的補償行為
        doAnswer(invocation -> {
            idem.failAndReleaseIfNeeded(1L, requestId, true);
            throw uploadFailure;
        }).when(createSupport).uploadTempImage(1L, requestId, validFile);

        RuntimeException ex = assertThrows(
                RuntimeException.class,
                () -> invoker.invoke(validFile, requestId)
        );

        assertSame(uploadFailure, ex);

        // 前置流程有走到 upload 失敗點
        verify(idem).reserveOrGetExisting(1L, requestId, fixedNow);
        verify(entitlementService).resolveTier(1L, fixedNow);
        verify(rateLimiter).checkOrThrow(1L, EntitlementService.Tier.TRIAL, fixedNow);
        verify(inFlight).acquireOrThrow(1L);
        verify(createSupport).uploadTempImage(1L, requestId, validFile);

        // upload 失敗後要回收 request 與 lease
        verify(idem).failAndReleaseIfNeeded(1L, requestId, true);
        verify(inFlight).release(lease);

        // upload 失敗後，後續流程都不應執行
        verifyNoInteractions(quota);
        verify(repo, never()).save(any());
        verify(taskRepo, never()).save(any());
        verify(idem, never()).attach(anyLong(), any(), any(), any());
        verify(createSupport, never()).retainBlobAndAttach(any(), anyLong(), any());
        verify(createSupport, never()).newBaseEntity(anyLong(), any(FoodLogMethod.class), any(), anyString(), any(), any(), any(), anyBoolean());
        verify(createSupport, never()).applyCacheHitDraft(any(), any());
        verify(createSupport, never()).applyPendingMiss(any(), any(), anyString());
        verify(createSupport, never()).createQueuedTask(anyString());
        verifyNoInteractions(envelopeAssembler);
        verifyNoInteractions(abuseGuard);

        // 這些旁支 service 不該被碰到
        verifyNoInteractions(queryService);
        verifyNoInteractions(imageAccessService);
        verifyNoInteractions(retryService);
        verifyNoInteractions(barcodeService);
        verifyNoInteractions(providerClient);
    }

    @FunctionalInterface
    private interface FileInvoker {
        void invoke(MultipartFile file, String requestId) throws Exception;
    }
}
