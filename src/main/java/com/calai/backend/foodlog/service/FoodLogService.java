package com.calai.backend.foodlog.service;

import com.calai.backend.entitlement.service.EntitlementService;
import com.calai.backend.foodlog.dto.FoodLogEnvelope;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.entity.FoodLogTaskEntity;
import com.calai.backend.foodlog.model.FoodLogErrorCode;
import com.calai.backend.foodlog.model.FoodLogMethod;
import com.calai.backend.foodlog.model.FoodLogStatus;
import com.calai.backend.foodlog.model.TimeSource;
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
import com.calai.backend.foodlog.service.support.FoodLogRequestNormalizer;
import com.calai.backend.foodlog.storage.StorageService;
import com.calai.backend.foodlog.storage.support.StorageCleanup;
import com.calai.backend.foodlog.time.CapturedTimeResolver;
import com.calai.backend.foodlog.time.ExifTimeExtractor;
import com.calai.backend.foodlog.web.error.FoodLogAppException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.*;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
@Service
public class FoodLogService {

    private final ProviderClient providerClient;

    private String defaultProvider() {
        String code = providerClient.providerCode();
        if (code == null || code.isBlank()) {
            throw new IllegalStateException("PROVIDER_CODE_MISSING");
        }
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private static final long MAX_IMAGE_BYTES = 15L * 1024 * 1024;

    private static final List<String> DEDUPE_METHODS_PHOTO_ALBUM = List.of(
            FoodLogMethod.PHOTO.code(),
            FoodLogMethod.ALBUM.code()
    );

    private static final List<String> DEDUPE_METHODS_LABEL = List.of(
            FoodLogMethod.LABEL.code()
    );

    private final FoodLogRepository repo;
    private final FoodLogTaskRepository taskRepo;
    private final StorageService storage;
    private final QuotaService quota;
    private final IdempotencyService idem;
    private final UserInFlightLimiter inFlight;
    private final UserRateLimiter rateLimiter;
    private final Clock clock;
    private final CapturedTimeResolver timeResolver;
    private final AbuseGuardService abuseGuard;
    private final EntitlementService entitlementService;
    private final FoodLogEnvelopeAssembler envelopeAssembler;
    private final FoodLogQueryService queryService;
    private final FoodLogImageAccessService imageAccessService;
    private final FoodLogRetryService retryService;
    private final FoodLogBarcodeService barcodeService;
    private final FoodLogCreateSupport createSupport;
    private final UserDailyNutritionSummaryService dailySummaryService;

    /**
     * createAlbum / createPhoto / createLabel 已經能一眼看懂：
     * 先 reserve
     * validate
     * rate limit / in-flight
     * upload
     * dedupe
     * anti-abuse
     * 建 base entity
     * hit → draft
     * miss → pending
     * finalize result
     */

    @Transactional(rollbackFor = Exception.class)
    public FoodLogEnvelope createAlbum(
            Long userId,
            String clientTz,
            String deviceId,
            MultipartFile file,
            String requestId
    ) throws Exception {

        ZoneId captureTz = FoodLogRequestNormalizer.parseClientTzOrUtc(clientTz);
        ZoneId quotaTz = FoodLogRequestNormalizer.resolveQuotaTz();
        Instant serverNow = clock.instant();

        String existingLogId = idem.reserveOrGetExisting(userId, requestId, serverNow);
        if (existingLogId != null) return getOne(userId, existingLogId, requestId);

        validateUploadBasicsOrRelease(userId, requestId, file);

        EntitlementService.Tier tier = resolveTierAndCheckRateOrRelease(
                userId,
                requestId,
                serverNow
        );

        UserInFlightLimiter.Lease lease = null;
        String tempKey = null;

        try {
            lease = acquireInFlightOrRelease(userId, requestId);

            FoodLogCreateSupport.UploadTempResult upload = createSupport.uploadTempImage(
                    userId,
                    requestId,
                    file
            );

            tempKey = upload.tempKey();
            StorageService.SaveResult saved = upload.saved();

            try {
                // 2) 去重命中（不扣 quota）
                var hit = findReusableHit(userId, DEDUPE_METHODS_PHOTO_ALBUM, saved.sha256());

                boolean cacheHit = hit.isPresent()
                                   && hit.get().getEffective() != null
                                   && hit.get().getEffective().isObject();

                // ✅ Anti-abuse：deviceId 統一 normalize，避免 null/blank key 汙染或 NPE
                String did = FoodLogRequestNormalizer.normalizeDeviceId(userId, deviceId);
                abuseGuard.onOperationAttempt(userId, did, cacheHit, serverNow, quotaTz);

                // ✅ ALBUM：固定用「上傳時間」當作 capturedAtUtc
                LocalDate todayLocal = ZonedDateTime.ofInstant(serverNow, captureTz).toLocalDate();

                FoodLogEntity e = createSupport.newBaseEntity(
                        userId,
                        FoodLogMethod.ALBUM,
                        serverNow,
                        captureTz.getId(),
                        todayLocal,
                        serverNow,
                        TimeSource.SERVER_RECEIVED,
                        false
                );

                if (hit.isPresent() && hit.get().getEffective() != null && hit.get().getEffective().isObject()) {
                    createSupport.applyCacheHitDraft(e, hit.get());
                } else {
                    QuotaService.Decision d = quota.consumeOperationOrThrow(userId, tier, quotaTz, serverNow);
                    createSupport.applyPendingMiss(e, d.tierUsed(), defaultProvider());
                }

                return finalizeCreateResult(userId, requestId, serverNow, e, upload);

            } catch (Exception ex) {
                cleanupUploadOrBlobAfterFailure(storage, userId, requestId, tempKey);
                idem.failAndReleaseIfNeeded(userId, requestId, true);
                throw ex;
            }

        } finally {
            if (lease != null) inFlight.release(lease);
        }
    }

    // =========================
    // S4-08：PHOTO
    // =========================
    @Transactional(rollbackFor = Exception.class)
    public FoodLogEnvelope createPhoto(
            Long userId,
            String clientTz,
            String deviceId,
            String deviceCapturedAtUtc,
            MultipartFile file,
            String requestId
    ) throws Exception {

        ZoneId captureTz = FoodLogRequestNormalizer.parseClientTzOrUtc(clientTz);
        ZoneId quotaTz = FoodLogRequestNormalizer.resolveQuotaTz();
        Instant serverNow = clock.instant();

        String existingLogId = idem.reserveOrGetExisting(userId, requestId, serverNow);
        if (existingLogId != null) return getOne(userId, existingLogId, requestId);

        validateUploadBasicsOrRelease(userId, requestId, file);

        EntitlementService.Tier tier = resolveTierAndCheckRateOrRelease(
                userId,
                requestId,
                serverNow
        );

        UserInFlightLimiter.Lease lease = null;
        String tempKey = null; // ✅ 讓所有 catch 都能刪到正確 key（含副檔名）

        try {
            lease = acquireInFlightOrRelease(userId, requestId);

            FoodLogCreateSupport.UploadTempResult upload = createSupport.uploadTempImage(
                    userId,
                    requestId,
                    file
            );

            tempKey = upload.tempKey();
            StorageService.SaveResult saved = upload.saved();

            try {
                // 2) EXIF（從 tempKey 再 open 一次讀）
                Optional<Instant> exifUtc = ExifTimeExtractor.tryReadCapturedAtUtc(storage, tempKey, captureTz);

                // 3) deviceCapturedAtUtc（App 可傳）
                Instant deviceUtc = parseInstantOrNull(deviceCapturedAtUtc);

                // 4) resolve capturedAtUtc（EXIF → DEVICE → SERVER）
                CapturedTimeResolver.Result r = timeResolver.resolve(exifUtc.orElse(null), deviceUtc, serverNow);

                // 5) 去重命中（不扣 quota）
                var hit = findReusableHit(userId, DEDUPE_METHODS_PHOTO_ALBUM, saved.sha256());

                // ✅ NEW：判斷 cacheHit（必須 effective 是 object 才算真正命中）
                boolean cacheHit = hit.isPresent()
                                   && hit.get().getEffective() != null
                                   && hit.get().getEffective().isObject();

                // ✅ Anti-abuse：deviceId 統一 normalize
                String did = FoodLogRequestNormalizer.normalizeDeviceId(userId, deviceId);
                abuseGuard.onOperationAttempt(userId, did, cacheHit, serverNow, quotaTz);

                // 6) 建 log（capturedLocalDate 用 resolved capturedAtUtc + client tz）
                LocalDate localDate = ZonedDateTime.ofInstant(r.capturedAtUtc(), captureTz).toLocalDate();

                FoodLogEntity e = createSupport.newBaseEntity(
                        userId,
                        FoodLogMethod.PHOTO,
                        r.capturedAtUtc(),
                        captureTz.getId(),
                        localDate,
                        serverNow,
                        TimeSource.valueOf(r.source().name()),
                        r.suspect()
                );

                if (hit.isPresent() && hit.get().getEffective() != null && hit.get().getEffective().isObject()) {
                    createSupport.applyCacheHitDraft(e, hit.get());
                } else {
                    QuotaService.Decision d = quota.consumeOperationOrThrow(userId, tier, quotaTz, serverNow);
                    createSupport.applyPendingMiss(e, d.tierUsed(), defaultProvider());
                }

                return finalizeCreateResult(userId, requestId, serverNow, e, upload);

            } catch (Exception ex) {
                // ✅ retain 後若後續失敗，只清 tempKey；blob orphan 留給背景 cleaner
                cleanupUploadOrBlobAfterFailure(storage, userId, requestId, tempKey);
                idem.failAndReleaseIfNeeded(userId, requestId, true);
                throw ex;
            }

        } finally {
            if (lease != null) inFlight.release(lease);
        }
    }

    // =========================
    // LABEL：營養標示（Gemini 3 Flash）
    // =========================
    @Transactional(rollbackFor = Exception.class)
    public FoodLogEnvelope createLabel(
            Long userId,
            String clientTz,
            String deviceId,
            String deviceCapturedAtUtc,
            MultipartFile file,
            String requestId
    ) throws Exception {

        ZoneId captureTz = FoodLogRequestNormalizer.parseClientTzOrUtc(clientTz);
        ZoneId quotaTz = FoodLogRequestNormalizer.resolveQuotaTz();
        Instant serverNow = clock.instant();

        String existingLogId = idem.reserveOrGetExisting(userId, requestId, serverNow);
        if (existingLogId != null) return getOne(userId, existingLogId, requestId);

        validateUploadBasicsOrRelease(userId, requestId, file);

        // ✅ 仍要限流，避免被打爆（雖然是 AI 才更需要，但 label 也會走 AI）
        EntitlementService.Tier tier = resolveTierAndCheckRateOrRelease(
                userId,
                requestId,
                serverNow
        );

        UserInFlightLimiter.Lease lease = null;
        String tempKey = null;

        try {
            lease = acquireInFlightOrRelease(userId, requestId);

            FoodLogCreateSupport.UploadTempResult upload = createSupport.uploadTempImage(
                    userId,
                    requestId,
                    file
            );

            tempKey = upload.tempKey();
            StorageService.SaveResult saved = upload.saved();

            try {
                // 2) EXIF / device / server time resolve（沿用 photo 規則）
                Optional<Instant> exifUtc = ExifTimeExtractor.tryReadCapturedAtUtc(storage, tempKey, captureTz);
                Instant deviceUtc = parseInstantOrNull(deviceCapturedAtUtc);
                CapturedTimeResolver.Result r = timeResolver.resolve(exifUtc.orElse(null), deviceUtc, serverNow);

                // 3) 去重命中（不扣 quota）
                var hit = findReusableHit(userId, DEDUPE_METHODS_LABEL, saved.sha256());

                // ✅ NEW：判斷 cacheHit（必須 effective 是 object 才算真正命中）
                boolean cacheHit = hit.isPresent()
                                   && hit.get().getEffective() != null
                                   && hit.get().getEffective().isObject();

                // ✅ Anti-abuse：deviceId 統一 normalize
                String did = FoodLogRequestNormalizer.normalizeDeviceId(userId, deviceId);
                abuseGuard.onOperationAttempt(userId, did, cacheHit, serverNow, quotaTz);

                LocalDate localDate = ZonedDateTime.ofInstant(r.capturedAtUtc(), captureTz).toLocalDate();

                FoodLogEntity e = createSupport.newBaseEntity(
                        userId,
                        FoodLogMethod.LABEL,
                        r.capturedAtUtc(),
                        captureTz.getId(),
                        localDate,
                        serverNow,
                        TimeSource.valueOf(r.source().name()),
                        r.suspect()
                );

                if (hit.isPresent() && hit.get().getEffective() != null && hit.get().getEffective().isObject()) {
                    createSupport.applyCacheHitDraft(e, hit.get());
                } else {
                    QuotaService.Decision d = quota.consumeOperationOrThrow(userId, tier, quotaTz, serverNow);
                    createSupport.applyPendingMiss(e, d.tierUsed(), defaultProvider());
                }

                return finalizeCreateResult(userId, requestId, serverNow, e, upload);

            } catch (Exception ex) {
                cleanupUploadOrBlobAfterFailure(storage, userId, requestId, tempKey);
                idem.failAndReleaseIfNeeded(userId, requestId, true);
                throw ex;
            }

        } finally {
            if (lease != null) inFlight.release(lease);
        }
    }

    public FoodLogEnvelope createBarcodeMvp(
            Long userId,
            String clientTz,
            String deviceId,
            String barcode,
            String preferredLangTag,
            String requestId
    ) {
        return barcodeService.createBarcodeMvp(
                userId,
                clientTz,
                deviceId,
                barcode,
                preferredLangTag,
                requestId
        );
    }

    // ===== FoodLogService.getOne()：只在 QUEUED/RUNNING/FAILED 才回 task =====
    public FoodLogEnvelope getOne(Long userId, String id, String requestId) {
        return queryService.getOne(userId, id, requestId);
    }

    @Transactional(readOnly = true)
    public ImageOpenResult openImage(Long userId, String foodLogId) {
        return imageAccessService.openImage(userId, foodLogId);
    }

    public InputStream openImageStream(String objectKey) throws Exception {
        return imageAccessService.openImageStream(objectKey);
    }

    public FoodLogEnvelope retry(
            Long userId,
            String foodLogId,
            String deviceId,
            String requestId
    ) {
        return retryService.retry(userId, foodLogId, deviceId, requestId);
    }

    // =========================
    // helpers
    // =========================
    private void validateUploadBasicsOrRelease(Long userId, String requestId, MultipartFile file) {
        try {
            validateUploadBasics(file);
        } catch (RuntimeException ex) {
            idem.failAndReleaseIfNeeded(
                    userId,
                    requestId,
                    true
            );
            throw ex;
        }
    }

    private EntitlementService.Tier resolveTierAndCheckRateOrRelease(
            Long userId,
            String requestId,
            Instant nowUtc
    ) {
        try {
            EntitlementService.Tier tier = entitlementService.resolveTier(userId, nowUtc);
            rateLimiter.checkOrThrow(userId, tier, nowUtc);
            return tier;
        } catch (RuntimeException ex) {
            idem.failAndReleaseIfNeeded(
                    userId,
                    requestId,
                    true
            );
            throw ex;
        }
    }

    private static void cleanupUploadOrBlobAfterFailure(
            StorageService storage,
            Long userId,
            String requestId,
            String tempKey
    ) {
        // ⚠️ 絕對不要在這裡直接刪 blobKey
        //
        // 原因：
        // 本次請求即使剛建立 blob，也不代表 cleanup 執行時沒有其他請求已經開始共用。
        // 若直接刪 blobKey，併發下可能把其他成功請求正在引用的共享檔刪掉。
        //
        // 安全策略：
        // - 只清 tempKey（best effort）
        // - sha256/blob orphan 留給背景 orphan cleaner 處理
        StorageCleanup.safeDeleteQuietly(storage, tempKey);

        if (tempKey == null) {
            StorageCleanup.safeDeleteTempUploadFallback(storage, userId, requestId);
        }
    }

    private UserInFlightLimiter.Lease acquireInFlightOrRelease(Long userId, String requestId) {
        try {
            return inFlight.acquireOrThrow(userId);
        } catch (RuntimeException ex) {
            idem.failAndReleaseIfNeeded(
                    userId,
                    requestId,
                    true
            );
            throw ex;
        }
    }

    private static Instant parseInstantOrNull(String raw) {
        try {
            if (raw == null || raw.isBlank()) return null;
            return Instant.parse(raw.trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void validateUploadBasics(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new FoodLogAppException(FoodLogErrorCode.FILE_REQUIRED);
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            throw new FoodLogAppException(FoodLogErrorCode.FILE_TOO_LARGE);
        }
    }

    private Optional<FoodLogEntity> findReusableHit(
            Long userId,
            List<String> methods,
            String sha256
    ) {
        return repo.findFirstByUserIdAndMethodInAndImageSha256AndStatusInOrderByCreatedAtUtcDesc(
                userId,
                methods,
                sha256,
                List.of(FoodLogStatus.DRAFT, FoodLogStatus.SAVED)
        );
    }

    private FoodLogEnvelope finalizeCreateResult(
            Long userId,
            String requestId,
            Instant serverNow,
            FoodLogEntity e,
            FoodLogCreateSupport.UploadTempResult upload
    ) throws Exception {
        repo.save(e);
        idem.attach(userId, requestId, e.getId(), serverNow);

        createSupport.retainBlobAndAttach(e, userId, upload);
        repo.save(e);

        if (e.getStatus() == FoodLogStatus.DRAFT) {
            dailySummaryService.recomputeDay(e.getUserId(), e.getCapturedLocalDate());
            return envelopeAssembler.assemble(e, null, requestId);
        }

        FoodLogTaskEntity t = createSupport.createQueuedTask(e.getId());
        taskRepo.save(t);

        return envelopeAssembler.assemble(e, t, requestId);
    }
}
