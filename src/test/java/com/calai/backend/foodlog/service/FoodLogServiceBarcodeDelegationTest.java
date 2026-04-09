package com.calai.backend.foodlog.service;

import com.calai.backend.entitlement.service.EntitlementService;
import com.calai.backend.foodlog.dto.FoodLogEnvelope;
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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;

class FoodLogServiceBarcodeDelegationTest {

    private final ProviderClient providerClient = mock(ProviderClient.class);
    private final FoodLogRepository repo = mock(FoodLogRepository.class);
    private final FoodLogTaskRepository taskRepo = mock(FoodLogTaskRepository.class);
    private final StorageService storage = mock(StorageService.class);
    private final QuotaService quota = mock(QuotaService.class);
    private final IdempotencyService idem = mock(IdempotencyService.class);
    private final UserInFlightLimiter inFlight = mock(UserInFlightLimiter.class);
    private final UserRateLimiter rateLimiter = mock(UserRateLimiter.class);
    private final AbuseGuardService abuseGuard = mock(AbuseGuardService.class);
    private final EntitlementService entitlementService = mock(EntitlementService.class);
    private final FoodLogEnvelopeAssembler envelopeAssembler = mock(FoodLogEnvelopeAssembler.class);
    private final FoodLogQueryService queryService = mock(FoodLogQueryService.class);
    private final FoodLogImageAccessService imageAccessService = mock(FoodLogImageAccessService.class);
    private final FoodLogRetryService retryService = mock(FoodLogRetryService.class);
    private final FoodLogBarcodeService barcodeService = mock(FoodLogBarcodeService.class);
    private final FoodLogCreateSupport createSupport = mock(FoodLogCreateSupport.class);
    private final CapturedTimeResolver timeResolver = mock(CapturedTimeResolver.class);
    private final UserDailyNutritionSummaryService dailySummaryService = mock(UserDailyNutritionSummaryService.class);

    private FoodLogService service;

    @BeforeEach
    void setUp() {
        service = new FoodLogService(
                providerClient,
                repo,
                taskRepo,
                storage,
                quota,
                idem,
                inFlight,
                rateLimiter,
                Clock.fixed(Instant.parse("2026-03-20T08:00:00Z"), ZoneOffset.UTC),
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
    void createBarcodeMvp_should_delegate_to_barcodeService() {
        FoodLogEnvelope expected = mock(FoodLogEnvelope.class);

        when(barcodeService.createBarcodeMvp(
                1001L,
                "Asia/Taipei",
                "device-A",
                "4901234567894",
                "ja",
                "req-1"
        )).thenReturn(expected);

        FoodLogEnvelope actual = service.createBarcodeMvp(
                1001L,
                "Asia/Taipei",
                "device-A",
                "4901234567894",
                "ja",
                "req-1"
        );

        assertSame(expected, actual);
        verify(barcodeService).createBarcodeMvp(
                1001L,
                "Asia/Taipei",
                "device-A",
                "4901234567894",
                "ja",
                "req-1"
        );
    }
}
