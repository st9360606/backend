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
import com.calai.backend.foodlog.service.image.ImageOpenResult;
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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FoodLogService 單元測試（薄服務委派層）
 *
 * 測試目標：
 * 1. 驗證 FoodLogService 是否正確把請求委派給對應的專職 service
 * 2. 驗證回傳值是否原樣傳遞
 *
 * 不測試：
 * - repo 查詢細節
 * - quota / rate limit / storage / EXIF / upload
 * - barcode / retry / query 的內部業務規則
 */
@ExtendWith(MockitoExtension.class)
class FoodLogServiceDelegationTest {

    @Mock
    private ProviderClient providerClient;

    @Mock
    private FoodLogRepository repo;

    @Mock
    private FoodLogTaskRepository taskRepo;

    @Mock
    private StorageService storage;

    @Mock
    private QuotaService quota;

    @Mock
    private IdempotencyService idem;

    @Mock
    private UserInFlightLimiter inFlight;

    @Mock
    private UserRateLimiter rateLimiter;

    @Mock
    private Clock clock;

    @Mock
    private AbuseGuardService abuseGuard;

    @Mock
    private EntitlementService entitlementService;

    @Mock
    private FoodLogEnvelopeAssembler envelopeAssembler;

    @Mock
    private FoodLogQueryService queryService;

    @Mock
    private FoodLogImageAccessService imageAccessService;

    @Mock
    private FoodLogRetryService retryService;

    @Mock
    private FoodLogBarcodeService barcodeService;

    @Mock
    private FoodLogCreateSupport createSupport;

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
    void getOne_should_delegate_to_queryService() {
        // arrange
        Long userId = 1L;
        String foodLogId = "log-001";
        String requestId = "req-001";
        FoodLogEnvelope expected = org.mockito.Mockito.mock(FoodLogEnvelope.class);

        when(queryService.getOne(userId, foodLogId, requestId)).thenReturn(expected);

        // act
        FoodLogEnvelope actual = service.getOne(userId, foodLogId, requestId);

        // assert
        assertSame(expected, actual);
        verify(queryService).getOne(userId, foodLogId, requestId);
    }

    @Test
    void createBarcodeMvp_should_delegate_to_barcodeService() {
        // arrange
        Long userId = 2L;
        String clientTz = "Asia/Taipei";
        String deviceId = "device-002";
        String barcode = "4712345678901";
        String preferredLangTag = "zh-TW";
        String requestId = "req-002";
        FoodLogEnvelope expected = org.mockito.Mockito.mock(FoodLogEnvelope.class);

        when(barcodeService.createBarcodeMvp(
                userId,
                clientTz,
                deviceId,
                barcode,
                preferredLangTag,
                requestId
        )).thenReturn(expected);

        // act
        FoodLogEnvelope actual = service.createBarcodeMvp(
                userId,
                clientTz,
                deviceId,
                barcode,
                preferredLangTag,
                requestId
        );

        // assert
        assertSame(expected, actual);
        verify(barcodeService).createBarcodeMvp(
                userId,
                clientTz,
                deviceId,
                barcode,
                preferredLangTag,
                requestId
        );
    }

    @Test
    void openImage_should_delegate_to_imageAccessService() {
        // arrange
        Long userId = 3L;
        String foodLogId = "log-003";
        ImageOpenResult expected = new ImageOpenResult(
                "user-3/blobs/abc.jpg",
                "image/jpeg",
                12345L
        );

        when(imageAccessService.openImage(userId, foodLogId)).thenReturn(expected);

        // act
        ImageOpenResult actual = service.openImage(userId, foodLogId);

        // assert
        assertSame(expected, actual);
        verify(imageAccessService).openImage(userId, foodLogId);
    }

    @Test
    void openImageStream_should_delegate_to_imageAccessService() throws Exception {
        // arrange
        String objectKey = "user-4/blobs/food.jpg";
        InputStream expected = new ByteArrayInputStream(new byte[]{1, 2, 3});

        when(imageAccessService.openImageStream(objectKey)).thenReturn(expected);

        // act
        InputStream actual = service.openImageStream(objectKey);

        // assert
        assertSame(expected, actual);
        verify(imageAccessService).openImageStream(objectKey);
    }

    @Test
    void retry_should_delegate_to_retryService() {
        // arrange
        Long userId = 5L;
        String foodLogId = "log-005";
        String deviceId = "device-005";
        String requestId = "req-005";
        FoodLogEnvelope expected = org.mockito.Mockito.mock(FoodLogEnvelope.class);

        when(retryService.retry(userId, foodLogId, deviceId, requestId)).thenReturn(expected);

        // act
        FoodLogEnvelope actual = service.retry(userId, foodLogId, deviceId, requestId);

        // assert
        assertSame(expected, actual);
        verify(retryService).retry(userId, foodLogId, deviceId, requestId);
    }
}
