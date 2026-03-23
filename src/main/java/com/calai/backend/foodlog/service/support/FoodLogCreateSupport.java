package com.calai.backend.foodlog.service.support;

import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.entity.FoodLogTaskEntity;
import com.calai.backend.foodlog.image.ImageSniffer;
import com.calai.backend.foodlog.model.FoodLogErrorCode;
import com.calai.backend.foodlog.model.FoodLogMethod;
import com.calai.backend.foodlog.model.FoodLogStatus;
import com.calai.backend.foodlog.model.TimeSource;
import com.calai.backend.foodlog.processing.effective.FoodLogEffectivePostProcessor;
import com.calai.backend.foodlog.quota.model.ModelTier;
import com.calai.backend.foodlog.service.ImageBlobService;
import com.calai.backend.foodlog.service.request.IdempotencyService;
import com.calai.backend.foodlog.storage.StorageService;
import com.calai.backend.foodlog.storage.support.StorageCleanup;
import com.calai.backend.foodlog.web.error.FoodLogAppException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.PushbackInputStream;
import java.time.Instant;
import java.time.LocalDate;

/**
 * createAlbum / createPhoto / createLabel 的共用骨架支援。
 * 目前負責：
 * 1. uploadTempImage
 * 2. retainBlobAndAttach
 * 3. createQueuedTask
 * 4. newBaseEntity
 * 5. applyCacheHitDraft
 * 6. applyPendingMiss
 */
@Component
@RequiredArgsConstructor
public class FoodLogCreateSupport {

    private final StorageService storage;
    private final IdempotencyService idem;
    private final ImageBlobService blobService;
    private final FoodLogEffectivePostProcessor postProcessor;

    public record UploadTempResult(
            String tempKey,
            ImageSniffer.Detection detection,
            StorageService.SaveResult saved
    ) {}

    /**
     * 上傳臨時圖片：
     * - detect image format
     * - 決定 tempKey
     * - 存到 storage
     *
     * 失敗時：
     * - best effort 刪 temp
     * - release idempotency
     */
    public UploadTempResult uploadTempImage(
            Long userId,
            String requestId,
            MultipartFile file
    ) throws Exception {
        String tempKey = null;

        try (InputStream raw = file.getInputStream();
             PushbackInputStream in = new PushbackInputStream(raw, 16)) {

            ImageSniffer.Detection det = ImageSniffer.detect(in);
            if (det == null) {
                throw new FoodLogAppException(FoodLogErrorCode.UNSUPPORTED_IMAGE_FORMAT);
            }

            tempKey = "user-" + userId + "/blobs/tmp/" + requestId + "/upload" + det.ext();
            StorageService.SaveResult saved = storage.save(tempKey, in, det.contentType());

            return new UploadTempResult(tempKey, det, saved);

        } catch (Exception ex) {
            StorageCleanup.safeDeleteQuietly(storage, tempKey);

            if (tempKey == null) {
                StorageCleanup.safeDeleteTempUploadFallback(storage, userId, requestId);
            }

            idem.failAndReleaseIfNeeded(userId, requestId, true);
            throw ex;
        }
    }

    /**
     * temp -> blob retain，並把 image 相關欄位寫回 entity。
     */
    public void retainBlobAndAttach(
            FoodLogEntity e,
            Long userId,
            UploadTempResult upload
    ) throws Exception {
        ImageBlobService.RetainResult retained = blobService.retainFromTemp(
                userId,
                upload.tempKey(),
                upload.saved().sha256(),
                upload.detection().ext(),
                upload.saved().contentType(),
                upload.saved().sizeBytes()
        );

        e.setImageObjectKey(retained.objectKey());
        e.setImageSha256(retained.sha256());
        e.setImageContentType(upload.saved().contentType());
        e.setImageSizeBytes(upload.saved().sizeBytes());
    }

    /**
     * 建立標準 queued task。
     */
    public FoodLogTaskEntity createQueuedTask(String foodLogId) {
        FoodLogTaskEntity t = new FoodLogTaskEntity();
        t.setFoodLogId(foodLogId);
        t.setTaskStatus(FoodLogTaskEntity.TaskStatus.QUEUED);
        t.setPollAfterSec(2);
        t.setNextRetryAtUtc(null);
        return t;
    }

    public FoodLogEntity newBaseEntity(
            Long userId,
            FoodLogMethod method,
            Instant capturedAtUtc,
            String capturedTz,
            LocalDate capturedLocalDate,
            Instant serverReceivedAtUtc,
            TimeSource timeSource,
            boolean timeSuspect
    ) {
        return newBaseEntity(
                userId,
                method == null ? null : method.code(),
                capturedAtUtc,
                capturedTz,
                capturedLocalDate,
                serverReceivedAtUtc,
                timeSource,
                timeSuspect
        );
    }

    public FoodLogEntity newBaseEntity(
            Long userId,
            String method,
            Instant capturedAtUtc,
            String capturedTz,
            LocalDate capturedLocalDate,
            Instant serverReceivedAtUtc,
            TimeSource timeSource,
            boolean timeSuspect
    ) {
        FoodLogEntity e = new FoodLogEntity();
        e.setUserId(userId);
        e.setMethod(method);
        e.setDegradeLevel("DG-0");
        e.setCapturedAtUtc(capturedAtUtc);
        e.setCapturedTz(capturedTz);
        e.setCapturedLocalDate(capturedLocalDate);
        e.setServerReceivedAtUtc(serverReceivedAtUtc);
        e.setTimeSource(timeSource);
        e.setTimeSuspect(timeSuspect);
        return e;
    }

    public void applyCacheHitDraft(
            FoodLogEntity e,
            FoodLogEntity hit
    ) {
        e.setProvider(hit.getProvider());
        e.setDegradeLevel(hit.getDegradeLevel() == null ? "DG-0" : hit.getDegradeLevel());

        ObjectNode copied = hit.getEffective().deepCopy();
        ObjectNode processed = postProcessor.apply(copied, e.getProvider(), e.getMethod());
        markResultFromCache(processed);

        e.setEffective(processed);
        e.setStatus(FoodLogStatus.DRAFT);
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
        aiMeta.put("resultFromCache", true);
    }

    public void applyPendingMiss(
            FoodLogEntity e,
            ModelTier tierUsed,
            String provider
    ) {
        e.setDegradeLevel(tierUsed == ModelTier.MODEL_TIER_HIGH ? "DG-0" : "DG-2");
        e.setProvider(provider);
        e.setEffective(null);
        e.setStatus(FoodLogStatus.PENDING);
    }
}
