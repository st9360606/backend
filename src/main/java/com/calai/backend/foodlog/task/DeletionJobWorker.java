package com.calai.backend.foodlog.task;

import com.calai.backend.foodlog.entity.DeletionJobEntity;
import com.calai.backend.foodlog.repo.DeletionJobRepository;
import com.calai.backend.foodlog.service.ImageBlobService;
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

    @Scheduled(fixedDelay = 3000)
    @Transactional
    public void runOnce() {
        Instant now = Instant.now();
        List<DeletionJobEntity> jobs = repo.claimRunnableForUpdate(now, BATCH_SIZE);

        for (DeletionJobEntity job : jobs) {
            try {
                job.markRunning(now);
                repo.save(job);

                // ✅ 沒 sha/ext：表示不是圖（barcode/label）或資料缺漏 → 直接取消避免無限重試
                if (job.getSha256() == null || job.getSha256().isBlank()
                    || job.getExt() == null || job.getExt().isBlank()) {
                    job.markCancelled(now, "MISSING_SHA_OR_EXT");
                    repo.save(job);
                    continue;
                }

                // ✅ 釋放引用：ref_count 歸零才刪檔 + 刪 row（你 Step3.11 已完成）
                blobService.release(job.getUserId(), job.getSha256(), job.getExt());

                job.markSucceeded(now);
                repo.save(job);

            } catch (Exception e) {
                log.warn("deletion job failed: id={}", job.getId(), e);
                int retryAfter = nextDelaySec(job.getAttempts());
                job.markFailed(now, safeMsg(e), retryAfter);
                repo.save(job);
            }
        }
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
