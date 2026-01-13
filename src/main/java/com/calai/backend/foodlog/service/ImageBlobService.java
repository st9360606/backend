package com.calai.backend.foodlog.service;

import com.calai.backend.foodlog.entity.ImageBlobEntity;
import com.calai.backend.foodlog.repo.ImageBlobRepository;
import com.calai.backend.foodlog.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.FileAlreadyExistsException;
import java.time.Instant;

@RequiredArgsConstructor
@Service
public class ImageBlobService {

    private final ImageBlobRepository repo;
    private final StorageService storage;

    public record RetainResult(String objectKey, String sha256) {}

    /**
     * ✅ 將 temp 檔案「提升」成內容定址 blob：
     * - 若首次：insertFirst=1 → move(temp -> blobKey)
     * - 若已存在：retain ref_count → delete(temp)
     */
    @Transactional
    public RetainResult retainFromTemp(Long userId,
                                       String tempKey,
                                       String sha256,
                                       String ext,
                                       String contentType,
                                       long sizeBytes) throws Exception {

        Instant now = Instant.now();
        String blobKey = blobKey(userId, sha256, ext);

        int inserted = repo.insertFirst(userId, sha256, blobKey, contentType, sizeBytes, ext, now);
        if (inserted == 1) {
            // 我是第一個建立 blob 的人：把 temp 搬到 blobKey
            try {
                // 若極端併發：blobKey 可能已被別人搬好（ATOMIC_MOVE 會拋例外）
                if (storage.exists(blobKey)) {
                    storage.delete(tempKey);
                } else {
                    storage.move(tempKey, blobKey);
                }
            } catch (FileAlreadyExistsException e) {
                // 另一個請求先 move 成功了
                storage.delete(tempKey);
            } catch (Exception ex) {
                // 補償：blob row 已插入但檔案沒到位 → 刪 temp + 將 refCount 歸零並刪 row（簡化）
                try { storage.delete(tempKey); } catch (Exception ignored) {}
                repo.release(userId, sha256, Instant.now());
                repo.deleteIfZero(userId, sha256);
                throw ex;
            }
            return new RetainResult(blobKey, sha256);
        }

        // 已存在：ref_count + 1，並刪 temp
        repo.retain(userId, sha256, now);
        storage.delete(tempKey);

        return new RetainResult(blobKey, sha256);
    }

    /** 刪除引用：ref_count--；若變 0 才刪檔與 row */
    @Transactional
    public void release(Long userId, String sha256, String ext) throws Exception {
        Instant now = Instant.now();
        repo.release(userId, sha256, now);

        Integer rc = repo.getRefCount(userId, sha256);
        if (rc != null && rc <= 0) {
            String key = blobKey(userId, sha256, ext);
            try { storage.delete(key); } catch (Exception ignored) {}
            repo.deleteIfZero(userId, sha256);
        }
    }

    private static String blobKey(Long userId, String sha256, String ext) {
        return "user-" + userId + "/blobs/sha256/" + sha256 + ext;
    }

    public String findExtOrNull(Long userId, String sha256) {
        return repo.findByUserIdAndSha256(userId, sha256)
                .map(ImageBlobEntity::getExt)
                .orElse(null);
    }

}
