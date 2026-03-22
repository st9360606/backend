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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;

class FoodLogServiceBarcodeDelegationTest {

    private ProviderClient providerClient = mock(ProviderClient.class);
    private FoodLogRepository repo = mock(FoodLogRepository.class);
    private FoodLogTaskRepository taskRepo = mock(FoodLogTaskRepository.class);
    private StorageService storage = mock(StorageService.class);
    private QuotaService quota = mock(QuotaService.class);
    private IdempotencyService idem = mock(IdempotencyService.class);
    private UserInFlightLimiter inFlight = mock(UserInFlightLimiter.class);
    private UserRateLimiter rateLimiter = mock(UserRateLimiter.class);
    private AbuseGuardService abuseGuard = mock(AbuseGuardService.class);
    private EntitlementService entitlementService = mock(EntitlementService.class);
    private FoodLogEnvelopeAssembler envelopeAssembler = mock(FoodLogEnvelopeAssembler.class);
    private FoodLogQueryService queryService = mock(FoodLogQueryService.class);
    private FoodLogImageAccessService imageAccessService = mock(FoodLogImageAccessService.class);
    private FoodLogRetryService retryService = mock(FoodLogRetryService.class);
    private FoodLogBarcodeService barcodeService = mock(FoodLogBarcodeService.class);
    private FoodLogCreateSupport createSupport = mock(FoodLogCreateSupport.class);

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
