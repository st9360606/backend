package com.calai.backend.foodlog.service.image;

import com.calai.backend.foodlog.model.FoodLogStatus;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;

/**
 * 專門負責 food log 圖片讀取。
 * 這層只做：
 * - 驗證 food log 是否存在 / 是否已刪除
 * - 取得 image object key / content type / size
 * - 開啟 storage input stream
 */
@Service
@RequiredArgsConstructor
public class FoodLogImageAccessService {

    private final FoodLogRepository repo;
    private final StorageService storage;

    @Transactional(readOnly = true)
    public ImageOpenResult openImage(Long userId, String foodLogId) {
        var log = repo.findByIdAndUserId(foodLogId, userId)
                .orElseThrow(() -> new IllegalArgumentException("FOOD_LOG_NOT_FOUND"));

        if (log.getStatus() == FoodLogStatus.DELETED) {
            throw new IllegalArgumentException("FOOD_LOG_DELETED");
        }

        if (log.getImageObjectKey() == null || log.getImageObjectKey().isBlank()) {
            throw new IllegalStateException("IMAGE_OBJECT_KEY_MISSING");
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
