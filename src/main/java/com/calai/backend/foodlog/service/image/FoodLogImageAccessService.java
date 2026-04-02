package com.calai.backend.foodlog.service.image;

import com.calai.backend.foodlog.job.retention.FoodLogRetentionProperties;
import com.calai.backend.foodlog.model.FoodLogErrorCode;
import com.calai.backend.foodlog.model.FoodLogStatus;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.storage.StorageService;
import com.calai.backend.foodlog.web.error.FoodLogAppException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class FoodLogImageAccessService {

    private final FoodLogRepository repo;
    private final StorageService storage;
    private final FoodLogRetentionProperties retentionProperties;
    private final Clock clock;

    @Transactional(readOnly = true)
    public ImageOpenResult openImage(Long userId, String foodLogId) {
        var log = repo.findByIdAndUserId(foodLogId, userId)
                .orElseThrow(() -> new FoodLogAppException(FoodLogErrorCode.FOOD_LOG_NOT_FOUND));

        if (log.getStatus() == FoodLogStatus.DELETED) {
            throw new FoodLogAppException(FoodLogErrorCode.FOOD_LOG_DELETED);
        }

        Instant cutoff = Instant.now(clock).minus(
                log.getStatus() == FoodLogStatus.SAVED
                        ? retentionProperties.getKeepSavedOriginalImage()
                        : retentionProperties.getKeepOriginalImage()
        );

        if (log.getServerReceivedAtUtc() != null && !log.getServerReceivedAtUtc().isAfter(cutoff)) {
            throw new FoodLogAppException(FoodLogErrorCode.FOOD_LOG_NOT_FOUND);
        }

        if (log.getImageObjectKey() == null || log.getImageObjectKey().isBlank()) {
            throw new FoodLogAppException(FoodLogErrorCode.IMAGE_OBJECT_KEY_MISSING);
        }

        long size = log.getImageSizeBytes() == null ? -1L : log.getImageSizeBytes();

        return new ImageOpenResult(
                log.getImageObjectKey(),
                log.getImageContentType(),
                size
        );
    }

    public InputStream openImageStream(String objectKey) throws Exception {
        return storage.open(objectKey).inputStream();
    }
}
