package com.calai.backend.foodlog.service;

import com.calai.backend.entitlement.service.EntitlementService;
import com.calai.backend.foodlog.barcode.*;
import com.calai.backend.foodlog.config.FoodLogTierResolver;
import com.calai.backend.foodlog.dto.FoodLogEnvelope;
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
import com.calai.backend.foodlog.time.CapturedTimeResolver;
import com.calai.backend.foodlog.time.ExifTimeExtractor;
import com.calai.backend.foodlog.web.ModelRefusedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
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
import org.springframework.beans.factory.annotation.Value;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.calai.backend.foodlog.task.EffectivePostProcessor;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.calai.backend.foodlog.barcode.mapper.OpenFoodFactsMapper.OffResult;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

@RequiredArgsConstructor
@Service
public class FoodLogService {

    @Value("${app.foodlog.provider:STUB}")
    private String defaultProviderRaw;

    private String defaultProvider() {
        if (defaultProviderRaw == null) return "STUB";
        String v = defaultProviderRaw.trim();
        if (v.isEmpty()) return "STUB";
        return v.toUpperCase(Locale.ROOT);
    }

    private static final String LABEL_PROVIDER = "GEMINI";

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
    private final ObjectMapper om;
    private final QuotaService quota;
    private final IdempotencyService idem;
    private final ImageBlobService blobService;
    private final UserInFlightLimiter inFlight;
    private final UserRateLimiter rateLimiter;
    private final EffectivePostProcessor postProcessor;
    private final ClientActionMapper clientActionMapper;
    private final CapturedTimeResolver timeResolver = new CapturedTimeResolver();
    public record OpenedImage(String objectKey, String contentType, long sizeBytes) {}
    private final AbuseGuardService abuseGuard;
    private final EntitlementService entitlementService;
    private final BarcodeLookupService barcodeLookupService;
    private final TransactionTemplate txTemplate; // ✅ 短交易用

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

        ZoneId tz = parseTzOrUtc(clientTz);
        Instant serverNow = Instant.now();
        validateUploadBasics(file);

        String existingLogId = idem.reserveOrGetExisting(userId, requestId, serverNow);
        if (existingLogId != null) return getOne(userId, existingLogId, requestId);

        EntitlementService.Tier tier = entitlementService.resolveTier(userId, serverNow);
        rateLimiter.checkOrThrow(userId, tier, serverNow);

        boolean acquired = false;
        String tempKey = null;
        ImageBlobService.RetainResult retained = null; // ✅ retain 後補償用

