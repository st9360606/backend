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
import org.springframework.http.MediaType;

import java.io.PushbackInputStream;
import java.time.*;
import java.io.InputStream;

@RequiredArgsConstructor
@Service
public class FoodLogService {

    private static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024; // 8MB（先保守）
    private final FoodLogRepository repo;
    private final FoodLogTaskRepository taskRepo;
    private final StorageService storage;
    private final ObjectMapper om;

    public record OpenedImage(String objectKey, String contentType, long sizeBytes) {}

    @Transactional
    public FoodLogEnvelope createAlbum(Long userId, String clientTz, MultipartFile file, String requestId) throws Exception {
        ZoneId tz = parseTzOrUtc(clientTz);
        Instant serverNow = Instant.now();
        LocalDate todayLocal = ZonedDateTime.ofInstant(serverNow, tz).toLocalDate();

        validateUploadBasics(file);

        // 先建 log（拿到 id）
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

        // ✅ Step 3.5：用 magic number 決定 type/ext/contentType
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
                try { storage.delete(objectKey); } catch (Exception ignored) {}
                throw ex;
            }
        }

        e.setImageObjectKey(saved.objectKey());
        e.setImageSha256(saved.sha256());
        e.setImageContentType(saved.contentType());
        e.setImageSizeBytes(saved.sizeBytes());
        repo.save(e);

        FoodLogTaskEntity t = new FoodLogTaskEntity();
        t.setFoodLogId(e.getId());
        t.setTaskStatus(FoodLogTaskEntity.TaskStatus.QUEUED);
        t.setPollAfterSec(2);
        t.setNextRetryAtUtc(null);
        taskRepo.save(t);

        return toEnvelope(e, t, requestId);
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
}
