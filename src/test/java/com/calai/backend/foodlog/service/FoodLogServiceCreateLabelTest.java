package com.calai.backend.foodlog.service;

import com.calai.backend.entitlement.service.EntitlementService;
import com.calai.backend.foodlog.dto.FoodLogEnvelope;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.entity.FoodLogTaskEntity;
import com.calai.backend.foodlog.model.FoodLogMethod;
import com.calai.backend.foodlog.model.FoodLogStatus;
import com.calai.backend.foodlog.model.TimeSource;
import com.calai.backend.foodlog.provider.spi.ProviderClient;
import com.calai.backend.foodlog.quota.guard.AbuseGuardService;
import com.calai.backend.foodlog.quota.model.ModelTier;
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
import com.calai.backend.foodlog.time.ExifTimeExtractor;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * createLabel() happy path 測試
 *
 * 驗證兩條主路徑：
 * 1. cache hit -> DRAFT -> 不扣 quota、不建 task
 * 2. cache miss -> PENDING -> 扣 quota、建 task
 */
@ExtendWith(MockitoExtension.class)
public class FoodLogServiceCreateLabelTest {

    @Mock
    ProviderClient providerClient;
    @Mock
    FoodLogRepository repo;
    @Mock
    FoodLogTaskRepository taskRepo;
    @Mock
    StorageService storage;
    @Mock
    QuotaService quota;
    @Mock
    IdempotencyService idem;
    @Mock
    UserInFlightLimiter inFlight;
    @Mock
    UserRateLimiter rateLimiter;
    @Mock
    Clock clock;
    @Mock
    AbuseGuardService abuseGuard;
    @Mock
    EntitlementService entitlementService;
    @Mock
    FoodLogEnvelopeAssembler envelopeAssembler;
    @Mock
    FoodLogQueryService queryService;
    @Mock
    FoodLogImageAccessService imageAccessService;
    @Mock
    FoodLogRetryService retryService;
    @Mock
    FoodLogBarcodeService barcodeService;
    @Mock
    FoodLogCreateSupport createSupport;
    @Mock
    CapturedTimeResolver timeResolver;

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

    /**
     * createLabel() 內部一定會先呼叫 timeResolver.resolve(...)
     * 然後立刻取 r.capturedAtUtc() / r.source().name() / r.suspect()
     * 若不 stub，Mockito 預設回 null，service 會直接 NPE。
     */
    private void stubResolvedTime(Instant resolvedAtUtc, Instant serverNow) {
        CapturedTimeResolver.Result resolved =
                mock(CapturedTimeResolver.Result.class, org.mockito.Answers.RETURNS_DEEP_STUBS);

        when(resolved.capturedAtUtc()).thenReturn(resolvedAtUtc);
        when(resolved.source().name()).thenReturn("SERVER_RECEIVED");
        when(resolved.suspect()).thenReturn(false);

        when(timeResolver.resolve(any(), any(), eq(serverNow))).thenReturn(resolved);
    }

    @Test
    void createLabel_should_return_draft_without_quota_or_task_when_cache_hit() throws Exception {
        // arrange
        Instant fixedNow = Instant.parse("2026-03-03T00:00:00Z");
        String requestId = "rid-label-hit-1";
        String tempKey = "temp/u1/rid-label-hit-1.jpg";
        String sha256 = "sha-label-001";
        String deviceCapturedAtUtc = "2026-03-03T01:02:03Z";

        UserInFlightLimiter.Lease lease = new UserInFlightLimiter.Lease(1L, "lease-label-hit-1");

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "label.jpg",
                "image/jpeg",
                new byte[]{1, 2, 3, 4}
        );

        FoodLogCreateSupport.UploadTempResult upload = mock(FoodLogCreateSupport.UploadTempResult.class);
        StorageService.SaveResult saved = mock(StorageService.SaveResult.class);

        FoodLogEntity reusableHit = new FoodLogEntity();
        reusableHit.setId("log-old-label-1");
        reusableHit.setStatus(FoodLogStatus.DRAFT);
        reusableHit.setEffective(JsonNodeFactory.instance.objectNode().put("foodName", "Nutrition Label"));

        FoodLogEntity newEntity = new FoodLogEntity();
        newEntity.setId("log-new-label-1");

        FoodLogEnvelope expected = mock(FoodLogEnvelope.class);

        when(clock.instant()).thenReturn(fixedNow);
        when(idem.reserveOrGetExisting(1L, requestId, fixedNow)).thenReturn(null);

