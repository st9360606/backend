package com.calai.backend.foodlog.service.cleanup;

import com.calai.backend.foodlog.storage.StorageService;
import lombok.extern.slf4j.Slf4j;

/**
 * 任何 cleanup 都用這支，避免 cleanup 失敗蓋過主要例外。
 */
@Slf4j
public final class StorageCleanup {

    private StorageCleanup() {}

    public static void safeDeleteQuietly(StorageService storage, String objectKey) {
        if (storage == null) return;
        if (objectKey == null || objectKey.isBlank()) return;
        try {
            storage.delete(objectKey);
        } catch (Exception e) {
            log.debug("cleanup delete failed: key={}", objectKey, e);
        }
    }

    /**
     * 如果你真的拿不到 ext（極少），才用這個：同時試 .jpg/.png
     */
    public static void safeDeleteTempUploadFallback(StorageService storage, Long userId, String requestId) {
        if (storage == null) return;
        if (userId == null) return;
        if (requestId == null || requestId.isBlank()) return;

        safeDeleteQuietly(storage, "user-" + userId + "/blobs/tmp/" + requestId + "/upload.jpg");
        safeDeleteQuietly(storage, "user-" + userId + "/blobs/tmp/" + requestId + "/upload.png");
    }
}
