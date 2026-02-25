package com.calai.backend.foodlog.service;

import com.calai.backend.foodlog.entity.ImageBlobEntity;
import com.calai.backend.foodlog.repo.ImageBlobRepository;
import com.calai.backend.foodlog.storage.LocalDiskStorageService;
import com.calai.backend.foodlog.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.FileAlreadyExistsException;
import java.time.Instant;

@Slf4j
@RequiredArgsConstructor
@Service
public class ImageBlobService {

    private final ImageBlobRepository repo;
    private final StorageService storage;

    /**
     * @param newlyCreated true = 這次 insertFirst 成功，並由 temp 搬成新 blob
     *                     false = blob 已存在，這次只是 ref_count + 1（重用）
     */
    public record RetainResult(String objectKey, String sha256, boolean newlyCreated) {}

    /**
     * ✅ 將 temp 檔案「提升」成內容定址 blob：
     * - 若首次：insertFirst=1 → move(temp -> blobKey)
     * - 若已存在：retain ref_count → delete(temp)
     * 重要修正：
     * - 已存在分支不再使用呼叫端 ext 組 blobKey 回傳，改用 DB 既有 row 的 objectKey（避免 ext 不一致）
     */
    @Transactional
    public RetainResult retainFromTemp(Long userId,
                                       String tempKey,
                                       String sha256,
                                       String ext,
                                       String contentType,
                                       long sizeBytes) throws Exception {

        Instant now = Instant.now();
        String candidateBlobKey = blobKey(userId, sha256, ext);

        int inserted = repo.insertFirst(userId, sha256, candidateBlobKey, contentType, sizeBytes, ext, now);
        if (inserted == 1) {
            // 我是第一個建立 blob 的人：把 temp 搬到 blobKey
            try {
                // 若極端併發：blobKey 可能已被別人搬好（ATOMIC_MOVE / 非原子 move 都可能遇到）
                if (storage.exists(candidateBlobKey)) {
                    // 代表檔案已存在（競態下別人先搬完），這邊只刪 temp
                    storage.delete(tempKey);
                    cleanupTempParentDirBestEffort(tempKey);
                } else {
                    storage.move(tempKey, candidateBlobKey);
                    cleanupTempParentDirBestEffort(tempKey);
                }
            } catch (FileAlreadyExistsException e) {
                // 另一個請求先 move 成功了（競態）
                try {
                    storage.delete(tempKey);
                } finally {
                    cleanupTempParentDirBestEffort(tempKey);
                }
            } catch (Exception ex) {
                // 補償：blob row 已插入但檔案沒到位 → 刪 temp + 將 refCount 歸零並刪 row（簡化）
                try {
                    storage.delete(tempKey);
                } catch (Exception ignored) {
                    // ignore
                } finally {
                    cleanupTempParentDirBestEffort(tempKey);
                }

                repo.release(userId, sha256, Instant.now());
                repo.deleteIfZero(userId, sha256);
                throw ex;
            }

            return new RetainResult(candidateBlobKey, sha256, true);
        }

        // ✅ 已存在：ref_count + 1
        int updated = repo.retain(userId, sha256, now);
        if (updated <= 0) {
            // 極端競態防呆：insertFirst=0 但 retain=0（可能 row 被併發清理）
            throw new IllegalStateException(
                    "retain existing blob failed after duplicate insert path. userId=" + userId + ", sha256=" + sha256
            );
        }

        // ✅ 一定用 DB 既有 row 當 source of truth（避免 ext 不一致）
        ImageBlobEntity existing = repo.findByUserIdAndSha256(userId, sha256)
                .orElseThrow(() -> new IllegalStateException(
                        "image_blobs row missing after retain. userId=" + userId + ", sha256=" + sha256));

        // ✅ 保守刪 temp：刪除失敗不影響主流程，交給排程補清
        safeDeleteTempAfterReuse(tempKey, userId, sha256, existing.getObjectKey());

        return new RetainResult(existing.getObjectKey(), sha256, false);
    }

    /**
     * ✅ 建議版 API：不再依賴呼叫端傳 ext
     * 刪除引用：ref_count--；若變 0 才刪檔與 row
     * 重要修正：
     * - 刪檔路徑改用 DB row 的 objectKey，避免呼叫端 ext 傳錯導致刪錯檔 / 刪不到檔。
     */
    @Transactional
    public void release(Long userId, String sha256) throws Exception {
        Instant now = Instant.now();

        // 先抓 row（若不存在，表示已清掉或從未建立）
        ImageBlobEntity existing = repo.findByUserIdAndSha256(userId, sha256).orElse(null);
        if (existing == null) {
            log.debug("release skipped: image blob row not found. userId={}, sha256={}", userId, sha256);
            return;
        }

        repo.release(userId, sha256, now);

        Integer rc = repo.getRefCount(userId, sha256);
        if (rc != null && rc <= 0) {
            String objectKey = existing.getObjectKey(); // ✅ 用 DB objectKey，不信任呼叫端 ext
            try {
                storage.delete(objectKey);
            } catch (Exception ex) {
                // best-effort：刪檔失敗先記錄，row 仍會嘗試 deleteIfZero；若殘留檔案由 orphan cleaner 補清
                log.warn("release file delete failed. userId={}, sha256={}, objectKey={}, err={}",
                        userId, sha256, objectKey, ex.toString());
            }

            repo.deleteIfZero(userId, sha256);
        }
    }

    /**
     * ✅ 相容過渡版（保留舊簽名）
     * 內部忽略 ext，改走新邏輯，避免你現有呼叫點一次改太多。
     */
    @Deprecated
    @Transactional
    public void release(Long userId, String sha256, String ext) throws Exception {
        release(userId, sha256);
    }

    /**
     * ✅ 重用既有 blob 時的 temp 清理（保守模式）
     * - 成功：刪 temp + best-effort 刪掉 requestId 空資料夾
     * - 失敗：只記錄 warning，不中斷主流程
     */
    private void safeDeleteTempAfterReuse(String tempKey, Long userId, String sha256, String blobKey) {
        if (tempKey == null || tempKey.isBlank()) return;

        try {
            storage.delete(tempKey);
            cleanupTempParentDirBestEffort(tempKey);
        } catch (Exception ex) {
            log.warn(
                    "reuse existing blob but temp delete failed. userId={}, sha256={}, tempKey={}, blobKey={}, err={}",
                    userId, sha256, tempKey, blobKey, ex.toString()
            );
        }
    }

    /**
     * ✅ 僅限 LocalDisk 時，順手刪 temp 父資料夾（requestId 目錄）
     * 不影響主流程；刪不掉就交給排程。
     */
    private void cleanupTempParentDirBestEffort(String tempKey) {
        if (!(storage instanceof LocalDiskStorageService local)) return;
        if (tempKey == null || tempKey.isBlank()) return;

        try {
            local.deleteEmptyParentDirQuietly(tempKey);
        } catch (Exception ignored) {
            // best-effort，不影響主流程
        }
    }

    private static String blobKey(Long userId, String sha256, String ext) {
        String normalizedExt = (ext == null) ? "" : ext;
        return "user-" + userId + "/blobs/sha256/" + sha256 + normalizedExt;
    }

    public String findExtOrNull(Long userId, String sha256) {
        return repo.findByUserIdAndSha256(userId, sha256)
                .map(ImageBlobEntity::getExt)
                .orElse(null);
    }
}
