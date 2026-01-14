package com.calai.backend.foodlog.task;

import com.calai.backend.foodlog.dto.FoodLogStatus;
import com.calai.backend.foodlog.entity.DeletionJobEntity;
import com.calai.backend.foodlog.repo.DeletionJobRepository;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.service.ImageBlobService;
import com.calai.backend.foodlog.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
@Component
public class DeletionJobWorker {

    private static final int BATCH_SIZE = 20;

    private final DeletionJobRepository repo;
    private final ImageBlobService blobService;

    // ✅ fallback 需要的依賴
    private final FoodLogRepository foodLogRepo;
    private final StorageService storage;

    @Scheduled(fixedDelay = 3000)
    @Transactional
    public void runOnce() {
        Instant now = Instant.now();
        List<DeletionJobEntity> jobs = repo.claimRunnableForUpdate(now, BATCH_SIZE);

        for (DeletionJobEntity job : jobs) {
            try {
                job.markRunning(now);
                repo.save(job);

                // ✅ sha/ext 缺失：走 fallback（有 objectKey 才能做）
                if (isBlank(job.getSha256()) || isBlank(job.getExt())) {
                    handleFallbackCleanup(now, job, "MISSING_SHA_OR_EXT");
                    continue;
                }

                // ✅ 正常路徑：ref_count 歸零才刪檔（你 Step3.11）
                try {
                    blobService.release(job.getUserId(), job.getSha256(), job.getExt());
                    job.markSucceeded(now);
                    repo.save(job);
                } catch (Exception e) {
                    // ✅ 如果是 blob row missing：重試通常沒意義 → 改走 fallback
                    if (isBlobRowMissing(e)) {
                        handleFallbackCleanup(now, job, "BLOB_ROW_MISSING");
                        continue;
                    }
                    throw e;
                }

            } catch (Exception e) {
                log.warn("deletion job failed: id={}", job.getId(), e);
                int retryAfter = nextDelaySec(job.getAttempts());
                job.markFailed(now, safeMsg(e), retryAfter);
                repo.save(job);
            }
        }
    }

    /**
     * ✅ 安全閘 fallback：
     * 1) 有 image_object_key 才能清
     * 2) refs==0 才允許 move/delete
     * 3) refs>0：禁止刪 → CANCELLED + 留證據
     */
    private void handleFallbackCleanup(Instant now, DeletionJobEntity job, String reason) {
        String objectKey = job.getImageObjectKey();
        if (isBlank(objectKey)) {
            job.markCancelled(now, reason + ":MISSING_OBJECT_KEY");
            repo.save(job);
            return;
        }

        long refs = foodLogRepo.countLiveRefsByObjectKey(job.getUserId(), objectKey, FoodLogStatus.DELETED);
        if (refs > 0) {
            job.markCancelled(now, reason + ":REFERENCED refs=" + refs);
            repo.save(job);
            log.warn("fallback cleanup skipped (referenced). jobId={}, objectKey={}, refs={}",
                    job.getId(), objectKey, refs);
            return;
        }

        // ✅ refs==0 → 可以清。更保守：先 move 到 trash（可再加 TTL GC）
        try {
            if (storage.exists(objectKey)) {
                String trashKey = "user-" + job.getUserId()
                                  + "/blobs/trash/deletion-job-" + job.getId()
                                  + "/" + sanitizeFileName(objectKey);

                try {
                    storage.move(objectKey, trashKey);
                } catch (Exception moveEx) {
                    // move 不支援/失敗 → 降級 delete
                    storage.delete(objectKey);
                }
            }
            job.markSucceeded(now);
            repo.save(job);

        } catch (Exception ex) {
            // fallback 也失敗：回到 FAILED 可重試（或你也可以選擇 CANCELLED）
            job.markFailed(now, reason + ":FALLBACK_FAILED:" + safeMsg(ex), 60);
            repo.save(job);
        }
    }

    private static boolean isBlobRowMissing(Exception e) {
        // ✅ 依你的 blobService.release 實作調整：建議改成自訂例外型別更乾淨
        String m = e.getMessage();
        if (m == null) return false;
        return m.contains("BLOB_ROW_NOT_FOUND")
               || m.contains("IMAGE_BLOB_NOT_FOUND")
               || m.contains("blob row missing");
    }

    private static String sanitizeFileName(String objectKey) {
        // 只為了產生 trash key，不要讓 / 破壞路徑
        return objectKey.replace("/", "_");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static int nextDelaySec(int attempts) {
        if (attempts <= 1) return 2;
        if (attempts == 2) return 5;
        if (attempts == 3) return 15;
        if (attempts == 4) return 30;
        return 60;
    }

    private static String safeMsg(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m;
    }
}
