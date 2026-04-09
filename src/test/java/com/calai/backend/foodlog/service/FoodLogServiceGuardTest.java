package com.calai.backend.foodlog.service;

import com.calai.backend.entitlement.service.EntitlementService;
import com.calai.backend.foodlog.model.FoodLogErrorCode;
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
import com.calai.backend.foodlog.web.error.FoodLogAppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FoodLogServiceGuardTest {

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

    @Test
    void createPhoto_releaseInFlight_and_failIdem_whenUploadTempImageThrows() throws Exception {
        // arrange
        Instant fixedNow = Instant.parse("2026-03-03T00:00:00Z");
        UserInFlightLimiter.Lease lease = new UserInFlightLimiter.Lease(1L, "lease-1");

        when(clock.instant()).thenReturn(fixedNow);
        when(idem.reserveOrGetExisting(1L, "rid-1", fixedNow)).thenReturn(null);
        when(entitlementService.resolveTier(1L, fixedNow)).thenReturn(EntitlementService.Tier.TRIAL);
        doNothing().when(rateLimiter).checkOrThrow(1L, EntitlementService.Tier.TRIAL, fixedNow);
        when(inFlight.acquireOrThrow(1L)).thenReturn(lease);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "x.bin",
                "application/octet-stream",
                new byte[]{1, 2, 3, 4, 5}
        );

        // 關鍵：因為 createSupport 是 mock，
        // 真實 uploadTempImage() 裡面的 idem.failAndReleaseIfNeeded() 不會自動發生，
        // 要自己把 side effect 模擬出來。
        doAnswer(invocation -> {
            idem.failAndReleaseIfNeeded(1L, "rid-1", true);
            throw new FoodLogAppException(FoodLogErrorCode.UNSUPPORTED_IMAGE_FORMAT);
        }).when(createSupport).uploadTempImage(1L, "rid-1", file);

        // act + assert
        FoodLogAppException ex = assertThrows(
                FoodLogAppException.class,
                () -> svc.createPhoto(1L, "Asia/Taipei", "dev-1", null, file, "rid-1")
        );

        assertThat(ex.getMessage()).isEqualTo("UNSUPPORTED_IMAGE_FORMAT");

        // verify 基本前置流程
        verify(idem).reserveOrGetExisting(1L, "rid-1", fixedNow);
        verify(entitlementService).resolveTier(1L, fixedNow);
        verify(rateLimiter).checkOrThrow(1L, EntitlementService.Tier.TRIAL, fixedNow);
        verify(inFlight).acquireOrThrow(1L);
        verify(createSupport).uploadTempImage(1L, "rid-1", file);

        // verify upload 失敗後有回收 idem
        verify(idem).failAndReleaseIfNeeded(1L, "rid-1", true);

        // verify lease 有被 release
        ArgumentCaptor<UserInFlightLimiter.Lease> captor =
                ArgumentCaptor.forClass(UserInFlightLimiter.Lease.class);
        verify(inFlight).release(captor.capture());

        assertThat(captor.getValue().userId()).isEqualTo(1L);
        assertThat(captor.getValue().token()).isEqualTo("lease-1");

        // verify 失敗後不應往後走
        verifyNoInteractions(quota);
        verifyNoInteractions(taskRepo);
        verify(repo, never()).save(any());
        verifyNoInteractions(envelopeAssembler);
        verifyNoInteractions(dailySummaryService);

        // 因為 uploadTempImage 就失敗了，下面這些都不該觸發
        verifyNoInteractions(abuseGuard);
        verifyNoInteractions(queryService);
        verifyNoInteractions(imageAccessService);
        verifyNoInteractions(retryService);
        verifyNoInteractions(barcodeService);
        verifyNoInteractions(providerClient);

        // 也不應 attach / retain / create task
        verify(idem, never()).attach(anyLong(), anyString(), anyString(), any());
        verify(createSupport, never()).retainBlobAndAttach(any(), anyLong(), any());
        verify(createSupport, never()).newBaseEntity(
                anyLong(),
                any(FoodLogMethod.class),
                any(),
                anyString(),
                any(),
                any(),
                any(),
                anyBoolean()
        );
        verify(createSupport, never()).applyCacheHitDraft(any(), any());
        verify(createSupport, never()).applyPendingMiss(any(), any(), anyString());
        verify(createSupport, never()).createQueuedTask(anyString());
    }
}
