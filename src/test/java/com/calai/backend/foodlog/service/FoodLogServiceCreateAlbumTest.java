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
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FoodLogServiceCreateAlbumTest {

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
    void createAlbum_should_return_draft_without_quota_or_task_when_cache_hit() throws Exception {
        Instant fixedNow = Instant.parse("2026-03-03T00:00:00Z");
        LocalDate expectedLocalDate = LocalDate.of(2026, 3, 3);
        String requestId = "rid-album-hit-1";
        String tempKey = "temp/u1/rid-album-hit-1.jpg";
        String sha256 = "sha-album-001";

        UserInFlightLimiter.Lease lease = new UserInFlightLimiter.Lease(1L, "lease-album-hit-1");

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "album.jpg",
                "image/jpeg",
                new byte[]{1, 2, 3, 4}
        );

        FoodLogCreateSupport.UploadTempResult upload = mock(FoodLogCreateSupport.UploadTempResult.class);
        StorageService.SaveResult saved = mock(StorageService.SaveResult.class);

        FoodLogEntity reusableHit = new FoodLogEntity();
        reusableHit.setId("log-old-album-1");
        reusableHit.setStatus(FoodLogStatus.DRAFT);
        reusableHit.setEffective(JsonNodeFactory.instance.objectNode().put("foodName", "Chicken Salad"));

        FoodLogEntity newEntity = new FoodLogEntity();
        newEntity.setId("log-new-album-1");
        newEntity.setUserId(1L);
        newEntity.setCapturedLocalDate(expectedLocalDate);

        FoodLogEnvelope expected = mock(FoodLogEnvelope.class);

        when(clock.instant()).thenReturn(fixedNow);
        when(idem.reserveOrGetExisting(1L, requestId, fixedNow)).thenReturn(null);

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

        when(createSupport.newBaseEntity(
                eq(1L),
                eq(FoodLogMethod.ALBUM),
                eq(fixedNow),
                eq("Asia/Taipei"),
                eq(expectedLocalDate),
                eq(fixedNow),
                eq(TimeSource.SERVER_RECEIVED),
                eq(false)
        )).thenReturn(newEntity);

        doAnswer(invocation -> {
            FoodLogEntity e = invocation.getArgument(0);
            e.setStatus(FoodLogStatus.DRAFT);
            e.setEffective(JsonNodeFactory.instance.objectNode().put("foodName", "Chicken Salad"));
            e.setUserId(1L);
            e.setCapturedLocalDate(expectedLocalDate);
            return null;
        }).when(createSupport).applyCacheHitDraft(same(newEntity), same(reusableHit));

        when(envelopeAssembler.assemble(newEntity, null, requestId)).thenReturn(expected);

        FoodLogEnvelope actual = svc.createAlbum(
                1L,
                "Asia/Taipei",
                "dev-1",
                file,
                requestId
        );

        assertSame(expected, actual);

        verify(createSupport).newBaseEntity(
                1L,
                FoodLogMethod.ALBUM,
                fixedNow,
                "Asia/Taipei",
                expectedLocalDate,
                fixedNow,
                TimeSource.SERVER_RECEIVED,
                false
        );

        verify(abuseGuard).onOperationAttempt(
                1L,
                "dev-1",
                true,
                fixedNow,
                ZoneOffset.UTC
        );

        verifyNoInteractions(quota);

        verify(repo, times(2)).save(newEntity);
        verify(idem).attach(1L, requestId, "log-new-album-1", fixedNow);
        verify(createSupport).retainBlobAndAttach(newEntity, 1L, upload);

        verify(dailySummaryService).recomputeDay(1L, expectedLocalDate);

        verify(taskRepo, never()).save(any());
        verify(createSupport, never()).createQueuedTask(any());

        verify(envelopeAssembler).assemble(newEntity, null, requestId);
        verify(inFlight).release(lease);
    }

    @Test
    void createAlbum_should_consume_quota_and_create_task_when_cache_miss() throws Exception {
        Instant fixedNow = Instant.parse("2026-03-03T00:00:00Z");
        LocalDate expectedLocalDate = LocalDate.of(2026, 3, 3);
        String requestId = "rid-album-miss-1";
        String tempKey = "temp/u1/rid-album-miss-1.jpg";
        String sha256 = "sha-album-002";

        UserInFlightLimiter.Lease lease = new UserInFlightLimiter.Lease(1L, "lease-album-miss-1");

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "album.jpg",
                "image/jpeg",
                new byte[]{9, 8, 7, 6}
        );

        FoodLogCreateSupport.UploadTempResult upload = mock(FoodLogCreateSupport.UploadTempResult.class);
        StorageService.SaveResult saved = mock(StorageService.SaveResult.class);
        QuotaService.Decision decision = mock(QuotaService.Decision.class);

        FoodLogEntity newEntity = new FoodLogEntity();
        newEntity.setId("log-new-album-2");
        newEntity.setUserId(1L);
        newEntity.setCapturedLocalDate(expectedLocalDate);

        FoodLogTaskEntity task = new FoodLogTaskEntity();
        task.setFoodLogId("log-new-album-2");

        FoodLogEnvelope expected = mock(FoodLogEnvelope.class);

        when(clock.instant()).thenReturn(fixedNow);
        when(idem.reserveOrGetExisting(1L, requestId, fixedNow)).thenReturn(null);

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
        )).thenReturn(Optional.empty());

        when(createSupport.newBaseEntity(
                eq(1L),
                eq(FoodLogMethod.ALBUM),
                eq(fixedNow),
                eq("Asia/Taipei"),
                eq(expectedLocalDate),
                eq(fixedNow),
                eq(TimeSource.SERVER_RECEIVED),
                eq(false)
        )).thenReturn(newEntity);

        when(quota.consumeOperationOrThrow(
                eq(1L),
                eq(EntitlementService.Tier.TRIAL),
                eq(ZoneOffset.UTC),
                eq(fixedNow)
        )).thenReturn(decision);
        when(decision.tierUsed()).thenReturn(ModelTier.MODEL_TIER_HIGH);

        when(providerClient.providerCode()).thenReturn("gemini");

        doAnswer(invocation -> {
            FoodLogEntity e = invocation.getArgument(0);
            e.setStatus(FoodLogStatus.PENDING);
            e.setUserId(1L);
            e.setCapturedLocalDate(expectedLocalDate);
            return null;
        }).when(createSupport).applyPendingMiss(
                same(newEntity),
                eq(ModelTier.MODEL_TIER_HIGH),
                eq("GEMINI")
        );

        when(createSupport.createQueuedTask("log-new-album-2")).thenReturn(task);
        when(envelopeAssembler.assemble(newEntity, task, requestId)).thenReturn(expected);

        FoodLogEnvelope actual = svc.createAlbum(
                1L,
                "Asia/Taipei",
                "dev-1",
                file,
                requestId
        );

        assertSame(expected, actual);

        verify(createSupport).newBaseEntity(
                1L,
                FoodLogMethod.ALBUM,
                fixedNow,
                "Asia/Taipei",
                expectedLocalDate,
                fixedNow,
                TimeSource.SERVER_RECEIVED,
                false
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
        verify(idem).attach(1L, requestId, "log-new-album-2", fixedNow);
        verify(createSupport).retainBlobAndAttach(newEntity, 1L, upload);

        verify(createSupport).createQueuedTask("log-new-album-2");
        verify(taskRepo).save(task);

        verify(dailySummaryService, never()).recomputeDay(any(), any());

        verify(envelopeAssembler).assemble(newEntity, task, requestId);
        verify(inFlight).release(lease);
    }
}
