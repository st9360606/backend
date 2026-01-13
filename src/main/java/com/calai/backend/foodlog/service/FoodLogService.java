package com.calai.backend.foodlog.service;

import com.calai.backend.foodlog.dto.FoodLogEnvelope;
import com.calai.backend.foodlog.dto.FoodLogStatus;
import com.calai.backend.foodlog.dto.TimeSource;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.entity.FoodLogTaskEntity;
import com.calai.backend.foodlog.image.ImageSniffer;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.repo.FoodLogTaskRepository;
import com.calai.backend.foodlog.storage.StorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.PushbackInputStream;
import java.time.*;
import java.io.InputStream;
import java.util.Optional;

@RequiredArgsConstructor
@Service
public class FoodLogService {

    private static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024; // 8MB（先保守）
    private final FoodLogRepository repo;
    private final FoodLogTaskRepository taskRepo;
    private final StorageService storage;
    private final ObjectMapper om;
    private final QuotaService quota;
    public record OpenedImage(String objectKey, String contentType, long sizeBytes) {}
    private final IdempotencyService idem;
    private final ImageBlobService blobService;

    @Transactional
    public FoodLogEnvelope createAlbum(Long userId, String clientTz, MultipartFile file, String requestId) throws Exception {
        ZoneId tz = parseTzOrUtc(clientTz);
        Instant serverNow = Instant.now();
        validateUploadBasics(file);

        String existingLogId = idem.reserveOrGetExisting(userId, requestId, serverNow);
        if (existingLogId != null) return getOne(userId, existingLogId, requestId);

        String tempKey = null;
        ImageSniffer.Detection det;
        StorageService.SaveResult saved;

        try (InputStream raw = file.getInputStream();
             PushbackInputStream in = new PushbackInputStream(raw, 16)) {

            det = ImageSniffer.detect(in);
            if (det == null) throw new IllegalArgumentException("UNSUPPORTED_IMAGE_FORMAT");

            // ✅ tempKey 不依賴 foodLogId（因為 log 可能還沒建立）
            tempKey = "user-" + userId + "/blobs/tmp/" + requestId + "/upload" + det.ext();
            saved = storage.save(tempKey, in, det.contentType());
        } catch (Exception ex) {
            idem.failAndReleaseIfNeeded(userId, requestId, "UPLOAD_FAILED", safeMsg(ex), true);
            throw ex;
        }

        try {
            // ✅ 先看是否命中「已有有效結果」（不扣 quota）
            var hit = repo.findFirstByUserIdAndImageSha256AndStatusInOrderByCreatedAtUtcDesc(
                    userId,
                    saved.sha256(),
                    java.util.List.of(FoodLogStatus.DRAFT, FoodLogStatus.SAVED)
            );

            // ✅ 先建立 food_log（不管命中與否都要有一筆 log）
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

            if (hit.isPresent() && hit.get().getEffective() != null) {
                // 命中：直接 DRAFT（不排 task、不扣 quota）
                e.setProvider(hit.get().getProvider());
                e.setEffective(hit.get().getEffective());
                e.setStatus(FoodLogStatus.DRAFT);
            } else {
                // 未命中：這時才扣 quota，然後排 task → PENDING
                quota.consumeAiOrThrow(userId, tz, serverNow);
                e.setProvider("STUB");
                e.setEffective(null);
                e.setStatus(FoodLogStatus.PENDING);
            }

            repo.save(e);
            idem.attach(userId, requestId, e.getId(), serverNow);

            // ✅ Step 3.11：temp -> blobKey + refCount
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

            // 命中重用：不建 task
            if (e.getStatus() == FoodLogStatus.DRAFT) {
                return toEnvelope(e, null, requestId);
            }

            // 未命中：照舊建 task
            FoodLogTaskEntity t = new FoodLogTaskEntity();
            t.setFoodLogId(e.getId());
            t.setTaskStatus(FoodLogTaskEntity.TaskStatus.QUEUED);
            t.setPollAfterSec(2);
            t.setNextRetryAtUtc(null);
            taskRepo.save(t);

            return toEnvelope(e, t, requestId);

        } catch (Exception ex) {
            // ✅ 補償：temp 若還存在就刪
            try { if (tempKey != null) storage.delete(tempKey); } catch (Exception ignored) {}
            idem.failAndReleaseIfNeeded(userId, requestId, "CREATE_ALBUM_FAILED", safeMsg(ex), true);
            throw ex;
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

    @Transactional(readOnly = true)
    public FoodLogEnvelope getOne(Long userId, String id, String requestId) {
        FoodLogEntity e = repo.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("FOOD_LOG_NOT_FOUND"));

        // ✅ 硬化：只有 PENDING/FAILED 才查 task
        FoodLogTaskEntity t = null;
        if (e.getStatus() == FoodLogStatus.PENDING || e.getStatus() == FoodLogStatus.FAILED) {
            t = taskRepo.findByFoodLogId(e.getId()).orElse(null);
        }

        return toEnvelope(e, t, requestId);
    }

    private FoodLogEnvelope toEnvelope(FoodLogEntity e, FoodLogTaskEntity t, String requestId) {
        JsonNode eff = e.getEffective();
        FoodLogEnvelope.NutritionResult nr = null;

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
                    new FoodLogEnvelope.Source(e.getMethod(), e.getProvider())
            );
        }

