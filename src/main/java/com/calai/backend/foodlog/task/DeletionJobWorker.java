package com.calai.backend.foodlog.task;

import com.calai.backend.foodlog.entity.DeletionJobEntity;
import com.calai.backend.foodlog.model.FoodLogStatus;
import com.calai.backend.foodlog.repo.DeletionJobRepository;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.service.ImageBlobService;
import com.calai.backend.foodlog.storage.StorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Slf4j
@Component
public class DeletionJobWorker {

    private static final int BATCH_SIZE = 20;

    private final DeletionJobRepository repo;
    private final ImageBlobService blobService;
    private final FoodLogRepository foodLogRepo;
    private final StorageService storage;
    private final TransactionTemplate txTemplate;
    private final Clock clock;

    public DeletionJobWorker(
            DeletionJobRepository repo,
            ImageBlobService blobService,
            FoodLogRepository foodLogRepo,
            StorageService storage,
            PlatformTransactionManager txManager,
            Clock clock
    ) {
        this.repo = repo;
        this.blobService = blobService;
        this.foodLogRepo = foodLogRepo;
        this.storage = storage;
        this.txTemplate = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 3000)
    public void runOnce() {
        Instant now = clock.instant();

        List<String> jobIds = txTemplate.execute(status ->
                repo.claimRunnableForUpdate(now, BATCH_SIZE).stream()
                        .map(DeletionJobEntity::getId)
                        .toList()
        );

        if (jobIds == null || jobIds.isEmpty()) {
            return;
        }

        for (String jobId : jobIds) {
            processOne(jobId);
        }
    }

    private void processOne(String jobId) {
        DeletionExecution execution = txTemplate.execute(status -> prepareExecution(jobId));
        if (execution == null) {
            return;
        }

        // 只有 sha 缺失時，才無法走正常 release
        if (execution.missingSha()) {
            handleFallbackCleanup(execution, "MISSING_SHA");
            return;
        }

        try {
            ImageBlobService.ReleaseOutcome outcome =
                    blobService.release(execution.userId(), execution.sha256());

            if (outcome == ImageBlobService.ReleaseOutcome.ROW_MISSING) {
                handleFallbackCleanup(execution, "BLOB_ROW_MISSING");
                return;
            }

            txTemplate.executeWithoutResult(status ->
                    markSucceeded(execution.jobId())
            );

        } catch (Exception e) {
            log.warn("deletion job failed: id={}", execution.jobId(), e);

            int retryAfter = nextDelaySec(execution.attemptsAfterStart());
            txTemplate.executeWithoutResult(status ->
                    markFailed(execution.jobId(), safeMsg(e), retryAfter)
            );
        }
    }

    /**
     * 短交易：
     * 1. 取 job（row lock）
     * 2. 檢查是否仍 runnable
     * 3. markRunning
     * 4. 回傳 snapshot 給交易外處理
     */
    private DeletionExecution prepareExecution(String jobId) {
        DeletionJobEntity job = repo.findByIdForUpdate(jobId).orElse(null);
        if (job == null) {
            return null;
        }

        Instant now = clock.instant();

        // 若已不是 runnable，就不要重複處理
        if (job.getJobStatus() != DeletionJobEntity.JobStatus.QUEUED
                && job.getJobStatus() != DeletionJobEntity.JobStatus.FAILED) {
            return null;
        }

        if (job.getNextRetryAtUtc() != null && job.getNextRetryAtUtc().isAfter(now)) {
            return null;
        }

        job.markRunning(now);
        repo.save(job);

        return new DeletionExecution(
                job.getId(),
                job.getUserId(),
                job.getSha256(),
                job.getImageObjectKey(),
                job.getAttempts()
        );
    }

    /**
     * 安全閘 fallback：
     * 1) 有 image_object_key 才能清
     * 2) refs==0 才允許 move/delete
     * 3) refs>0：禁止刪 → CANCELLED + 留證據
     *
     * storage I/O 全部在交易外
     */
    private void handleFallbackCleanup(DeletionExecution execution, String reason) {
        String objectKey = execution.imageObjectKey();

        if (isBlank(objectKey)) {
            txTemplate.executeWithoutResult(status ->
                    markCancelled(execution.jobId(), reason + ":MISSING_OBJECT_KEY")
            );
            return;
        }

        long refs = foodLogRepo.countLiveRefsByObjectKey(
                execution.userId(),
                objectKey,
                FoodLogStatus.DELETED
        );

        if (refs > 0) {
            log.warn("fallback cleanup skipped (referenced). jobId={}, objectKey={}, refs={}",
                    execution.jobId(), objectKey, refs);

            txTemplate.executeWithoutResult(status ->
                    markCancelled(execution.jobId(), reason + ":REFERENCED refs=" + refs)
            );
            return;
        }

        try {
            if (storage.exists(objectKey)) {
                String trashKey = "user-" + execution.userId()
                        + "/blobs/trash/deletion-job-" + execution.jobId()
                        + "/" + sanitizeFileName(objectKey);

                try {
                    storage.move(objectKey, trashKey);
                } catch (Exception moveEx) {
                    // move 不支援 / 失敗 → 降級 delete
                    storage.delete(objectKey);
                }
            }

            txTemplate.executeWithoutResult(status ->
                    markSucceeded(execution.jobId())
            );

        } catch (Exception ex) {
            txTemplate.executeWithoutResult(status ->
                    markFailed(execution.jobId(), reason + ":FALLBACK_FAILED:" + safeMsg(ex), 60)
            );
        }
    }

    /**
     * 保守回寫：
     * - 再次 row lock
     * - 若 job 不存在，直接略過
     */
    private void markSucceeded(String jobId) {
        DeletionJobEntity job = repo.findByIdForUpdate(jobId).orElse(null);
        if (job == null) {
            return;
        }
        if (job.getJobStatus() != DeletionJobEntity.JobStatus.RUNNING
                && job.getJobStatus() != DeletionJobEntity.JobStatus.FAILED) {
            return;
        }
        job.markSucceeded(clock.instant());
        repo.save(job);
    }

    /**
     * 保守回寫：
     * - 再次 row lock
     * - 若 job 不存在，直接略過
     */
    private void markFailed(String jobId, String message, int retryAfterSec) {
        DeletionJobEntity job = repo.findByIdForUpdate(jobId).orElse(null);
        if (job == null) {
            return;
        }
        if (job.getJobStatus() != DeletionJobEntity.JobStatus.RUNNING
                && job.getJobStatus() != DeletionJobEntity.JobStatus.FAILED) {
            return;
        }
        job.markFailed(clock.instant(), message, retryAfterSec);
        repo.save(job);
    }

    /**
     * 保守回寫：
     * - 再次 row lock
     * - 若 job 不存在，直接略過
     */
    private void markCancelled(String jobId, String reason) {
        DeletionJobEntity job = repo.findByIdForUpdate(jobId).orElse(null);
        if (job == null) {
            return;
        }
        if (job.getJobStatus() != DeletionJobEntity.JobStatus.RUNNING
                && job.getJobStatus() != DeletionJobEntity.JobStatus.FAILED
                && job.getJobStatus() != DeletionJobEntity.JobStatus.QUEUED) {
            return;
        }
        job.markCancelled(clock.instant(), reason);
        repo.save(job);
    }

    private static String sanitizeFileName(String objectKey) {
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

    private record DeletionExecution(
            String jobId,
            Long userId,
            String sha256,
            String imageObjectKey,
            int attemptsAfterStart
    ) {
        boolean missingSha() {
            return isBlank(sha256);
        }
    }
}
