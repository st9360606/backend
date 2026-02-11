package com.calai.backend.foodlog.service;

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
import com.calai.backend.foodlog.quota.service.AiQuotaEngine;
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
import org.springframework.web.multipart.MultipartFile;
import com.calai.backend.foodlog.service.cleanup.StorageCleanup;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.time.*;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import java.util.Locale;
import com.calai.backend.foodlog.task.EffectivePostProcessor;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.calai.backend.foodlog.barcode.OpenFoodFactsClient;
import com.calai.backend.foodlog.barcode.mapper.OpenFoodFactsMapper;
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

    private final FoodLogRepository repo;
    private final FoodLogTaskRepository taskRepo;
    private final StorageService storage;
    private final ObjectMapper om; // 目前留著，之後 provider 會用到
    private final AiQuotaEngine aiQuota;
    private final IdempotencyService idem;
    private final ImageBlobService blobService;
    private final UserInFlightLimiter inFlight;
    private final UserRateLimiter rateLimiter;
    private final EffectivePostProcessor postProcessor;
    private final ClientActionMapper clientActionMapper;
    // MVP：先 new（後續要 DI 也可以）
    private final CapturedTimeResolver timeResolver = new CapturedTimeResolver();

    public record OpenedImage(String objectKey, String contentType, long sizeBytes) {}
    private final OpenFoodFactsClient offClient;
    private final AbuseGuardService abuseGuard;

    // =========================
    // S4-05：ALBUM
    // =========================
    @Transactional
    public FoodLogEnvelope createAlbum(Long userId, String clientTz, String deviceId, MultipartFile file, String requestId) throws Exception{
        ZoneId tz = parseTzOrUtc(clientTz);
        Instant serverNow = Instant.now();
        validateUploadBasics(file);

        String existingLogId = idem.reserveOrGetExisting(userId, requestId, serverNow);
        if (existingLogId != null) return getOne(userId, existingLogId, requestId);

        rateLimiter.checkOrThrow(userId, serverNow);

        boolean acquired = false;
        String tempKey = null; // ✅ 讓所有 catch 都能刪到正確 key（含副檔名）

        try {
            inFlight.acquireOrThrow(userId);
            acquired = true;

            ImageSniffer.Detection det;
            StorageService.SaveResult saved;

            // 3) 上傳 → tempKey
            try (InputStream raw = file.getInputStream();
                 PushbackInputStream in = new PushbackInputStream(raw, 16)) {

                det = ImageSniffer.detect(in);
                if (det == null) throw new IllegalArgumentException("UNSUPPORTED_IMAGE_FORMAT");

                // ✅ detect 成功就先決定 tempKey（帶 ext）
                tempKey = "user-" + userId + "/blobs/tmp/" + requestId + "/upload" + det.ext();

                saved = storage.save(tempKey, in, det.contentType());

            } catch (Exception ex) {
                // ✅ 上傳失敗也嘗試刪 temp（即使不存在也不會炸）
                StorageCleanup.safeDeleteQuietly(storage, tempKey);
                if (tempKey == null) StorageCleanup.safeDeleteTempUploadFallback(storage, userId, requestId);

                idem.failAndReleaseIfNeeded(userId, requestId, "UPLOAD_FAILED", safeMsg(ex), true);
                throw ex;
            }

            try {
                // 4) 去重命中（不扣 quota）
                var hit = repo.findFirstByUserIdAndImageSha256AndStatusInOrderByCreatedAtUtcDesc(
                        userId,
                        saved.sha256(),
                        List.of(FoodLogStatus.DRAFT, FoodLogStatus.SAVED)
                );

                // ✅ NEW：判斷 cacheHit（必須 effective 是 object 才算真正命中）
                boolean cacheHit = hit.isPresent()
                                   && hit.get().getEffective() != null
                                   && hit.get().getEffective().isObject();

                // ✅ NEW：Anti-abuse（會在觸發時直接丟 429 CooldownActiveException）
                abuseGuard.onOperationAttempt(userId, deviceId, cacheHit, serverNow, tz);

                // 5) 建 log
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

                    // ✅ 讓 “去重命中” 也套用同一套後處理（healthScore/meta/non-food）
                    ObjectNode processed = postProcessor.apply(copied, e.getProvider(), e.getMethod());

                    e.setEffective(processed);
                    e.setStatus(FoodLogStatus.DRAFT);
                } else {
                    AiQuotaEngine.Decision d = aiQuota.consumeOperationOrThrow(userId, tz, serverNow);
                    // 先用 degradeLevel 反映 tier，方便你立刻觀測（Step 2 才會真正選模型）
                    e.setDegradeLevel(d.tierUsed() == ModelTier.MODEL_TIER_HIGH ? "DG-0" : "DG-2");
                    e.setProvider(defaultProvider());
                    e.setEffective(null);
                    e.setStatus(FoodLogStatus.PENDING);
                }

                repo.save(e);
                idem.attach(userId, requestId, e.getId(), serverNow);

                // 6) temp -> blobKey + refCount
                var retained = blobService.retainFromTemp(
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
                // ✅ 以前你這裡 hard-code ".../upload" 現在改成刪 tempKey
                StorageCleanup.safeDeleteQuietly(storage, tempKey);

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
    @Transactional
    public FoodLogEnvelope createPhoto(Long userId, String clientTz, String deviceId, String deviceCapturedAtUtc, MultipartFile file, String requestId) throws Exception {

        ZoneId tz = parseTzOrUtc(clientTz);
        Instant serverNow = Instant.now();
        validateUploadBasics(file);

        String existingLogId = idem.reserveOrGetExisting(userId, requestId, serverNow);
        if (existingLogId != null) return getOne(userId, existingLogId, requestId);

        rateLimiter.checkOrThrow(userId, serverNow);

        boolean acquired = false;
        String tempKey = null; // ✅ 讓所有 catch 都能刪到正確 key（含副檔名）

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
                var hit = repo.findFirstByUserIdAndImageSha256AndStatusInOrderByCreatedAtUtcDesc(
                        userId,
                        saved.sha256(),
                        List.of(FoodLogStatus.DRAFT, FoodLogStatus.SAVED)
                );

                // ✅ NEW：判斷 cacheHit（必須 effective 是 object 才算真正命中）
                boolean cacheHit = hit.isPresent()
                                   && hit.get().getEffective() != null
                                   && hit.get().getEffective().isObject();

                // ✅ NEW：Anti-abuse（會在觸發時直接丟 429 CooldownActiveException）
                abuseGuard.onOperationAttempt(userId, deviceId, cacheHit, serverNow, tz);

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

                    e.setEffective(processed);
                    e.setStatus(FoodLogStatus.DRAFT);
                } else {
                    AiQuotaEngine.Decision d = aiQuota.consumeOperationOrThrow(userId, tz, serverNow);
                    // 先用 degradeLevel 反映 tier，方便你立刻觀測（Step 2 才會真正選模型）
                    e.setDegradeLevel(d.tierUsed() == ModelTier.MODEL_TIER_HIGH ? "DG-0" : "DG-2");
                    e.setProvider(defaultProvider());
                    e.setEffective(null);
                    e.setStatus(FoodLogStatus.PENDING);
                }

                repo.save(e);
                idem.attach(userId, requestId, e.getId(), serverNow);

                // 7) temp -> blob + refCount
                var retained = blobService.retainFromTemp(
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
                // ✅ 以前你這裡 hard-code ".../upload" 現在改成刪 tempKey
                StorageCleanup.safeDeleteQuietly(storage, tempKey);
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
    @Transactional
    public FoodLogEnvelope createLabel(Long userId, String clientTz, String deviceId, String deviceCapturedAtUtc, MultipartFile file, String requestId) throws Exception {

        ZoneId tz = parseTzOrUtc(clientTz);
        Instant serverNow = Instant.now();
        validateUploadBasics(file);

        String existingLogId = idem.reserveOrGetExisting(userId, requestId, serverNow);
        if (existingLogId != null) return getOne(userId, existingLogId, requestId);

        // ✅ 仍要限流，避免被打爆（雖然是 AI 才更需要，但 label 也會走 AI）
        rateLimiter.checkOrThrow(userId, serverNow);

        boolean acquired = false;
        String tempKey = null;

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
                var hit = repo.findFirstByUserIdAndImageSha256AndStatusInOrderByCreatedAtUtcDesc(
                        userId,
                        saved.sha256(),
                        List.of(FoodLogStatus.DRAFT, FoodLogStatus.SAVED)
                );

                // ✅ NEW：判斷 cacheHit（必須 effective 是 object 才算真正命中）
                boolean cacheHit = hit.isPresent()
                                   && hit.get().getEffective() != null
                                   && hit.get().getEffective().isObject();

                // ✅ NEW：Anti-abuse（會在觸發時直接丟 429 CooldownActiveException）
                abuseGuard.onOperationAttempt(userId, deviceId, cacheHit, serverNow, tz);

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

                    e.setEffective(processed);
                    e.setStatus(FoodLogStatus.DRAFT);
                } else {
                    // ✅ 未命中：扣 AI quota，provider 固定 GEMINI
                    AiQuotaEngine.Decision d = aiQuota.consumeOperationOrThrow(userId, tz, serverNow);
                    // 先用 degradeLevel 反映 tier，方便你立刻觀測（Step 2 才會真正選模型）
                    e.setDegradeLevel(d.tierUsed() == ModelTier.MODEL_TIER_HIGH ? "DG-0" : "DG-2");
                    e.setProvider(LABEL_PROVIDER);
                    e.setEffective(null);
                    e.setStatus(FoodLogStatus.PENDING);
                }

                repo.save(e);
                idem.attach(userId, requestId, e.getId(), serverNow);

                // 4) temp -> blob + refCount
                var retained = blobService.retainFromTemp(
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
                StorageCleanup.safeDeleteQuietly(storage, tempKey);
                idem.failAndReleaseIfNeeded(userId, requestId, "CREATE_LABEL_FAILED", safeMsg(ex), true);
                throw ex;
            }

        } finally {
            if (acquired) inFlight.release(userId);
        }
    }

    // =========================
    // BARCODE：MVP（查不到 → TRY_LABEL）
    // =========================
    @Transactional
    public FoodLogEnvelope createBarcodeMvp(Long userId, String barcode, String requestId) {
        Instant now = Instant.now();

        if (barcode == null || barcode.isBlank()) {
            throw new IllegalArgumentException("BARCODE_REQUIRED");
        }
        String bc = barcode.trim();

        // ✅ 基本 sanity（EAN/UPC 常見 8~14；你目前寬鬆 6~32 OK）
        if (bc.length() < 6 || bc.length() > 32) {
            throw new IllegalArgumentException("BARCODE_INVALID");
        }

        String existingLogId = idem.reserveOrGetExisting(userId, requestId, now);
        if (existingLogId != null) return getOne(userId, existingLogId, requestId);

        // ✅ 仍要限流
        rateLimiter.checkOrThrow(userId, now);

        // ✅ 建議：barcode 也用 UTC 先做 MVP（要更精準可改成接 clientTz header）
        ZoneId tz = ZoneOffset.UTC;
        LocalDate localDate = ZonedDateTime.ofInstant(now, tz).toLocalDate();

        // ===== 1) 查 OpenFoodFacts =====
        OffResult off;
        try {
            var root = offClient.getProduct(bc);
            off = OpenFoodFactsMapper.map(root);
        } catch (Exception ex) {
            // ✅ OFF 掛掉/timeout/JSON 壞：不要讓 API 500
            return buildBarcodeFailedEnvelope(userId, bc, requestId, now, tz, localDate,
                    "BARCODE_LOOKUP_FAILED", "openfoodfacts lookup failed: " + safeMsg(ex));
        }

        // ===== 2) 查不到：維持你原本行為（引導 TRY_LABEL）=====
        if (off == null) {
            return buildBarcodeFailedEnvelope(userId, bc, requestId, now, tz, localDate,
                    "BARCODE_NOT_FOUND", "barcode not found: " + bc);
        }

        // ===== 3) 查到：建立 DRAFT + effective（符合你既有 schema）=====
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

        // ✅ 建議存一份 barcode，之後歷史查詢/顯示會用到
        e.setBarcode(bc);

        // --- effective schema（最少必要欄位）---
        ObjectNode eff = JsonNodeFactory.instance.objectNode();

        // foodName：OFF 可能為 null，保底給個字串避免 UI 空白
        String name = (off.productName() == null || off.productName().isBlank())
                ? "Unknown product"
                : off.productName();
        eff.put("foodName", name);

        // quantity：OFF nutriments 大多是 per 100g
        ObjectNode qty = eff.putObject("quantity");
        qty.put("value", 100.0);
        qty.put("unit", "GRAM");

        // nutrients：你 schema 需要的欄位都塞（缺的就 putNull）
        ObjectNode n = eff.putObject("nutrients");
        putNumOrNull(n, "kcal", off.kcalPer100g());
        putNumOrNull(n, "protein", off.proteinPer100g());
        putNumOrNull(n, "fat", off.fatPer100g());
        putNumOrNull(n, "carbs", off.carbsPer100g());

        // 你 schema 還有 fiber/sugar/sodium，OFF 先不硬猜：留 null
        n.putNull("fiber");
        n.putNull("sugar");
        n.putNull("sodium");

        // confidence：條碼+資料庫，信心通常高（但仍可能資料缺）
        eff.put("confidence", 0.95);

        // （可選）aiMeta：保留來源資訊，對 debug 很有用
        ObjectNode aiMeta = eff.putObject("aiMeta");
        aiMeta.put("source", "OPENFOODFACTS");
        aiMeta.put("basis", "PER_100G");
        aiMeta.put("barcode", bc);

        // ✅ 套用你既有後處理（healthScore/sanityChecker/warnings whitelist）
        ObjectNode processed = postProcessor.apply(eff, e.getProvider(), e.getMethod());
        e.setEffective(processed);

        e.setStatus(FoodLogStatus.DRAFT);
        e.setLastErrorCode(null);
        e.setLastErrorMessage(null);

        repo.save(e);
        idem.attach(userId, requestId, e.getId(), now);

        // ✅ BARCODE 不需要 task
        return toEnvelope(e, null, requestId);
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
        e.setProvider("BARCODE");
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
        if (v == null) obj.putNull(field);
        else obj.put(field, v);
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
                            doubleOrNull(n, "kcal"),
                            doubleOrNull(n, "protein"),
                            doubleOrNull(n, "fat"),
                            doubleOrNull(n, "carbs"),
                            doubleOrNull(n, "fiber"),
                            doubleOrNull(n, "sugar"),
                            doubleOrNull(n, "sodium")
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
                nr,
                task,
                err,
                new FoodLogEnvelope.Trace(requestId)
        );
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
        rateLimiter.checkOrThrow(userId, now);

        FoodLogEntity log = repo.findByIdForUpdate(foodLogId);
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

        // ✅ NEW：retry 也算操作，給 abuseGuard（cacheHit=false）
        abuseGuard.onOperationAttempt(userId, deviceId, false, now, tz);

        // ✅ Step 1：retry 也算一次 Operation（會再打模型）
        AiQuotaEngine.Decision d = aiQuota.consumeOperationOrThrow(userId, tz, now);

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

    // ===== FoodLogService 新增/保留這段 helper：從 message 抽 retryAfterSec =====
// 放在 FoodLogService helpers 區塊即可（你原本若已有就用這版覆蓋）

    private static final java.util.regex.Pattern P_SUGGESTED_RETRY_AFTER =
            java.util.regex.Pattern.compile("suggestedRetryAfterSec=(\\d+)");
    private static final java.util.regex.Pattern P_RETRY_AFTER =
            java.util.regex.Pattern.compile("retryAfterSec=(\\d+)");

    private static Integer parseRetryAfterFromMessageOrNull(String msg) {
        if (msg == null || msg.isBlank()) return null;

        java.util.regex.Matcher m1 = P_SUGGESTED_RETRY_AFTER.matcher(msg);
        if (m1.find()) {
            try { return clampInt(Integer.parseInt(m1.group(1)), 0, 3600); }
            catch (Exception ignored) {}
        }

        java.util.regex.Matcher m2 = P_RETRY_AFTER.matcher(msg);
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
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asInt();
    }

    private static Double doubleOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asDouble();
    }

    private static Integer computeRetryAfterSecOrNull(FoodLogTaskEntity t, Instant now) {
        if (t == null || t.getNextRetryAtUtc() == null) return null;
        long sec = Duration.between(now, t.getNextRetryAtUtc()).getSeconds();
        if (sec < 0) sec = 0;
        if (sec > Integer.MAX_VALUE) sec = Integer.MAX_VALUE;
        return (int) sec;
    }

    private static int computePollAfterSec(FoodLogStatus status, FoodLogTaskEntity t, Instant now) {
        int base = Math.max(1, t.getPollAfterSec()); // DB 目前固定 2

        // ✅ FAILED：用 nextRetryAtUtc 算剩餘秒數
        if (status == FoodLogStatus.FAILED) {
            Integer retryAfter = computeRetryAfterSecOrNull(t, now);
            int v = (retryAfter == null) ? 5 : retryAfter;
            return clamp(v, 2, 60);
        }

        // ✅ PENDING：針對 QUEUED 做「排隊退避」
        if (status == FoodLogStatus.PENDING) {

            // 1) QUEUED：依排隊時間退避（>30s→5、>60s→8、>120s→10）
            if (t.getTaskStatus() == FoodLogTaskEntity.TaskStatus.QUEUED) {
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
            if (t.getTaskStatus() == FoodLogTaskEntity.TaskStatus.RUNNING) {
                return clamp(base, 2, 10);
            }

            // 3) 其他狀態（理論上少見）：輕度退避
            int attempts = Math.max(0, t.getAttempts());
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