        stubResolvedTime(fixedNow, fixedNow);

        when(entitlementService.resolveTier(1L, fixedNow))
                .thenReturn(EntitlementService.Tier.TRIAL);

        doNothing().when(rateLimiter).checkOrThrow(1L, EntitlementService.Tier.TRIAL, fixedNow);
        when(inFlight.acquireOrThrow(1L)).thenReturn(lease);

        when(createSupport.uploadTempImage(1L, requestId, file)).thenReturn(upload);
        when(upload.tempKey()).thenReturn(tempKey);
        when(upload.saved()).thenReturn(saved);
        when(saved.sha256()).thenReturn(sha256);

        when(repo.findFirstByUserIdAndMethodInAndImageSha256AndStatusInOrderByCreatedAtUtcDesc(
                eq(1L),
                anyCollection(),
                eq(sha256),
                anyCollection()
        )).thenReturn(Optional.of(reusableHit));

        doReturn(newEntity).when(createSupport).newBaseEntity(
                eq(1L),
                eq(FoodLogMethod.LABEL),
                any(Instant.class),
                eq("Asia/Taipei"),
                any(LocalDate.class),
                eq(fixedNow),
                eq(TimeSource.SERVER_RECEIVED),
                eq(false)
        );

        doAnswer(invocation -> {
            FoodLogEntity e = invocation.getArgument(0);
            e.setStatus(FoodLogStatus.DRAFT);
            e.setEffective(JsonNodeFactory.instance.objectNode().put("foodName", "Nutrition Label"));
            return null;
        }).when(createSupport).applyCacheHitDraft(same(newEntity), same(reusableHit));

        when(envelopeAssembler.assemble(newEntity, null, requestId)).thenReturn(expected);

