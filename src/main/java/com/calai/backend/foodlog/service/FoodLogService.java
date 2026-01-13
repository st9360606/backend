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

    @Transactional
    public FoodLogEnvelope createAlbum(Long userId, String clientTz, MultipartFile file, String requestId) throws Exception {
        ZoneId tz = parseTzOrUtc(clientTz);
        Instant serverNow = Instant.now();
        validateUploadBasics(file);

        // ✅ Step 3.10A：先做冪等占位
        String existingLogId = idem.reserveOrGetExisting(userId, requestId, serverNow);
        if (existingLogId != null) {
            return getOne(userId, existingLogId, requestId);
        }

        try {
            // ✅ 只會被第一個成功 reserve 的請求扣 quota
            quota.consumeAiOrThrow(userId, tz, serverNow);

            LocalDate todayLocal = ZonedDateTime.ofInstant(serverNow, tz).toLocalDate();

            FoodLogEntity e = new FoodLogEntity();
            e.setUserId(userId);
            e.setStatus(FoodLogStatus.PENDING);
            e.setMethod("ALBUM");
            e.setProvider("STUB");
            e.setDegradeLevel("DG-0");
            e.setCapturedAtUtc(serverNow);
            e.setCapturedTz(tz.getId());
            e.setCapturedLocalDate(todayLocal);
            e.setServerReceivedAtUtc(serverNow);
            e.setTimeSource(TimeSource.SERVER_RECEIVED);
            e.setTimeSuspect(false);
            e.setEffective(null);

            repo.save(e);

            // ✅ attach：此 requestId 從此對應到這個 food_log_id
            idem.attach(userId, requestId, e.getId(), serverNow);

            String objectKey;
            StorageService.SaveResult saved;

            try (InputStream raw = file.getInputStream();
                 PushbackInputStream in = new PushbackInputStream(raw, 16)) {

                ImageSniffer.Detection det = ImageSniffer.detect(in);
                if (det == null) throw new IllegalArgumentException("UNSUPPORTED_IMAGE_FORMAT");

                objectKey = "user-" + userId + "/food-log/" + e.getId() + "/original" + det.ext();

                try {
                    saved = storage.save(objectKey, in, det.contentType());
                } catch (Exception ex) {
                    try {
                        storage.delete(objectKey);
                    } catch (Exception ignored) {
                    }
                    throw ex;
                }
            }

            e.setImageObjectKey(saved.objectKey());
            e.setImageSha256(saved.sha256());
            e.setImageContentType(saved.contentType());
            e.setImageSizeBytes(saved.sizeBytes());
            repo.save(e);

            // ✅ Step 3.10B：sha256 去重，如果已有 DRAFT/SAVED 的 effective，直接重用，不建 task、不跑 provider
            var hit = repo.findFirstByUserIdAndImageSha256AndStatusInOrderByCreatedAtUtcDesc(
                    userId,
                    e.getImageSha256(),
                    java.util.List.of(FoodLogStatus.DRAFT, FoodLogStatus.SAVED)
            );

            if (hit.isPresent() && hit.get().getEffective() != null) {
                e.setEffective(hit.get().getEffective());
                e.setProvider(hit.get().getProvider());
                e.setStatus(FoodLogStatus.DRAFT);
                e.setLastErrorCode(null);
                e.setLastErrorMessage(null);
                repo.save(e);

                // ✅ 不建立 task（也不排隊）
                return toEnvelope(e, null, requestId);
            }

            // 否則照舊：建立 task → PENDING → worker 跑 provider
            FoodLogTaskEntity t = new FoodLogTaskEntity();
            t.setFoodLogId(e.getId());
            t.setTaskStatus(FoodLogTaskEntity.TaskStatus.QUEUED);
            t.setPollAfterSec(2);
            t.setNextRetryAtUtc(null);
            taskRepo.save(t);

            return toEnvelope(e, t, requestId);

        } catch (Exception ex) {
            // ✅ 失敗不要讓 requestId 永久卡住 RESERVED
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
