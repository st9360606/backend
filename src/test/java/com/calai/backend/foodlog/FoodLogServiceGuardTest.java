package com.calai.backend.foodlog;

import com.calai.backend.entitlement.service.EntitlementService;
import com.calai.backend.foodlog.barcode.BarcodeLookupService;
import com.calai.backend.foodlog.mapper.ClientActionMapper;
import com.calai.backend.foodlog.quota.guard.AbuseGuardService;
import com.calai.backend.foodlog.quota.service.QuotaService;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.repo.FoodLogTaskRepository;
import com.calai.backend.foodlog.service.FoodLogService;
import com.calai.backend.foodlog.service.IdempotencyService;
import com.calai.backend.foodlog.service.ImageBlobService;
import com.calai.backend.foodlog.service.limiter.UserInFlightLimiter;
import com.calai.backend.foodlog.service.limiter.UserRateLimiter;
import com.calai.backend.foodlog.storage.StorageService;
import com.calai.backend.foodlog.task.EffectivePostProcessor;
import com.calai.backend.foodlog.task.ProviderClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class FoodLogServiceGuardTest {

    @Mock FoodLogRepository repo;
    @Mock FoodLogTaskRepository taskRepo;
    @Mock StorageService storage;

    @Mock QuotaService quota;
    @Mock IdempotencyService idem;
    @Mock ImageBlobService blobService;
    @Mock UserInFlightLimiter inFlight;
    @Mock UserRateLimiter rateLimiter;

    @Mock EffectivePostProcessor postProcessor;
    @Mock ClientActionMapper clientActionMapper;
    @Mock AbuseGuardService abuseGuard;
    @Mock EntitlementService entitlementService;

    @Mock ProviderClient providerClient;
    @Mock BarcodeLookupService barcodeLookupService;
    @Mock TransactionTemplate txTemplate;
    @Mock Clock clock;

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
    void createPhoto_releaseInFlight_whenUnsupportedFormat() throws Exception {
        Instant fixedNow = Instant.parse("2026-03-03T00:00:00Z");
        UserInFlightLimiter.Lease lease =
                new UserInFlightLimiter.Lease(1L, "lease-1");

        when(clock.instant()).thenReturn(fixedNow);

        when(idem.reserveOrGetExisting(anyLong(), anyString(), any(Instant.class)))
                .thenReturn(null);

        when(entitlementService.resolveTier(anyLong(), any(Instant.class)))
                .thenReturn(EntitlementService.Tier.TRIAL);

        doNothing().when(rateLimiter).checkOrThrow(
                anyLong(),
                any(EntitlementService.Tier.class),
                any(Instant.class)
        );

        // ✅ acquireOrThrow 現在是有回傳值的方法
        when(inFlight.acquireOrThrow(anyLong())).thenReturn(lease);

        doNothing().when(idem).failAndReleaseIfNeeded(
                anyLong(),
                anyString(),
                anyBoolean()
        );

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "x.bin",
                "application/octet-stream",
                new byte[]{1, 2, 3, 4, 5}
        );

        assertThrows(IllegalArgumentException.class, () ->
                svc.createPhoto(1L, "Asia/Taipei", "dev-1", null, file, "rid-1")
        );

        verify(entitlementService, times(1))
                .resolveTier(eq(1L), eq(fixedNow));

        verify(rateLimiter, times(1))
                .checkOrThrow(eq(1L), eq(EntitlementService.Tier.TRIAL), eq(fixedNow));

        verify(inFlight, times(1)).acquireOrThrow(1L);

        // ✅ 驗證 release 的是 Lease，不是 Long
        ArgumentCaptor<UserInFlightLimiter.Lease> captor =
                ArgumentCaptor.forClass(UserInFlightLimiter.Lease.class);

        verify(inFlight, times(1)).release(captor.capture());

        assertThat(captor.getValue().userId()).isEqualTo(1L);
        assertThat(captor.getValue().token()).isEqualTo("lease-1");

        verify(idem, atLeastOnce()).failAndReleaseIfNeeded(
                eq(1L),
                eq("rid-1"),
                eq(true)
        );

        // unsupported format 在 detect 階段就失敗，不應扣 quota
        verifyNoInteractions(quota);

        // 這條路徑也不應建立 task / retain blob
        verifyNoInteractions(taskRepo);
        verifyNoInteractions(blobService);
    }
}