        try (MockedStatic<ExifTimeExtractor> exifMock = mockStatic(ExifTimeExtractor.class)) {
            exifMock.when(() -> ExifTimeExtractor.tryReadCapturedAtUtc(
                    storage,
                    tempKey,
                    java.time.ZoneId.of("Asia/Taipei")
            )).thenReturn(Optional.empty());

            // act
            FoodLogEnvelope actual = svc.createLabel(
                    1L,
                    "Asia/Taipei",
                    "dev-1",
                    deviceCapturedAtUtc,
                    file,
                    requestId
            );

            // assert
            assertSame(expected, actual);

            verify(entitlementService).resolveTier(1L, fixedNow);
            verify(rateLimiter).checkOrThrow(1L, EntitlementService.Tier.TRIAL, fixedNow);
            verify(inFlight).acquireOrThrow(1L);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<String>> methodsCaptor = ArgumentCaptor.forClass(Collection.class);

            verify(repo).findFirstByUserIdAndMethodInAndImageSha256AndStatusInOrderByCreatedAtUtcDesc(
                    eq(1L),
                    methodsCaptor.capture(),
                    eq(sha256),
                    anyCollection()
            );

            org.junit.jupiter.api.Assertions.assertEquals(
                    java.util.List.of("LABEL"),
                    new java.util.ArrayList<>(methodsCaptor.getValue())
            );

            verify(abuseGuard).onOperationAttempt(
                    1L,
                    "dev-1",
                    true,
                    fixedNow,
                    ZoneOffset.UTC
            );

            verify(createSupport).applyCacheHitDraft(newEntity, reusableHit);
            verifyNoInteractions(quota);

            verify(repo, times(2)).save(newEntity);
            verify(idem).attach(1L, requestId, "log-new-label-1", fixedNow);
            verify(createSupport).retainBlobAndAttach(newEntity, 1L, upload);

            verify(taskRepo, never()).save(any());
            verify(createSupport, never()).createQueuedTask(any());

            verify(envelopeAssembler).assemble(newEntity, null, requestId);
            verify(inFlight).release(lease);
        }
    }

    @Test
    void createLabel_should_consume_quota_and_create_task_when_cache_miss() throws Exception {
        // arrange
        Instant fixedNow = Instant.parse("2026-03-03T00:00:00Z");
        String requestId = "rid-label-miss-1";
        String tempKey = "temp/u1/rid-label-miss-1.jpg";
        String sha256 = "sha-label-002";
        String deviceCapturedAtUtc = "2026-03-03T01:02:03Z";

        UserInFlightLimiter.Lease lease = new UserInFlightLimiter.Lease(1L, "lease-label-miss-1");

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "label.jpg",
                "image/jpeg",
                new byte[]{9, 8, 7, 6}
        );

        FoodLogCreateSupport.UploadTempResult upload = mock(FoodLogCreateSupport.UploadTempResult.class);
        StorageService.SaveResult saved = mock(StorageService.SaveResult.class);

        QuotaService.Decision decision = new QuotaService.Decision(ModelTier.MODEL_TIER_HIGH);

        FoodLogEntity newEntity = new FoodLogEntity();
        newEntity.setId("log-new-label-2");

        FoodLogTaskEntity task = new FoodLogTaskEntity();
        task.setFoodLogId("log-new-label-2");

        FoodLogEnvelope expected = mock(FoodLogEnvelope.class);

        when(clock.instant()).thenReturn(fixedNow);
        when(idem.reserveOrGetExisting(1L, requestId, fixedNow)).thenReturn(null);

        stubResolvedTime(fixedNow, fixedNow);

        doReturn(EntitlementService.Tier.TRIAL)
                .when(entitlementService)
                .resolveTier(1L, fixedNow);

        doNothing().when(rateLimiter).checkOrThrow(1L, EntitlementService.Tier.TRIAL, fixedNow);
        when(inFlight.acquireOrThrow(1L)).thenReturn(lease);

        when(createSupport.uploadTempImage(1L, requestId, file)).thenReturn(upload);
        when(upload.tempKey()).thenReturn(tempKey);
        when(upload.saved()).thenReturn(saved);
        when(saved.sha256()).thenReturn(sha256);

        when(repo.findFirstByUserIdAndMethodInAndImageSha256AndStatusInOrderByCreatedAtUtcDesc(
                eq(1L),
                anyCollection(),
                eq(sha256),
                anyCollection()
        )).thenReturn(Optional.empty());

        doReturn(newEntity).when(createSupport).newBaseEntity(
                eq(1L),
                eq(FoodLogMethod.LABEL),
                any(Instant.class),
                eq("Asia/Taipei"),
                any(LocalDate.class),
                eq(fixedNow),
                eq(TimeSource.SERVER_RECEIVED),
                eq(false)
        );

        when(quota.consumeOperationOrThrow(
                eq(1L),
                eq(EntitlementService.Tier.TRIAL),
                eq(ZoneOffset.UTC),
                eq(fixedNow)
        )).thenReturn(decision);

        when(providerClient.providerCode()).thenReturn("gemini");

        doAnswer(invocation -> {
            FoodLogEntity e = invocation.getArgument(0);
            e.setStatus(FoodLogStatus.PENDING);
            return null;
        }).when(createSupport).applyPendingMiss(
                same(newEntity),
                eq(ModelTier.MODEL_TIER_HIGH),
                eq("GEMINI")
        );

        when(createSupport.createQueuedTask("log-new-label-2")).thenReturn(task);
        when(envelopeAssembler.assemble(newEntity, task, requestId)).thenReturn(expected);

        try (MockedStatic<ExifTimeExtractor> exifMock = mockStatic(ExifTimeExtractor.class)) {
            exifMock.when(() -> ExifTimeExtractor.tryReadCapturedAtUtc(
                    storage,
                    tempKey,
                    java.time.ZoneId.of("Asia/Taipei")
            )).thenReturn(Optional.empty());

            // act
            FoodLogEnvelope actual = svc.createLabel(
                    1L,
                    "Asia/Taipei",
                    "dev-1",
                    deviceCapturedAtUtc,
                    file,
                    requestId
            );

            // assert
            assertSame(expected, actual);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<String>> methodsCaptor = ArgumentCaptor.forClass(Collection.class);

            verify(repo).findFirstByUserIdAndMethodInAndImageSha256AndStatusInOrderByCreatedAtUtcDesc(
                    eq(1L),
                    methodsCaptor.capture(),
                    eq(sha256),
                    anyCollection()
            );

            org.junit.jupiter.api.Assertions.assertEquals(
                    java.util.List.of("LABEL"),
                    new java.util.ArrayList<>(methodsCaptor.getValue())
            );

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
                    eq(ModelTier.MODEL_TIER_HIGH),
                    eq("GEMINI")
            );

            verify(repo, times(2)).save(newEntity);
            verify(idem).attach(1L, requestId, "log-new-label-2", fixedNow);
            verify(createSupport).retainBlobAndAttach(newEntity, 1L, upload);

            verify(createSupport).createQueuedTask("log-new-label-2");
            verify(taskRepo).save(task);

            verify(envelopeAssembler).assemble(newEntity, task, requestId);
            verify(inFlight).release(lease);
        }
    }
}