        try {
            inFlight.acquireOrThrow(userId);
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
                abuseGuard.onOperationAttempt(userId, did, cacheHit, serverNow, tz);

                // ✅ ALBUM：固定用「上傳時間」當作 capturedAtUtc
                LocalDate todayLocal = ZonedDateTime.ofInstant(serverNow, tz).toLocalDate();

                FoodLogEntity e = new FoodLogEntity();
                e.setUserId(userId);
                e.setMethod("ALBUM");
                e.setDegradeLevel("DG-0");

                e.setCapturedAtUtc(serverNow);
                e.setCapturedTz(tz.getId());
                e.setCapturedLocalDate(todayLocal);
                e.setServerReceivedAtUtc(serverNow);

                e.setTimeSource(TimeSource.SERVER_RECEIVED);
                e.setTimeSuspect(false);

                if (hit.isPresent() && hit.get().getEffective() != null && hit.get().getEffective().isObject()) {
                    e.setProvider(hit.get().getProvider());

                    ObjectNode copied = hit.get().getEffective().deepCopy();
                    ObjectNode processed = postProcessor.apply(copied, e.getProvider(), e.getMethod());
                    markFromCache(processed);
                    e.setEffective(processed);
                    e.setStatus(FoodLogStatus.DRAFT);
                } else {
                    QuotaService.Decision d = quota.consumeOperationOrThrow(userId, tz, serverNow);
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

        ZoneId tz = parseTzOrUtc(clientTz);
        Instant serverNow = Instant.now();
        validateUploadBasics(file);

        String existingLogId = idem.reserveOrGetExisting(userId, requestId, serverNow);
        if (existingLogId != null) return getOne(userId, existingLogId, requestId);

        EntitlementService.Tier tier = entitlementService.resolveTier(userId, serverNow);
        rateLimiter.checkOrThrow(userId, tier, serverNow);

        boolean acquired = false;
        String tempKey = null; // ✅ 讓所有 catch 都能刪到正確 key（含副檔名）
        ImageBlobService.RetainResult retained = null; // ✅ retain 後補償用

        try {
            inFlight.acquireOrThrow(userId);
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
                Optional<Instant> exifUtc = ExifTimeExtractor.tryReadCapturedAtUtc(storage, tempKey, tz);

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
                abuseGuard.onOperationAttempt(userId, did, cacheHit, serverNow, tz);

                // 6) 建 log（capturedLocalDate 用 resolved capturedAtUtc + client tz）
                LocalDate localDate = ZonedDateTime.ofInstant(r.capturedAtUtc(), tz).toLocalDate();

                FoodLogEntity e = new FoodLogEntity();
                e.setUserId(userId);
                e.setMethod("PHOTO");
                e.setDegradeLevel("DG-0");

                e.setCapturedAtUtc(r.capturedAtUtc());
                e.setCapturedTz(tz.getId());
                e.setCapturedLocalDate(localDate);
                e.setServerReceivedAtUtc(serverNow);

                e.setTimeSource(TimeSource.valueOf(r.source().name()));
                e.setTimeSuspect(r.suspect());

                if (hit.isPresent() && hit.get().getEffective() != null && hit.get().getEffective().isObject()) {
                    e.setProvider(hit.get().getProvider());

                    ObjectNode copied = hit.get().getEffective().deepCopy();

                    // ✅ 讓 “去重命中” 也套用同一套後處理（healthScore/meta/non-food）
                    ObjectNode processed = postProcessor.apply(copied, e.getProvider(), e.getMethod());
                    markFromCache(processed);
                    e.setEffective(processed);
                    e.setStatus(FoodLogStatus.DRAFT);
                } else {
                    QuotaService.Decision d = quota.consumeOperationOrThrow(userId, tz, serverNow);
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

        ZoneId tz = parseTzOrUtc(clientTz);
        Instant serverNow = Instant.now();
        validateUploadBasics(file);

        String existingLogId = idem.reserveOrGetExisting(userId, requestId, serverNow);
        if (existingLogId != null) return getOne(userId, existingLogId, requestId);

        // ✅ 仍要限流，避免被打爆（雖然是 AI 才更需要，但 label 也會走 AI）
        EntitlementService.Tier tier = entitlementService.resolveTier(userId, serverNow);
        rateLimiter.checkOrThrow(userId, tier, serverNow);

        boolean acquired = false;
        String tempKey = null;
        ImageBlobService.RetainResult retained = null; // ✅ retain 後補償用

        try {
            inFlight.acquireOrThrow(userId);
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
                Optional<Instant> exifUtc = ExifTimeExtractor.tryReadCapturedAtUtc(storage, tempKey, tz);
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
                abuseGuard.onOperationAttempt(userId, did, cacheHit, serverNow, tz);

                LocalDate localDate = ZonedDateTime.ofInstant(r.capturedAtUtc(), tz).toLocalDate();

                FoodLogEntity e = new FoodLogEntity();
                e.setUserId(userId);
                e.setMethod("LABEL");
                e.setDegradeLevel("DG-0");

                e.setCapturedAtUtc(r.capturedAtUtc());
                e.setCapturedTz(tz.getId());
                e.setCapturedLocalDate(localDate);
                e.setServerReceivedAtUtc(serverNow);

                e.setTimeSource(TimeSource.valueOf(r.source().name()));
                e.setTimeSuspect(r.suspect());

                if (hit.isPresent() && hit.get().getEffective() != null && hit.get().getEffective().isObject()) {
                    // ✅ 命中：直接複製 effective，不扣 quota、不建 task
                    e.setProvider(hit.get().getProvider());

                    ObjectNode copied = hit.get().getEffective().deepCopy();
                    ObjectNode processed = postProcessor.apply(copied, e.getProvider(), e.getMethod());
                    markFromCache(processed);
                    e.setEffective(processed);
                    e.setStatus(FoodLogStatus.DRAFT);
                } else {
                    // ✅ 未命中：扣 AI quota，provider 固定 GEMINI
                    QuotaService.Decision d = quota.consumeOperationOrThrow(userId, tz, serverNow);
                    // 先用 degradeLevel 反映 tier，方便你立刻觀測（Step 2 才會真正選模型）
                    e.setDegradeLevel(d.tierUsed() == ModelTier.MODEL_TIER_HIGH ? "DG-0" : "DG-2");
                    e.setProvider(LABEL_PROVIDER);
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
        Instant now = Instant.now();

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
            ZoneId tz = parseTzOrUtc(clientTz);
            LocalDate localDate = ZonedDateTime.ofInstant(now, tz).toLocalDate();

            // ✅ 2) deviceId normalize
            String did = normalizeDeviceId(userId, deviceId);

            // ✅ 3) rate limit（交易外）
            EntitlementService.Tier tier = entitlementService.resolveTier(userId, now);
            rateLimiter.checkOrThrow(userId, tier, now);

            // ✅ 4) barcode cheap op（交易外）
            abuseGuard.onBarcodeAttempt(userId, did, now, tz);

            // ✅ 5) lang normalize（交易外）
            String langKey = OpenFoodFactsLang.normalizeLangKey(preferredLangTag);

            // ✅ 6) lookup（交易外：OFF + cache + redis lock）
            BarcodeLookupService.LookupResult r;
            try {
                r = barcodeLookupService.lookupOff(bcRaw, langKey);

            } catch (OffHttpException ex) {
                // ✅ OffHttpException 目前只有 status / bodySnippet，沒有 code getter
                // ✅ 不要拿 safeMsg(ex)（message）去比對 "PROVIDER_RATE_LIMITED"（error code）
                boolean providerLimited = (ex.getStatus() == 429 || ex.getStatus() == 403);

                if (providerLimited) {
                    return persistBarcodeFailedEnvelopeTx(
                            userId, bcNorm, requestId, now, tz, localDate,
                            "PROVIDER_RATE_LIMITED",
                            "openfoodfacts rate-limited/banned risk. status=" + ex.getStatus() + ", msg=" + safeMsg(ex)
                    );
                }

                return persistBarcodeFailedEnvelopeTx(
                        userId, bcNorm, requestId, now, tz, localDate,
                        "BARCODE_LOOKUP_FAILED",
                        "openfoodfacts http error. status=" + ex.getStatus() + ", msg=" + safeMsg(ex)
                );

            } catch (OffParseException ex) {

                return persistBarcodeFailedEnvelopeTx(
                        userId, bcNorm, requestId, now, tz, localDate,
                        "BARCODE_LOOKUP_FAILED",
                        "openfoodfacts parse error. code=" + ex.getCode() + ", msg=" + safeMsg(ex)
                );

            } catch (Exception ex) {

                return persistBarcodeFailedEnvelopeTx(
                        userId, bcNorm, requestId, now, tz, localDate,
                        "BARCODE_LOOKUP_FAILED",
                        "openfoodfacts unknown error: " + safeMsg(ex)
                );
            }

            // ✅ 防呆：service 回 null
            if (r == null) {
                return persistBarcodeFailedEnvelopeTx(
                        userId, bcNorm, requestId, now, tz, localDate,
                        "BARCODE_LOOKUP_FAILED",
                        "barcode lookup returned null result"
                );
            }

            // ✅ 找不到（含 negative cache）
            if (!r.found() || r.off() == null) {
                String failRaw = firstNonBlank(r.barcodeRaw(), bcRaw);
                String failNorm = firstNonBlank(r.barcodeNorm(), bcNorm);

                return persistBarcodeFailedEnvelopeTx(
                        userId, failNorm, requestId, now, tz, localDate,
                        "BARCODE_NOT_FOUND",
                        "openfoodfacts not found. raw=" + failRaw + ", norm=" + failNorm
                );
            }

            // ✅ 查到 → 短交易寫 DB + attach
            return persistBarcodeSuccessEnvelopeTx(
                    userId, bcRaw, bcNorm, requestId, now, tz, localDate, r, langKey
            );

        } catch (RuntimeException ex) {
            // ✅ 關鍵：reserve 成功後若中途炸掉（rate limit/cooldown/DB error），要釋放 requestId
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
            ZoneId tz,
            LocalDate localDate,
            String errorCode,
            String errorMsg
    ) {
        FoodLogEnvelope out = txTemplate.execute(status ->
                buildBarcodeFailedEnvelope(
                        userId, bc, requestId, now, tz, localDate, errorCode, errorMsg
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
            ZoneId tz,
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
            e.setCapturedTz(tz.getId());
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

            String basis = applyBarcodePortion(eff, off);

            ObjectNode nObj = (eff.get("nutrients") != null && eff.get("nutrients").isObject())
                    ? (ObjectNode) eff.get("nutrients")
                    : null;

            boolean hasAnyMacro = nObj != null && (
                    (nObj.get("protein") != null && !nObj.get("protein").isNull())
                    || (nObj.get("fat") != null && !nObj.get("fat").isNull())
                    || (nObj.get("carbs") != null && !nObj.get("carbs").isNull())
            );

            eff.put("confidence", hasAnyMacro ? 0.98 : 0.92);

            ObjectNode aiMeta = ensureObj(eff, "aiMeta");
            aiMeta.put("barcodeRaw", resolvedRaw);
            aiMeta.put("barcodeNorm", resolvedNorm);
            aiMeta.put("fromCache", r.fromCache());
            aiMeta.put("source", "OPENFOODFACTS");
            aiMeta.put("basis", basis);
            aiMeta.put("lang", langKey);

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

    private String applyBarcodePortion(ObjectNode eff, OffResult off) {

        // ===== 1) 優先：整包（需要 packageSize + per100）=====
        boolean hasPkg = off.packageSizeValue() != null
                         && off.packageSizeValue() > 0
                         && off.packageSizeUnit() != null
                         && !off.packageSizeUnit().isBlank();

        boolean hasAnyPer100 = (off.kcalPer100g() != null
                                || off.proteinPer100g() != null
                                || off.fatPer100g() != null
                                || off.carbsPer100g() != null
                                || off.fiberPer100g() != null
                                || off.sugarPer100g() != null
                                || off.sodiumMgPer100g() != null);

        if (hasPkg && hasAnyPer100) {

            String unitRaw = off.packageSizeUnit().trim().toLowerCase(Locale.ROOT); // "g" or "ml"
            String qtyUnit = unitRaw.contains("ml") ? "ML" : "GRAM";
            double pkg = off.packageSizeValue();

            ObjectNode qty = eff.putObject("quantity");
            qty.put("value", pkg);
            qty.put("unit", qtyUnit);

            double factor = pkg / 100.0;

            ObjectNode n = eff.putObject("nutrients");
            putNumOrNull(n, "kcal", mul(off.kcalPer100g(), factor));
            putNumOrNull(n, "protein", mul(off.proteinPer100g(), factor));
            putNumOrNull(n, "fat", mul(off.fatPer100g(), factor));
            putNumOrNull(n, "carbs", mul(off.carbsPer100g(), factor));
            putNumOrNull(n, "fiber", mul(off.fiberPer100g(), factor));
            putNumOrNull(n, "sugar", mul(off.sugarPer100g(), factor));
            putNumOrNull(n, "sodium", mul(off.sodiumMgPer100g(), factor)); // mg 也照乘

            return "WHOLE_PACKAGE";
        }

        // ===== 2) 次選：每份（需要 *_serving 任一存在）=====
        boolean hasAnyServing = (off.kcalPerServing() != null
                                 || off.proteinPerServing() != null
                                 || off.fatPerServing() != null
                                 || off.carbsPerServing() != null
                                 || off.fiberPerServing() != null
                                 || off.sugarPerServing() != null
                                 || off.sodiumMgPerServing() != null);

        if (hasAnyServing) {
            ObjectNode qty = eff.putObject("quantity");
            qty.put("value", 1.0);
            qty.put("unit", "SERVING");

            ObjectNode n = eff.putObject("nutrients");
            putNumOrNull(n, "kcal", off.kcalPerServing());
            putNumOrNull(n, "protein", off.proteinPerServing());
            putNumOrNull(n, "fat", off.fatPerServing());
            putNumOrNull(n, "carbs", off.carbsPerServing());
            putNumOrNull(n, "fiber", off.fiberPerServing());
            putNumOrNull(n, "sugar", off.sugarPerServing());
            putNumOrNull(n, "sodium", off.sodiumMgPerServing());

            return "PER_SERVING";
        }

        // ===== 3) 最後：退回 per100g（你原本）=====
        ObjectNode qty = eff.putObject("quantity");
        qty.put("value", 100.0);

        String per100Unit = "GRAM";
        if (off.packageSizeUnit() != null && off.packageSizeUnit().equalsIgnoreCase("ml")) {
            per100Unit = "ML";
        }
        qty.put("unit", per100Unit);

        ObjectNode n = eff.putObject("nutrients");
        putNumOrNull(n, "kcal", off.kcalPer100g());
        putNumOrNull(n, "protein", off.proteinPer100g());
        putNumOrNull(n, "fat", off.fatPer100g());
        putNumOrNull(n, "carbs", off.carbsPer100g());
        putNumOrNull(n, "fiber", off.fiberPer100g());
        putNumOrNull(n, "sugar", off.sugarPer100g());
        putNumOrNull(n, "sodium", off.sodiumMgPer100g());

        return "PER_100";
    }

    private static ObjectNode ensureObj(ObjectNode root, String field) {
        JsonNode n = root.get(field);
        if (n != null && n.isObject()) return (ObjectNode) n;
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        root.set(field, out);
        return out;
    }

    private static Double mul(Double v, double factor) {
        return (v == null) ? null : v * factor;
    }

    private FoodLogEnvelope buildBarcodeFailedEnvelope(
            Long userId,
            String bc,
            String requestId,
            Instant now,
            ZoneId tz,
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
        e.setCapturedTz(tz.getId());
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

    private static void putNumOrNull(ObjectNode obj, String field, Double v) {
        if (v == null || !Double.isFinite(v)) {
            obj.putNull(field);
        } else {
            obj.put(field, v);
        }
    }


    // ===== FoodLogService.getOne()：只在 QUEUED/RUNNING/FAILED 才回 task =====
    @Transactional(readOnly = true)
    public FoodLogEnvelope getOne(Long userId, String id, String requestId) {
        FoodLogEntity e = repo.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("FOOD_LOG_NOT_FOUND"));

        // Safety/Recitation/Harm 一律回 422（不走 envelope 的 error 欄位）
        ProviderRefuseReason reason = ProviderRefuseReason.fromErrorCodeOrNull(e.getLastErrorCode());

        if (reason != null) {throw new ModelRefusedException(reason, e.getLastErrorMessage());}

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
        Instant now = Instant.now();

        JsonNode eff = e.getEffective();
        // ✅ v1.2：回應帶 tierUsed / fromCache
        String tierUsed = resolveTierUsedDisplay(e);
        boolean fromCache = false;
        FoodLogEnvelope.NutritionResult nr = null;

        java.util.List<String> warnings = null;
        String degradedReason = null;

        if (eff != null && eff.isObject()) {

            // warnings
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

            // degradedReason
            JsonNode aiMeta = eff.get("aiMeta");
            if (aiMeta != null && aiMeta.isObject()) {
                JsonNode dr = aiMeta.get("degradedReason");
                if (dr != null && !dr.isNull()) degradedReason = dr.asText(null);
                JsonNode fc = aiMeta.get("fromCache");
                if (fc != null && fc.isBoolean()) fromCache = fc.asBoolean();
            }
            if (degradedReason == null && warnings != null) {
                if (warnings.stream().anyMatch("NO_FOOD_DETECTED"::equalsIgnoreCase)) degradedReason = "NO_FOOD";
                else if (warnings.stream().anyMatch("UNKNOWN_FOOD"::equalsIgnoreCase)) degradedReason = "UNKNOWN_FOOD";
                else if (warnings.stream().anyMatch("NO_LABEL_DETECTED"::equalsIgnoreCase)) degradedReason = "NO_LABEL";
            }
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
                    new FoodLogEnvelope.Source(e.getMethod(), e.getProvider())
            );
        }

        // ✅ task：只在「還會自動跑」時回給前端
        FoodLogEnvelope.Task task = null;
        boolean taskMeaningful = (t != null);

        if (taskMeaningful && (e.getStatus() == FoodLogStatus.PENDING || e.getStatus() == FoodLogStatus.FAILED)) {
            int poll = computePollAfterSec(e.getStatus(), t, now);
            task = new FoodLogEnvelope.Task(t.getId(), poll);
        }

        // ✅ error：FAILED 一定回；retryAfterSec 優先 nextRetryAt，其次解析 message
        FoodLogEnvelope.ApiError err = null;
        if (e.getStatus() == FoodLogStatus.FAILED) {

            Integer retryAfter = computeRetryAfterSecOrNull(t, now); // 1) nextRetryAtUtc（若 t==null -> null）

            if (retryAfter == null && t != null) {
                // 2) task.lastErrorMessage（若你將來有 markFailed 的路徑）
                retryAfter = parseRetryAfterFromMessageOrNull(t.getLastErrorMessage());
            }

            if (retryAfter == null) {
                // 3) log.lastErrorMessage（你 429 CANCELLED 會塞 suggestedRetryAfterSec 在這裡）
                retryAfter = parseRetryAfterFromMessageOrNull(e.getLastErrorMessage());
            }

            // 4) 429 fallback（UI 至少有提示）
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

        // ✅ DRAFT 提示：若是 per100(gram/ml) 且缺淨重，提示 UI「把淨重一起拍進來」
        if (err == null && e.getStatus() == FoodLogStatus.DRAFT && nr != null && warnings != null) {

            boolean hasServingUnknown = warnings.stream()
                    .anyMatch("SERVING_SIZE_UNKNOWN"::equalsIgnoreCase);

            boolean isPer100GramOrMl = nr.quantity() != null
                                       && nr.quantity().unit() != null
                                       && ( "GRAM".equalsIgnoreCase(nr.quantity().unit()) || "ML".equalsIgnoreCase(nr.quantity().unit()) )
                                       && nr.quantity().value() != null
                                       && Math.abs(nr.quantity().value() - 100.0) < 0.0001;

            if (hasServingUnknown && isPer100GramOrMl) {
                // ✅ schema 不變：沿用 error 欄位當「提示型 action」
                // UI 看到這個就顯示「請把淨重/內容量一起拍進來」
                err = new FoodLogEnvelope.ApiError(
                        "SERVING_SIZE_UNKNOWN",
                        ClientAction.RETAKE_PHOTO.name(),
                        null
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
                new FoodLogEnvelope.Trace(requestId)
        );
    }

    private static void markFromCache(ObjectNode effective) {
        if (effective == null) return;
        JsonNode aiMetaNode = effective.get("aiMeta");
        ObjectNode aiMeta;
        if (aiMetaNode instanceof ObjectNode o) aiMeta = o;
        else aiMeta = effective.putObject("aiMeta");
        aiMeta.put("fromCache", true);
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
    public FoodLogEnvelope retry(Long userId, String foodLogId, String deviceId, String requestId) {
        Instant now = Instant.now();

        // ✅ retry 也要擋一下，不然狂點 retry 一樣會打爆
        EntitlementService.Tier tier = entitlementService.resolveTier(userId, now);
        rateLimiter.checkOrThrow(userId, tier, now);

        FoodLogEntity log = repo.findByIdForUpdate(foodLogId);
        if (log == null) throw new IllegalArgumentException("FOOD_LOG_NOT_FOUND");
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

        // ✅ 用原本 capturedTz（或你也可以改用 clientTz，但 retry 通常沿用原 log）
        ZoneId tz = parseTzOrUtc(log.getCapturedTz());

        // ✅ retry 也算操作，給 abuseGuard（cacheHit=false）
        // ✅ deviceId 統一 normalize
        String did = normalizeDeviceId(userId, deviceId);
        abuseGuard.onOperationAttempt(userId, did, false, now, tz);

        // ✅ Step 1：retry 也算一次 Operation（會再打模型）
        QuotaService.Decision d = quota.consumeOperationOrThrow(userId, tz, now);

        // ✅ 先用 degradeLevel 反映 tier，方便你觀測（Step 2 才會真正選模型）
        log.setDegradeLevel(d.tierUsed() == ModelTier.MODEL_TIER_HIGH ? "DG-0" : "DG-2");

        // ✅ 重排 task
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

        // ✅ reset log
        log.setStatus(FoodLogStatus.PENDING);
        log.setLastErrorCode(null);
        log.setLastErrorMessage(null);
        repo.save(log);

        return getOne(userId, foodLogId, requestId);
    }

    // =========================
    // helpers
    // =========================

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