        FoodLogEnvelope.Task task = null;
        if (t != null && (e.getStatus() == FoodLogStatus.PENDING || e.getStatus() == FoodLogStatus.FAILED)) {
            task = new FoodLogEnvelope.Task(t.getId(), t.getPollAfterSec());
        }

        FoodLogEnvelope.ApiError err = null;
        if (e.getStatus() == FoodLogStatus.FAILED) {
            err = new FoodLogEnvelope.ApiError(
                    e.getLastErrorCode(),
                    "RETRY_LATER",
                    (t == null || t.getNextRetryAtUtc() == null) ? null : 2
            );
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

    /** StreamingResponseBody 會負責關閉 */
    public InputStream openImageStream(String objectKey) throws Exception {
        return storage.open(objectKey).inputStream();
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

    @Transactional
    public FoodLogEnvelope retry(Long userId, String foodLogId, String requestId) {
        Instant now = Instant.now();

        // ✅ lock food_log（避免同時被 worker/別人 retry 改狀態）
        FoodLogEntity log = repo.findByIdForUpdate(foodLogId);
        if (!userId.equals(log.getUserId())) throw new IllegalArgumentException("FOOD_LOG_NOT_FOUND");

        if (log.getStatus() == FoodLogStatus.DELETED) throw new IllegalArgumentException("FOOD_LOG_DELETED");
        if (log.getStatus() == FoodLogStatus.DRAFT || log.getStatus() == FoodLogStatus.SAVED) {
            throw new IllegalArgumentException("FOOD_LOG_NOT_RETRYABLE");
        }
        if (log.getStatus() != FoodLogStatus.FAILED) {
            throw new IllegalArgumentException("FOOD_LOG_NOT_RETRYABLE");
        }

        // ✅ Step 3.9：retry 也扣點（用 log.capturedTz 當 userTz）
        ZoneId tz = ZoneId.of(log.getCapturedTz());
        quota.consumeAiOrThrow(userId, tz, now);

        // ✅ lock task（沒有就補一筆）
        Optional<FoodLogTaskEntity> opt = taskRepo.findByFoodLogIdForUpdate(foodLogId);
        FoodLogTaskEntity task = opt.orElseGet(() -> {
            FoodLogTaskEntity t = new FoodLogTaskEntity();
            t.setFoodLogId(foodLogId);
            t.setTaskStatus(FoodLogTaskEntity.TaskStatus.QUEUED);
            t.setPollAfterSec(2);
            t.setAttempts(0);
            return t;
        });

        // ✅ 重置 task 成可立即執行
        task.setTaskStatus(FoodLogTaskEntity.TaskStatus.QUEUED);
        task.setNextRetryAtUtc(null);
        task.setPollAfterSec(2);
        task.setAttempts(0);
        task.setLastErrorCode(null);
        task.setLastErrorMessage(null);
        taskRepo.save(task);

        // ✅ 把 log 拉回 PENDING，讓 App poll 起來
        log.setStatus(FoodLogStatus.PENDING);
        log.setLastErrorCode(null);
        log.setLastErrorMessage(null);
        repo.save(log);

        return getOne(userId, foodLogId, requestId); // 直接回 envelope（含 task）
    }
}
