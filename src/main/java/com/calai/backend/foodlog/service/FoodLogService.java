package com.calai.backend.foodlog.service;

import com.calai.backend.entitlement.service.EntitlementService;
import com.calai.backend.foodlog.barcode.*;
import com.calai.backend.foodlog.config.FoodLogTierResolver;
import com.calai.backend.foodlog.dto.FoodLogEnvelope;
import com.calai.backend.foodlog.mapper.OffCategoryResolver;
import com.calai.backend.foodlog.mapper.OffEffectiveBuilder;
import com.calai.backend.foodlog.quota.guard.AbuseGuardService;
import com.calai.backend.foodlog.model.FoodLogStatus;
import com.calai.backend.foodlog.model.ProviderRefuseReason;
import com.calai.backend.foodlog.model.TimeSource;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.entity.FoodLogTaskEntity;
import com.calai.backend.foodlog.image.ImageSniffer;
import com.calai.backend.foodlog.mapper.ClientActionMapper;
import com.calai.backend.foodlog.model.ClientAction;
import com.calai.backend.foodlog.quota.model.ModelTier;
import com.calai.backend.foodlog.quota.service.QuotaService;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.repo.FoodLogTaskRepository;
import com.calai.backend.foodlog.service.limiter.UserInFlightLimiter;
import com.calai.backend.foodlog.service.limiter.UserRateLimiter;
import com.calai.backend.foodlog.storage.StorageService;
import com.calai.backend.foodlog.task.FoodLogWarning;
import com.calai.backend.foodlog.task.ProviderClient;
import com.calai.backend.foodlog.time.CapturedTimeResolver;
import com.calai.backend.foodlog.time.ExifTimeExtractor;
import com.calai.backend.foodlog.web.ModelRefusedException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import com.calai.backend.foodlog.service.cleanup.StorageCleanup;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.List;
import java.util.Optional;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.calai.backend.foodlog.task.EffectivePostProcessor;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.calai.backend.foodlog.barcode.mapper.OpenFoodFactsMapper.OffResult;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

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

    private static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024; // 8MB（先保守）
    private static final List<String> DEDUPE_METHODS_PHOTO_ALBUM = List.of("PHOTO", "ALBUM");
    private static final List<String> DEDUPE_METHODS_LABEL = List.of("LABEL");

    /**
     * ✅ 所有入口統一的 deviceId normalize：
     * - null / blank → "uid-{userId}"
     * - trim 後回傳
     */
    private static String normalizeDeviceId(Long userId, String deviceId) {
        if (deviceId == null) return "uid-" + userId;
        String s = deviceId.trim();
        return s.isEmpty() ? ("uid-" + userId) : s;
    }

    private final FoodLogRepository repo;
    private final FoodLogTaskRepository taskRepo;
    private final StorageService storage;
    private final QuotaService quota;
    private final IdempotencyService idem;
    private final ImageBlobService blobService;
    private final UserInFlightLimiter inFlight;
    private final UserRateLimiter rateLimiter;
    private final EffectivePostProcessor postProcessor;
    private final ClientActionMapper clientActionMapper;
    private final Clock clock;
    private final CapturedTimeResolver timeResolver = new CapturedTimeResolver();
    public record OpenedImage(String objectKey, String contentType, long sizeBytes) {}
    private final AbuseGuardService abuseGuard;
    private final EntitlementService entitlementService;
    private final BarcodeLookupService barcodeLookupService;
    private final TransactionTemplate txTemplate;

    // =========================
    // S4-05：ALBUM
    // =========================
    @Transactional(rollbackFor = Exception.class)
    public FoodLogEnvelope createAlbum(
            Long userId,
            String clientTz,
            String deviceId,
            MultipartFile file,
            String requestId
    ) throws Exception {

        ZoneId captureTz = parseTzOrUtc(clientTz);
        ZoneId quotaTz = parseTzOrUtc(clientTz);
        Instant serverNow = clock.instant();
        validateUploadBasics(file);

        String existingLogId = idem.reserveOrGetExisting(userId, requestId, serverNow);
        if (existingLogId != null) return getOne(userId, existingLogId, requestId);

        EntitlementService.Tier tier = resolveTierAndCheckRateOrRelease(
                userId,
                requestId,
                serverNow
        );

        boolean acquired = false;
        String tempKey = null;
        ImageBlobService.RetainResult retained = null; // ✅ retain 後補償用

        try {
            acquireInFlightOrRelease(userId, requestId);
            acquired = true;

            ImageSniffer.Detection det;
            StorageService.SaveResult saved;

            // 1) 上傳 → tempKey
            try (InputStream raw = file.getInputStream();
                 PushbackInputStream in = new PushbackInputStream(raw, 16)) {

                det = ImageSniffer.detect(in);
                if (det == null) throw new IllegalArgumentException("UNSUPPORTED_IMAGE_FORMAT");

                tempKey = "user-" + userId + "/blobs/tmp/" + requestId + "/upload" + det.ext();
                saved = storage.save(tempKey, in, det.contentType());

            } catch (Exception ex) {
                StorageCleanup.safeDeleteQuietly(storage, tempKey);
                if (tempKey == null) StorageCleanup.safeDeleteTempUploadFallback(storage, userId, requestId);
                idem.failAndReleaseIfNeeded(userId, requestId, "UPLOAD_FAILED", safeMsg(ex), true);
                throw ex;
            }

            try {
                // 2) 去重命中（不扣 quota）
                var hit = repo.findFirstByUserIdAndMethodInAndImageSha256AndStatusInOrderByCreatedAtUtcDesc(
                        userId,
                        DEDUPE_METHODS_PHOTO_ALBUM,
                        saved.sha256(),
                        List.of(FoodLogStatus.DRAFT, FoodLogStatus.SAVED)
                );

                boolean cacheHit = hit.isPresent()
                                   && hit.get().getEffective() != null
                                   && hit.get().getEffective().isObject();

                // ✅ Anti-abuse：deviceId 統一 normalize，避免 null/blank key 汙染或 NPE
                String did = normalizeDeviceId(userId, deviceId);
                abuseGuard.onOperationAttempt(userId, did, cacheHit, serverNow, quotaTz);

                // ✅ ALBUM：固定用「上傳時間」當作 capturedAtUtc
                LocalDate todayLocal = ZonedDateTime.ofInstant(serverNow, captureTz).toLocalDate();

                FoodLogEntity e = new FoodLogEntity();
                e.setUserId(userId);
                e.setMethod("ALBUM");
                e.setDegradeLevel("DG-0");

                e.setCapturedAtUtc(serverNow);
                e.setCapturedTz(captureTz.getId());
                e.setCapturedLocalDate(todayLocal);
                e.setServerReceivedAtUtc(serverNow);

                e.setTimeSource(TimeSource.SERVER_RECEIVED);
                e.setTimeSuspect(false);

                if (hit.isPresent() && hit.get().getEffective() != null && hit.get().getEffective().isObject()) {
                    e.setProvider(hit.get().getProvider());
                    e.setDegradeLevel(hit.get().getDegradeLevel() == null ? "DG-0" : hit.get().getDegradeLevel());

                    ObjectNode copied = hit.get().getEffective().deepCopy();
                    ObjectNode processed = postProcessor.apply(copied, e.getProvider(), e.getMethod());
                    markResultFromCache(processed);
                    e.setEffective(processed);
                    e.setStatus(FoodLogStatus.DRAFT);
                } else {
                    QuotaService.Decision d = quota.consumeOperationOrThrow(userId, tier, quotaTz, serverNow);
                    e.setDegradeLevel(d.tierUsed() == ModelTier.MODEL_TIER_HIGH ? "DG-0" : "DG-2");
                    e.setProvider(defaultProvider());
                    e.setEffective(null);
                    e.setStatus(FoodLogStatus.PENDING);
                }

                repo.save(e);
                idem.attach(userId, requestId, e.getId(), serverNow);

                // 3) temp -> blob + refCount
                retained = blobService.retainFromTemp(
                        userId,
                        tempKey,
                        saved.sha256(),
                        det.ext(),
                        saved.contentType(),
                        saved.sizeBytes()
                );

                e.setImageObjectKey(retained.objectKey());
                e.setImageSha256(retained.sha256());
                e.setImageContentType(saved.contentType());
                e.setImageSizeBytes(saved.sizeBytes());
                repo.save(e);

                if (e.getStatus() == FoodLogStatus.DRAFT) {
                    return toEnvelope(e, null, requestId);
                }

                FoodLogTaskEntity t = new FoodLogTaskEntity();
                t.setFoodLogId(e.getId());
                t.setTaskStatus(FoodLogTaskEntity.TaskStatus.QUEUED);
                t.setPollAfterSec(2);
                t.setNextRetryAtUtc(null);
                taskRepo.save(t);

                return toEnvelope(e, t, requestId);

            } catch (Exception ex) {
                cleanupUploadOrBlobAfterFailure(storage, userId, requestId, tempKey, retained);
                idem.failAndReleaseIfNeeded(userId, requestId, "CREATE_ALBUM_FAILED", safeMsg(ex), true);
                throw ex;
            }

        } finally {
            if (acquired) inFlight.release(userId);
        }
    }

    // =========================
    // S4-08：PHOTO
    // =========================
    @Transactional(rollbackFor = Exception.class)
    public FoodLogEnvelope createPhoto(Long userId, String clientTz, String deviceId, String deviceCapturedAtUtc, MultipartFile file, String requestId) throws Exception {

        ZoneId captureTz = parseTzOrUtc(clientTz);
        ZoneId quotaTz = parseTzOrUtc(clientTz);
        Instant serverNow = clock.instant();
        validateUploadBasics(file);

        String existingLogId = idem.reserveOrGetExisting(userId, requestId, serverNow);
        if (existingLogId != null) return getOne(userId, existingLogId, requestId);

        EntitlementService.Tier tier = resolveTierAndCheckRateOrRelease(
                userId,
                requestId,
                serverNow
        );

        boolean acquired = false;
        String tempKey = null; // ✅ 讓所有 catch 都能刪到正確 key（含副檔名）
        ImageBlobService.RetainResult retained = null; // ✅ retain 後補償用

        try {
            acquireInFlightOrRelease(userId, requestId);
            acquired = true;

            ImageSniffer.Detection det;
            StorageService.SaveResult saved;

            // 1) 上傳 → tempKey
            try (InputStream raw = file.getInputStream();
                 PushbackInputStream in = new PushbackInputStream(raw, 16)) {

                det = ImageSniffer.detect(in);
                if (det == null) throw new IllegalArgumentException("UNSUPPORTED_IMAGE_FORMAT");

                // ✅ detect 成功就先決定 tempKey（帶 ext）
                tempKey = "user-" + userId + "/blobs/tmp/" + requestId + "/upload" + det.ext();

                saved = storage.save(tempKey, in, det.contentType());

            } catch (Exception ex) {
                StorageCleanup.safeDeleteQuietly(storage, tempKey);
                if (tempKey == null) StorageCleanup.safeDeleteTempUploadFallback(storage, userId, requestId);
                idem.failAndReleaseIfNeeded(userId, requestId, "UPLOAD_FAILED", safeMsg(ex), true);
                throw ex;
            }

            try {
                // 2) EXIF（從 tempKey 再 open 一次讀）
                Optional<Instant> exifUtc = ExifTimeExtractor.tryReadCapturedAtUtc(storage, tempKey, captureTz);

                // 3) deviceCapturedAtUtc（App 可傳）
                Instant deviceUtc = parseInstantOrNull(deviceCapturedAtUtc);

                // 4) resolve capturedAtUtc（EXIF → DEVICE → SERVER）
                CapturedTimeResolver.Result r = timeResolver.resolve(exifUtc.orElse(null), deviceUtc, serverNow);

                // 5) 去重命中（不扣 quota）
                var hit = repo.findFirstByUserIdAndMethodInAndImageSha256AndStatusInOrderByCreatedAtUtcDesc(
                        userId,
                        DEDUPE_METHODS_PHOTO_ALBUM,
                        saved.sha256(),
                        List.of(FoodLogStatus.DRAFT, FoodLogStatus.SAVED)
                );

                // ✅ NEW：判斷 cacheHit（必須 effective 是 object 才算真正命中）
                boolean cacheHit = hit.isPresent()
                                   && hit.get().getEffective() != null
                                   && hit.get().getEffective().isObject();

                // ✅ Anti-abuse：deviceId 統一 normalize
                String did = normalizeDeviceId(userId, deviceId);
                abuseGuard.onOperationAttempt(userId, did, cacheHit, serverNow, quotaTz);

                // 6) 建 log（capturedLocalDate 用 resolved capturedAtUtc + client tz）
                LocalDate localDate = ZonedDateTime.ofInstant(r.capturedAtUtc(), captureTz).toLocalDate();

                FoodLogEntity e = new FoodLogEntity();
                e.setUserId(userId);
                e.setMethod("PHOTO");
                e.setDegradeLevel("DG-0");

                e.setCapturedAtUtc(r.capturedAtUtc());
                e.setCapturedTz(captureTz.getId());
                e.setCapturedLocalDate(localDate);
                e.setServerReceivedAtUtc(serverNow);

                e.setTimeSource(TimeSource.valueOf(r.source().name()));
                e.setTimeSuspect(r.suspect());

                if (hit.isPresent() && hit.get().getEffective() != null && hit.get().getEffective().isObject()) {
                    e.setProvider(hit.get().getProvider());
                    e.setDegradeLevel(hit.get().getDegradeLevel() == null ? "DG-0" : hit.get().getDegradeLevel());

                    ObjectNode copied = hit.get().getEffective().deepCopy();

                    // ✅ 讓 “去重命中” 也套用同一套後處理（healthScore/meta/non-food）
                    ObjectNode processed = postProcessor.apply(copied, e.getProvider(), e.getMethod());
                    markResultFromCache(processed);
                    e.setEffective(processed);
                    e.setStatus(FoodLogStatus.DRAFT);
                } else {
                    QuotaService.Decision d = quota.consumeOperationOrThrow(userId, tier, quotaTz, serverNow);
                    // 先用 degradeLevel 反映 tier，方便你立刻觀測（Step 2 才會真正選模型）
                    e.setDegradeLevel(d.tierUsed() == ModelTier.MODEL_TIER_HIGH ? "DG-0" : "DG-2");
                    e.setProvider(defaultProvider());
                    e.setEffective(null);
                    e.setStatus(FoodLogStatus.PENDING);
                }

                repo.save(e);
                idem.attach(userId, requestId, e.getId(), serverNow);

                // 7) temp -> blob + refCount
                retained = blobService.retainFromTemp(
                        userId,
                        tempKey,
                        saved.sha256(),
                        det.ext(),
                        saved.contentType(),
                        saved.sizeBytes()
                );

                e.setImageObjectKey(retained.objectKey());
                e.setImageSha256(retained.sha256());
                e.setImageContentType(saved.contentType());
                e.setImageSizeBytes(saved.sizeBytes());
                repo.save(e);

                // 8) 命中：不建 task
                if (e.getStatus() == FoodLogStatus.DRAFT) {
                    return toEnvelope(e, null, requestId);
                }

                // 9) 未命中：建 task
                FoodLogTaskEntity t = new FoodLogTaskEntity();
                t.setFoodLogId(e.getId());
                t.setTaskStatus(FoodLogTaskEntity.TaskStatus.QUEUED);
                t.setPollAfterSec(2);
                t.setNextRetryAtUtc(null);
                taskRepo.save(t);

                return toEnvelope(e, t, requestId);

            } catch (Exception ex) {
                // ✅ 若 retain 後失敗：新建 blob 要刪 blobKey；重用 blob 不可誤刪
                cleanupUploadOrBlobAfterFailure(storage, userId, requestId, tempKey, retained);
                idem.failAndReleaseIfNeeded(userId, requestId, "CREATE_PHOTO_FAILED", safeMsg(ex), true);
                throw ex;
            }

        } finally {
            if (acquired) inFlight.release(userId);
        }
    }

    // =========================
    // LABEL：營養標示（Gemini 3 Flash）
    // =========================
    @Transactional(rollbackFor = Exception.class)
    public FoodLogEnvelope createLabel(Long userId, String clientTz, String deviceId, String deviceCapturedAtUtc, MultipartFile file, String requestId) throws Exception {

        ZoneId captureTz = parseTzOrUtc(clientTz);
        ZoneId quotaTz = parseTzOrUtc(clientTz);
        Instant serverNow = clock.instant();
        validateUploadBasics(file);

        String existingLogId = idem.reserveOrGetExisting(userId, requestId, serverNow);
        if (existingLogId != null) return getOne(userId, existingLogId, requestId);

        // ✅ 仍要限流，避免被打爆（雖然是 AI 才更需要，但 label 也會走 AI）
        EntitlementService.Tier tier = resolveTierAndCheckRateOrRelease(
                userId,
                requestId,
                serverNow
        );

        boolean acquired = false;
        String tempKey = null;
        ImageBlobService.RetainResult retained = null; // ✅ retain 後補償用

        try {
            acquireInFlightOrRelease(userId, requestId);
            acquired = true;

            ImageSniffer.Detection det;
            StorageService.SaveResult saved;

            // 1) 上傳 → tempKey
            try (InputStream raw = file.getInputStream();
                 PushbackInputStream in = new PushbackInputStream(raw, 16)) {

                det = ImageSniffer.detect(in);
                if (det == null) throw new IllegalArgumentException("UNSUPPORTED_IMAGE_FORMAT");

                tempKey = "user-" + userId + "/blobs/tmp/" + requestId + "/upload" + det.ext();
                saved = storage.save(tempKey, in, det.contentType());

            } catch (Exception ex) {
                StorageCleanup.safeDeleteQuietly(storage, tempKey);
                if (tempKey == null) StorageCleanup.safeDeleteTempUploadFallback(storage, userId, requestId);

                idem.failAndReleaseIfNeeded(userId, requestId, "UPLOAD_FAILED", safeMsg(ex), true);
                throw ex;
            }

            try {
                // 2) EXIF / device / server time resolve（沿用 photo 規則）
                Optional<Instant> exifUtc = ExifTimeExtractor.tryReadCapturedAtUtc(storage, tempKey, captureTz);
                Instant deviceUtc = parseInstantOrNull(deviceCapturedAtUtc);
                CapturedTimeResolver.Result r = timeResolver.resolve(exifUtc.orElse(null), deviceUtc, serverNow);

                // 3) 去重命中（不扣 quota）
                var hit = repo.findFirstByUserIdAndMethodInAndImageSha256AndStatusInOrderByCreatedAtUtcDesc(
                        userId,
                        DEDUPE_METHODS_LABEL,
                        saved.sha256(),
                        List.of(FoodLogStatus.DRAFT, FoodLogStatus.SAVED)
                );

                // ✅ NEW：判斷 cacheHit（必須 effective 是 object 才算真正命中）
                boolean cacheHit = hit.isPresent()
                                   && hit.get().getEffective() != null
                                   && hit.get().getEffective().isObject();

                // ✅ Anti-abuse：deviceId 統一 normalize
                String did = normalizeDeviceId(userId, deviceId);
                abuseGuard.onOperationAttempt(userId, did, cacheHit, serverNow, quotaTz);

                LocalDate localDate = ZonedDateTime.ofInstant(r.capturedAtUtc(), captureTz).toLocalDate();

                FoodLogEntity e = new FoodLogEntity();
                e.setUserId(userId);
                e.setMethod("LABEL");
                e.setDegradeLevel("DG-0");

                e.setCapturedAtUtc(r.capturedAtUtc());
                e.setCapturedTz(captureTz.getId());
                e.setCapturedLocalDate(localDate);
                e.setServerReceivedAtUtc(serverNow);

                e.setTimeSource(TimeSource.valueOf(r.source().name()));
                e.setTimeSuspect(r.suspect());

                if (hit.isPresent() && hit.get().getEffective() != null && hit.get().getEffective().isObject()) {
                    // ✅ 命中：直接複製 effective，不扣 quota、不建 task
                    e.setProvider(hit.get().getProvider());
                    e.setDegradeLevel(hit.get().getDegradeLevel() == null ? "DG-0" : hit.get().getDegradeLevel());

                    ObjectNode copied = hit.get().getEffective().deepCopy();
                    ObjectNode processed = postProcessor.apply(copied, e.getProvider(), e.getMethod());
                    markResultFromCache(processed);
                    e.setEffective(processed);
                    e.setStatus(FoodLogStatus.DRAFT);
                } else {
                    // ✅ 未命中：扣 AI quota，provider 由實際 ProviderClient 決定
                    QuotaService.Decision d = quota.consumeOperationOrThrow(userId, tier, quotaTz, serverNow);
                    // 先用 degradeLevel 反映 tier，方便你立刻觀測（Step 2 才會真正選模型）
                    e.setDegradeLevel(d.tierUsed() == ModelTier.MODEL_TIER_HIGH ? "DG-0" : "DG-2");
                    e.setProvider(defaultProvider());
                    e.setEffective(null);
                    e.setStatus(FoodLogStatus.PENDING);
                }

                repo.save(e);
                idem.attach(userId, requestId, e.getId(), serverNow);

                // 4) temp -> blob + refCount
                retained = blobService.retainFromTemp(
                        userId,
                        tempKey,
                        saved.sha256(),
                        det.ext(),
                        saved.contentType(),
                        saved.sizeBytes()
                );

                e.setImageObjectKey(retained.objectKey());
                e.setImageSha256(retained.sha256());
                e.setImageContentType(saved.contentType());
                e.setImageSizeBytes(saved.sizeBytes());
                repo.save(e);

                // 5) 命中：不建 task
                if (e.getStatus() == FoodLogStatus.DRAFT) {
                    return toEnvelope(e, null, requestId);
                }

                // 6) 未命中：建 task（worker 會跑 GEMINI）
                FoodLogTaskEntity t = new FoodLogTaskEntity();
                t.setFoodLogId(e.getId());
                t.setTaskStatus(FoodLogTaskEntity.TaskStatus.QUEUED);
                t.setPollAfterSec(2);
                t.setNextRetryAtUtc(null);
                taskRepo.save(t);

                return toEnvelope(e, t, requestId);

            } catch (Exception ex) {
                cleanupUploadOrBlobAfterFailure(storage, userId, requestId, tempKey, retained);
                idem.failAndReleaseIfNeeded(userId, requestId, "CREATE_LABEL_FAILED", safeMsg(ex), true);
                throw ex;
            }

        } finally {
            if (acquired) inFlight.release(userId);
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
        Instant now = clock.instant();

        if (barcode == null || barcode.isBlank()) {
            throw new IllegalArgumentException("BARCODE_REQUIRED");
        }

        // ✅ normalizeOrThrow 已完成格式/長度/digits 驗證
        // raw 保留給 trace/aiMeta；norm 才是查詢/DB key
        var bn = BarcodeNormalizer.normalizeOrThrow(barcode);
        String bcRaw = bn.rawInput();
        String bcNorm = bn.normalized();

        // ✅ 1) Idempotency 最早（這裡會自己開短 tx）
        String existingLogId = idem.reserveOrGetExisting(userId, requestId, now);
        if (existingLogId != null) return getOne(userId, existingLogId, requestId);

        try {
            ZoneId captureTz = parseTzOrUtc(clientTz);
            ZoneId quotaTz = parseTzOrUtc(clientTz);
            LocalDate localDate = ZonedDateTime.ofInstant(now, captureTz).toLocalDate();

            // ✅ 2) deviceId normalize
            String did = normalizeDeviceId(userId, deviceId);

            // ✅ 3) rate limit（交易外）
            EntitlementService.Tier tier = entitlementService.resolveTier(userId, now);
            rateLimiter.checkOrThrow(userId, tier, now);

            // ✅ 4) barcode cheap op（交易外）
            abuseGuard.onBarcodeAttempt(userId, did, now, quotaTz);

            // ✅ 5) lang normalize（交易外）
            String langKey = OpenFoodFactsLang.normalizeLangKey(preferredLangTag);

            // ✅ 6) lookup（交易外：OFF + cache + redis lock）
            BarcodeLookupService.LookupResult r;
            try {
                r = barcodeLookupService.lookupOff(bcRaw, langKey);

            } catch (OffHttpException ex) {
                boolean providerLimited = (ex.getStatus() == 429 || ex.getStatus() == 403);

                if (providerLimited) {
                    return persistBarcodeFailedEnvelopeTx(
                            userId, bcNorm, requestId, now, captureTz, localDate,
                            "PROVIDER_RATE_LIMITED",
                            "openfoodfacts rate-limited/banned risk. status=" + ex.getStatus() + ", msg=" + safeMsg(ex)
                    );
                }

                return persistBarcodeFailedEnvelopeTx(
                        userId, bcNorm, requestId, now, captureTz, localDate,
                        "BARCODE_LOOKUP_FAILED",
                        "openfoodfacts http error. status=" + ex.getStatus() + ", msg=" + safeMsg(ex)
                );

            } catch (OffParseException ex) {

                return persistBarcodeFailedEnvelopeTx(
                        userId, bcNorm, requestId, now, captureTz, localDate,
                        "BARCODE_LOOKUP_FAILED",
                        "openfoodfacts parse error. code=" + ex.getCode() + ", msg=" + safeMsg(ex)
                );

            } catch (Exception ex) {

                return persistBarcodeFailedEnvelopeTx(
                        userId, bcNorm, requestId, now, captureTz, localDate,
                        "BARCODE_LOOKUP_FAILED",
                        "openfoodfacts unknown error: " + safeMsg(ex)
                );
            }

            // ✅ 防呆：service 回 null
            if (r == null) {
                return persistBarcodeFailedEnvelopeTx(
                        userId, bcNorm, requestId, now, captureTz, localDate,
                        "BARCODE_LOOKUP_FAILED",
                        "barcode lookup returned null result"
                );
            }

            // ✅ 找不到（含 negative cache）
            if (!r.found() || r.off() == null) {
                String failRaw = firstNonBlank(r.barcodeRaw(), bcRaw);
                String failNorm = firstNonBlank(r.barcodeNorm(), bcNorm);

                return persistBarcodeFailedEnvelopeTx(
                        userId, failNorm, requestId, now, captureTz, localDate,
                        "BARCODE_NOT_FOUND",
                        "openfoodfacts not found. raw=" + failRaw + ", norm=" + failNorm
                );
            }

            if (!OffEffectiveBuilder.hasUsableNutrition(r.off())) {
                String failRaw = firstNonBlank(r.barcodeRaw(), bcRaw);
                String failNorm = firstNonBlank(r.barcodeNorm(), bcNorm);

                return persistBarcodeFailedEnvelopeTx(
                        userId, failNorm, requestId, now, captureTz, localDate,
                        "BARCODE_NUTRITION_UNAVAILABLE",
                        "openfoodfacts found identity but no usable nutrition. raw=" + failRaw + ", norm=" + failNorm
                );
            }

            // ✅ 查到 → 短交易寫 DB + attach
            return persistBarcodeSuccessEnvelopeTx(
                    userId, bcRaw, bcNorm, requestId, now, captureTz, localDate, r, langKey
            );

        } catch (RuntimeException ex) {
            idem.failAndReleaseIfNeeded(
                    userId,
                    requestId,
                    "CREATE_BARCODE_FAILED",
                    safeMsg(ex),
                    true
            );
            throw ex;
        }
    }

    private FoodLogEnvelope persistBarcodeFailedEnvelopeTx(
            Long userId,
            String bc,
            String requestId,
            Instant now,
            ZoneId captureTz,
            LocalDate localDate,
            String errorCode,
            String errorMsg
    ) {
        FoodLogEnvelope out = txTemplate.execute(status ->
                buildBarcodeFailedEnvelope(
                        userId, bc, requestId, now, captureTz, localDate, errorCode, errorMsg
                )
        );
        if (out == null) {
            throw new IllegalStateException("BARCODE_TX_FAILED_EMPTY_RESULT");
        }
        return out;
    }

    private FoodLogEnvelope persistBarcodeSuccessEnvelopeTx(
            Long userId,
            String bcRaw,
            String bcNorm,
            String requestId,
            Instant now,
            ZoneId captureTz,
            LocalDate localDate,
            BarcodeLookupService.LookupResult r,
            String langKey
    ) {
        FoodLogEnvelope out = txTemplate.execute(status -> {
            OffResult off = r.off();

            // ===== 查到：建立 DRAFT + effective =====
            FoodLogEntity e = new FoodLogEntity();
            e.setUserId(userId);
            e.setMethod("BARCODE");
            e.setProvider("OPENFOODFACTS");
            e.setDegradeLevel("DG-0");

            e.setCapturedAtUtc(now);
            e.setCapturedTz(captureTz.getId());
            e.setCapturedLocalDate(localDate);
            e.setServerReceivedAtUtc(now);

            e.setTimeSource(TimeSource.SERVER_RECEIVED);
            e.setTimeSuspect(false);

            String resolvedRaw = firstNonBlank(bcRaw, r.barcodeRaw());
            String resolvedNorm = firstNonBlank(r.barcodeNorm(), bcNorm);

            // ✅ DB 一律存 normalized
            e.setBarcode(resolvedNorm);

            ObjectNode eff = JsonNodeFactory.instance.objectNode();

            String name = (off.productName() == null || off.productName().isBlank())
                    ? "Unknown product"
                    : off.productName();
            eff.put("foodName", name);

            String basis = OffEffectiveBuilder.applyPortion(eff, off);
            eff.putArray("warnings");

            boolean hasCoreNutrition = OffEffectiveBuilder.hasCoreNutrition(off);
            eff.put("confidence", hasCoreNutrition ? 0.98 : 0.92);

            if (!hasCoreNutrition) {
                eff.withArray("warnings").add(FoodLogWarning.LOW_CONFIDENCE.name());
            }

            OffCategoryResolver.Resolved resolvedCategory = OffCategoryResolver.resolve(off);

            ObjectNode aiMeta = ensureObj(eff, "aiMeta");
            aiMeta.put("barcodeRaw", resolvedRaw);
            aiMeta.put("barcodeNorm", resolvedNorm);
            aiMeta.put("offFromCache", r.fromCache());
            aiMeta.put("source", "OPENFOODFACTS");
            aiMeta.put("basis", basis);
            aiMeta.put("lang", langKey);
            aiMeta.put("hasCoreNutrition", hasCoreNutrition);
            aiMeta.put("foodCategory", resolvedCategory.category().name());
            aiMeta.put("foodSubCategory", resolvedCategory.subCategory().name());

            ObjectNode processed = postProcessor.apply(eff, e.getProvider(), e.getMethod());
            e.setEffective(processed);

            e.setStatus(FoodLogStatus.DRAFT);
            e.setLastErrorCode(null);
            e.setLastErrorMessage(null);

            repo.save(e);
            idem.attach(userId, requestId, e.getId(), now);

            return toEnvelope(e, null, requestId);
        });

        if (out == null) {
            throw new IllegalStateException("BARCODE_TX_FAILED_EMPTY_RESULT");
        }
        return out;
    }

    private static ObjectNode ensureObj(ObjectNode root, String field) {
        JsonNode n = root.get(field);
        if (n != null && n.isObject()) return (ObjectNode) n;
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        root.set(field, out);
        return out;
    }

    private FoodLogEnvelope buildBarcodeFailedEnvelope(
            Long userId,
            String bc,
            String requestId,
            Instant now,
            ZoneId captureTz,
            LocalDate localDate,
            String errorCode,
            String errorMsg
    ) {
        FoodLogEntity e = new FoodLogEntity();
        e.setUserId(userId);
        e.setMethod("BARCODE");
        e.setProvider("OPENFOODFACTS");
        e.setDegradeLevel("DG-0");
        e.setCapturedAtUtc(now);
        e.setCapturedTz(captureTz.getId());
        e.setCapturedLocalDate(localDate);
        e.setServerReceivedAtUtc(now);
        e.setTimeSource(TimeSource.SERVER_RECEIVED);
        e.setTimeSuspect(false);
        e.setBarcode(bc);
        e.setStatus(FoodLogStatus.FAILED);
        e.setEffective(null);
        e.setLastErrorCode(errorCode);
        e.setLastErrorMessage(errorMsg);

        repo.save(e);
        idem.attach(userId, requestId, e.getId(), now);
        return toEnvelope(e, null, requestId);
    }

    // ===== FoodLogService.getOne()：只在 QUEUED/RUNNING/FAILED 才回 task =====
    @Transactional(readOnly = true)
    public FoodLogEnvelope getOne(Long userId, String id, String requestId) {
        FoodLogEntity e = repo.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("FOOD_LOG_NOT_FOUND"));

        // Safety/Recitation/Harm 一律回 422（不走 envelope 的 error 欄位）
        ProviderRefuseReason reason = ProviderRefuseReason.fromErrorCodeOrNull(e.getLastErrorCode());
        if (reason != null) {
            log.warn("food_log_provider_refused foodLogId={} reason={} code={} msg={}",
                    e.getId(),
                    reason,
                    e.getLastErrorCode(),
                    e.getLastErrorMessage());

            throw new ModelRefusedException(reason, e.getLastErrorCode());
        }

        FoodLogTaskEntity t = null;
        if (e.getStatus() == FoodLogStatus.PENDING || e.getStatus() == FoodLogStatus.FAILED) {
            FoodLogTaskEntity tmp = taskRepo.findByFoodLogId(e.getId()).orElse(null);

            if (tmp != null) {
                var ts = tmp.getTaskStatus();
                if (ts == FoodLogTaskEntity.TaskStatus.QUEUED
                    || ts == FoodLogTaskEntity.TaskStatus.RUNNING
                    || ts == FoodLogTaskEntity.TaskStatus.FAILED) {
                    t = tmp;
                }
            }
        }
        return toEnvelope(e, t, requestId);
    }

    // ===== FoodLogService.toEnvelope()：
    // 1) task 只回 meaningful（QUEUED/RUNNING/FAILED）
    // 2) FAILED 時 error 一定回；retryAfterSec 優先 nextRetryAt，其次從 lastErrorMessage 解析 =====
    private FoodLogEnvelope toEnvelope(FoodLogEntity e, FoodLogTaskEntity t, String requestId) {
        Instant now = clock.instant();

        JsonNode eff = e.getEffective();

        // ✅ quota / degrade 觀點
        String tierUsed = resolveTierUsedDisplay(e);

        // ✅ 是否結果重用
        boolean fromCache = resolveResultFromCache(eff);

        FoodLogEnvelope.NutritionResult nr = null;
        List<String> warnings = null;
        String degradedReason = null;

        // ✅ P2-3：實際解析路徑
        // 優先取 effective.aiMeta.source；沒有就 fallback provider
        String resolvedBy = null;

        if (eff != null && eff.isObject()) {
            JsonNode w = eff.get("warnings");
            if (w != null && w.isArray()) {
                warnings = new java.util.ArrayList<>();
                for (JsonNode it : w) {
                    if (it == null || it.isNull()) continue;
                    String s = it.asText(null);
                    if (s != null && !s.isBlank()) warnings.add(s);
                }
                if (warnings.isEmpty()) warnings = null;
            }

            JsonNode aiMeta = eff.get("aiMeta");
            if (aiMeta != null && aiMeta.isObject()) {
                JsonNode dr = aiMeta.get("degradedReason");
                if (dr != null && !dr.isNull()) {
                    degradedReason = dr.asText(null);
                }

                JsonNode src = aiMeta.get("source");
                if (src != null && !src.isNull()) {
                    String s = src.asText(null);
                    if (s != null && !s.isBlank()) {
                        resolvedBy = s.trim();
                    }
                }
            }

            if (degradedReason == null && warnings != null) {
                if (warnings.stream().anyMatch("NO_FOOD_DETECTED"::equalsIgnoreCase)) degradedReason = "NO_FOOD";
                else if (warnings.stream().anyMatch("UNKNOWN_FOOD"::equalsIgnoreCase)) degradedReason = "UNKNOWN_FOOD";
                else if (warnings.stream().anyMatch("NO_LABEL_DETECTED"::equalsIgnoreCase)) degradedReason = "NO_LABEL";
            }
        }

        if (resolvedBy == null || resolvedBy.isBlank()) {
            resolvedBy = e.getProvider();
        }

        if (eff != null && !eff.isNull()) {
            JsonNode n = eff.get("nutrients");
            JsonNode q = eff.get("quantity");

            nr = new FoodLogEnvelope.NutritionResult(
                    textOrNull(eff, "foodName"),
                    q == null ? null : new FoodLogEnvelope.Quantity(doubleOrNull(q, "value"), textOrNull(q, "unit")),
                    n == null ? null : new FoodLogEnvelope.Nutrients(
                            round1(doubleOrNull(n, "kcal")),
                            round1(doubleOrNull(n, "protein")),
                            round1(doubleOrNull(n, "fat")),
                            round1(doubleOrNull(n, "carbs")),
                            round1(doubleOrNull(n, "fiber")),
                            round1(doubleOrNull(n, "sugar")),
                            round1(doubleOrNull(n, "sodium"))
                    ),
                    intOrNull(eff, "healthScore"),
                    doubleOrNull(eff, "confidence"),
                    warnings,
                    degradedReason,
                    aiMetaTextOrNull(eff, "foodCategory"),
                    aiMetaTextOrNull(eff, "foodSubCategory"),
                    new FoodLogEnvelope.Source(
                            e.getMethod(),
                            e.getProvider(),
                            resolvedBy
                    )
            );
        }

        // ✅ task：只在「還會自動跑」時回給前端
        FoodLogEnvelope.Task task = null;
        boolean taskMeaningful = (t != null);

        if (taskMeaningful && (e.getStatus() == FoodLogStatus.PENDING || e.getStatus() == FoodLogStatus.FAILED)) {
            int poll = computePollAfterSec(e.getStatus(), t, now);
            task = new FoodLogEnvelope.Task(t.getId(), poll);
        }

        // ✅ FAILED 才回 error
        FoodLogEnvelope.ApiError err = null;
        if (e.getStatus() == FoodLogStatus.FAILED) {

            Integer retryAfter = computeRetryAfterSecOrNull(t, now);

            if (retryAfter == null && t != null) {
                retryAfter = parseRetryAfterFromMessageOrNull(t.getLastErrorMessage());
            }

            if (retryAfter == null) {
                retryAfter = parseRetryAfterFromMessageOrNull(e.getLastErrorMessage());
            }

            if (retryAfter == null && "PROVIDER_RATE_LIMITED".equalsIgnoreCase(e.getLastErrorCode())) {
                retryAfter = 20;
            }

            String action = java.util.Optional.ofNullable(clientActionMapper.fromErrorCode(e.getLastErrorCode()))
                    .orElse(ClientAction.RETRY_LATER)
                    .name();

            err = new FoodLogEnvelope.ApiError(
                    e.getLastErrorCode(),
                    action,
                    retryAfter
            );
        }

        // ✅ 成功態提示改走 hints
        List<FoodLogEnvelope.Hint> hints = null;

        if (e.getStatus() == FoodLogStatus.DRAFT && nr != null && warnings != null) {

            boolean hasServingUnknown = warnings.stream()
                    .anyMatch("SERVING_SIZE_UNKNOWN"::equalsIgnoreCase);

            boolean isPer100GramOrMl = nr.quantity() != null
                                       && nr.quantity().unit() != null
                                       && ("GRAM".equalsIgnoreCase(nr.quantity().unit())
                                           || "ML".equalsIgnoreCase(nr.quantity().unit()))
                                       && nr.quantity().value() != null
                                       && Math.abs(nr.quantity().value() - 100.0) < 0.0001;

            if (hasServingUnknown && isPer100GramOrMl) {
                hints = List.of(
                        new FoodLogEnvelope.Hint(
                                "SERVING_SIZE_UNKNOWN",
                                ClientAction.RETAKE_PHOTO.name(),
                                "Please include net weight or serving size in the photo."
                        )
                );
            }
        }

        return new FoodLogEnvelope(
                e.getId(),
                e.getStatus().name(),
                e.getDegradeLevel(),
                tierUsed,
                fromCache,
                nr,
                task,
                err,
                hints,
                new FoodLogEnvelope.Trace(requestId)
        );
    }

    private static boolean resolveResultFromCache(JsonNode effective) {
        if (effective == null || effective.isNull() || !effective.isObject()) {
            return false;
        }
        JsonNode aiMeta = effective.get("aiMeta");
        if (aiMeta == null || !aiMeta.isObject()) {
            return false;
        }
        JsonNode resultCache = aiMeta.get("resultFromCache");
        return resultCache != null && resultCache.isBoolean() && resultCache.asBoolean();
    }

    private static void markResultFromCache(ObjectNode effective) {
        if (effective == null) return;
        JsonNode aiMetaNode = effective.get("aiMeta");
        ObjectNode aiMeta;
        if (aiMetaNode instanceof ObjectNode o) {
            aiMeta = o;
        } else {
            aiMeta = effective.putObject("aiMeta");
        }
        // ✅ 新語意：只代表「結果重用」
        aiMeta.put("resultFromCache", true);
    }

    private static Double round1(Double v) {
        if (v == null) return null;
        if (v.isNaN() || v.isInfinite()) return null; // 保守：避免序列化怪值
        // ✅ BigDecimal.valueOf 避免 new BigDecimal(double) 的精度陷阱
        return BigDecimal.valueOf(v)
                .setScale(1, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }

    @Transactional(readOnly = true)
    public OpenedImage openImage(Long userId, String foodLogId) {
        var log = repo.findByIdAndUserId(foodLogId, userId)
                .orElseThrow(() -> new IllegalArgumentException("FOOD_LOG_NOT_FOUND"));

        if (log.getStatus() == FoodLogStatus.DELETED) {
            throw new IllegalArgumentException("FOOD_LOG_DELETED");
        }
        if (log.getImageObjectKey() == null || log.getImageObjectKey().isBlank()) {
            throw new IllegalStateException("IMAGE_OBJECT_KEY_MISSING");
        }

        long size = log.getImageSizeBytes() == null ? -1L : log.getImageSizeBytes();
        return new OpenedImage(log.getImageObjectKey(), log.getImageContentType(), size);
    }

    public InputStream openImageStream(String objectKey) throws Exception {
        return storage.open(objectKey).inputStream();
    }

    @Transactional
    public FoodLogEnvelope retry(
            Long userId,
            String foodLogId,
            String clientTz,
            String deviceId,
            String requestId
    ) {
        Instant now = clock.instant();

        FoodLogEntity log = repo.findByIdForUpdate(foodLogId)
                .orElseThrow(() -> new IllegalArgumentException("FOOD_LOG_NOT_FOUND"));
        if (!userId.equals(log.getUserId())) throw new IllegalArgumentException("FOOD_LOG_NOT_FOUND");
        if (log.getStatus() == FoodLogStatus.DELETED) throw new IllegalArgumentException("FOOD_LOG_DELETED");
        if (log.getStatus() == FoodLogStatus.DRAFT || log.getStatus() == FoodLogStatus.SAVED) {
            throw new IllegalArgumentException("FOOD_LOG_NOT_RETRYABLE");
        }
        if (log.getStatus() != FoodLogStatus.FAILED) {
            throw new IllegalArgumentException("FOOD_LOG_NOT_RETRYABLE");
        }
        if ("BARCODE".equalsIgnoreCase(log.getMethod())) {
            throw new IllegalArgumentException("FOOD_LOG_NOT_RETRYABLE");
        }

        ZoneId quotaTz = parseTzOrUtc(clientTz);

        // retry 也要擋一下，不然狂點 retry 一樣會打爆
        EntitlementService.Tier tier = entitlementService.resolveTier(userId, now);
        rateLimiter.checkOrThrow(userId, tier, now);

        String did = normalizeDeviceId(userId, deviceId);
        abuseGuard.onOperationAttempt(userId, did, false, now, quotaTz);

        QuotaService.Decision d = quota.consumeOperationOrThrow(userId, tier, quotaTz, now);

        log.setDegradeLevel(d.tierUsed() == ModelTier.MODEL_TIER_HIGH ? "DG-0" : "DG-2");

        Optional<FoodLogTaskEntity> opt = taskRepo.findByFoodLogIdForUpdate(foodLogId);
        FoodLogTaskEntity task = opt.orElseGet(() -> {
            FoodLogTaskEntity t = new FoodLogTaskEntity();
            t.setFoodLogId(foodLogId);
            t.setTaskStatus(FoodLogTaskEntity.TaskStatus.QUEUED);
            t.setPollAfterSec(2);
            t.setAttempts(0);
            return t;
        });

        task.setTaskStatus(FoodLogTaskEntity.TaskStatus.QUEUED);
        task.setNextRetryAtUtc(null);
        task.setPollAfterSec(2);
        task.setAttempts(0);
        task.setLastErrorCode(null);
        task.setLastErrorMessage(null);
        taskRepo.save(task);

        resetForRetry(log);
        repo.save(log);

        return getOne(userId, foodLogId, requestId);
    }

    // =========================
    // helpers
    // =========================

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
                    "PRECHECK_FAILED",
                    safeMsg(ex),
                    true
            );
            throw ex;
        }
    }

    static void resetForRetry(FoodLogEntity log) {
        log.setStatus(FoodLogStatus.PENDING);
        log.setEffective(null);
        log.setLastErrorCode(null);
        log.setLastErrorMessage(null);
    }

    private static String aiMetaTextOrNull(JsonNode effective, String field) {
        if (effective == null || effective.isNull()) return null;

        JsonNode aiMeta = effective.get("aiMeta");
        if (aiMeta == null || !aiMeta.isObject()) return null;

        JsonNode v = aiMeta.get(field);
        if (v == null || v.isNull()) return null;

        String s = v.asText(null);
        if (s == null) return null;

        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    private static void cleanupUploadOrBlobAfterFailure(
            StorageService storage,
            Long userId,
            String requestId,
            String tempKey,
            ImageBlobService.RetainResult retained
    ) {
        // ✅ retain 後若這次是新建 blob，失敗時要刪 blobKey，避免孤兒檔
        if (retained != null && retained.newlyCreated()) {
            String blobKey = retained.objectKey();
            if (blobKey != null && !blobKey.isBlank()) {
                StorageCleanup.safeDeleteQuietly(storage, blobKey);
                return;
            }
        }

        // ✅ 尚未 retain 或是重用既有 blob：清 temp 即可（刪不到也沒關係）
        StorageCleanup.safeDeleteQuietly(storage, tempKey);
        if (tempKey == null) {
            StorageCleanup.safeDeleteTempUploadFallback(storage, userId, requestId);
        }
    }

    private void acquireInFlightOrRelease(Long userId, String requestId) {
        try {
            inFlight.acquireOrThrow(userId);
        } catch (RuntimeException ex) {
            idem.failAndReleaseIfNeeded(
                    userId,
                    requestId,
                    safeMsg(ex),
                    safeMsg(ex),
                    true
            );
            throw ex;
        }
    }

    // ===== FoodLogService 新增/保留這段 helper：從 message 抽 retryAfterSec =====
    // 放在 FoodLogService helpers 區塊即可（你原本若已有就用這版覆蓋）

    private static final Pattern P_SUGGESTED_RETRY_AFTER =
            Pattern.compile("suggestedRetryAfterSec=(\\d+)");
    private static final java.util.regex.Pattern P_RETRY_AFTER =
            Pattern.compile("retryAfterSec=(\\d+)");

    private static Integer parseRetryAfterFromMessageOrNull(String msg) {
        if (msg == null || msg.isBlank()) return null;

        Matcher m1 = P_SUGGESTED_RETRY_AFTER.matcher(msg);
        if (m1.find()) {
            try { return clampInt(Integer.parseInt(m1.group(1)), 0, 3600); }
            catch (Exception ignored) {}
        }

        Matcher m2 = P_RETRY_AFTER.matcher(msg);
        if (m2.find()) {
            try { return clampInt(Integer.parseInt(m2.group(1)), 0, 3600); }
            catch (Exception ignored) {}
        }

        return null;
    }

    private static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static Instant parseInstantOrNull(String raw) {
        try {
            if (raw == null || raw.isBlank()) return null;
            return Instant.parse(raw.trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String safeMsg(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m;
    }

    private static void validateUploadBasics(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("FILE_REQUIRED");
        if (file.getSize() > MAX_IMAGE_BYTES) throw new IllegalArgumentException("FILE_TOO_LARGE");
    }

    private static ZoneId parseTzOrUtc(String tz) {
        try { return (tz == null || tz.isBlank()) ? ZoneOffset.UTC : ZoneId.of(tz); }
        catch (Exception ignored) { return ZoneOffset.UTC; }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private static Integer intOrNull(JsonNode node, String field) {
        if (node == null || node.isNull()) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        if (v.isIntegralNumber()) return v.asInt();
        if (v.isTextual()) {
            String s = v.asText(null);
            if (s == null || s.isBlank()) return null;
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    private static Double doubleOrNull(JsonNode node, String field) {
        if (node == null || node.isNull()) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        if (v.isNumber()) {
            double d = v.asDouble();
            return Double.isFinite(d) ? d : null;
        }
        if (v.isTextual()) {
            String s = v.asText(null);
            if (s == null || s.isBlank()) return null;
            try {
                double d = Double.parseDouble(s.trim());
                return Double.isFinite(d) ? d : null;
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    private static Integer computeRetryAfterSecOrNull(FoodLogTaskEntity t, Instant now) {
        if (t == null || t.getNextRetryAtUtc() == null) return null;
        long sec = Duration.between(now, t.getNextRetryAtUtc()).getSeconds();
        if (sec < 0) sec = 0;
        if (sec > Integer.MAX_VALUE) sec = Integer.MAX_VALUE;
        return (int) sec;
    }

    static String resolveTierUsedDisplay(FoodLogEntity e) {
        if (e == null) return null;

        String method = e.getMethod();
        if (method != null && "BARCODE".equalsIgnoreCase(method.trim())) {
            return "BARCODE";
        }

        ModelTier mt = FoodLogTierResolver.resolve(e.getDegradeLevel());
        return (mt == null) ? null : mt.name();
    }


    private static int computePollAfterSec(FoodLogStatus status, FoodLogTaskEntity t, Instant now) {
        if (t == null) return 2;

        Integer pollAfterObj = t.getPollAfterSec();
        int base = Math.max(1, pollAfterObj == null ? 2 : pollAfterObj);

        FoodLogTaskEntity.TaskStatus ts = t.getTaskStatus();

        // ✅ FAILED：用 nextRetryAtUtc 算剩餘秒數
        if (status == FoodLogStatus.FAILED) {
            Integer retryAfter = computeRetryAfterSecOrNull(t, now);
            int v = (retryAfter == null) ? 5 : retryAfter;
            return clamp(v, 2, 60);
        }

        // ✅ PENDING：針對 QUEUED 做「排隊退避」
        if (status == FoodLogStatus.PENDING) {

            // 1) QUEUED：依排隊時間退避（>30s→5、>60s→8、>120s→10）
            if (ts == FoodLogTaskEntity.TaskStatus.QUEUED) {
                Instant created = t.getCreatedAtUtc();
                if (created == null) {
                    // createdAt 不存在：保守回 base（通常 2）
                    return clamp(base, 2, 10);
                }

                long queuedSec = Duration.between(created, now).getSeconds();
                if (queuedSec < 0) queuedSec = 0;

                int v;
                if (queuedSec > 120) v = 10;
                else if (queuedSec > 60) v = 8;
                else if (queuedSec > 30) v = 5;
                else v = base; // 通常 2

                return clamp(v, 2, 10);
            }

            // 2) RUNNING：維持原本（Demo 體感好）
            if (ts == FoodLogTaskEntity.TaskStatus.RUNNING) {
                return clamp(base, 2, 10);
            }

            // 3) 其他狀態（理論上少見）：輕度退避
            Integer attemptsObj = t.getAttempts();
            int attempts = Math.max(0, attemptsObj == null ? 0 : attemptsObj);
            int v = base + Math.min(attempts, 6);
            return clamp(v, 2, 10);
        }

        // ✅ 其他狀態（理論上不會走到）：保守
        return clamp(base, 2, 10);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
