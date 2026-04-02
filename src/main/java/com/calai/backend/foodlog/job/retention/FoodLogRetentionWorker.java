package com.calai.backend.foodlog.job.retention;

import com.calai.backend.foodlog.entity.DeletionJobEntity;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.entity.FoodLogTaskEntity;
import com.calai.backend.foodlog.model.FoodLogStatus;
import com.calai.backend.foodlog.repo.DeletionJobRepository;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.repo.FoodLogTaskRepository;
import com.calai.backend.foodlog.service.ImageBlobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
@Component
@ConditionalOnProperty(
        prefix = "app.retention.foodlog",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class FoodLogRetentionWorker {

    private final FoodLogRetentionProperties props;
    private final FoodLogRepository logRepo;
    private final FoodLogTaskRepository taskRepo;
    private final DeletionJobRepository deletionRepo;
    private final ImageBlobService blobService;
    private final Clock clock;

    @Scheduled(cron = "0 30 5 * * *")
    @Transactional
    public void runDaily() {
        if (!props.isEnabled()) {
            return;
        }

        Instant now = Instant.now(clock);

        // 0) 圖片 retention
        // - 非 SAVED：3 天
        // - SAVED：15 天
        Instant cutoffImageDefault = now.minus(props.getKeepOriginalImage());
        Instant cutoffImageSaved = now.minus(props.getKeepSavedOriginalImage());
        int imageExpired = processExpiredImages(cutoffImageDefault, cutoffImageSaved, props.getBatchSize());

        // 1) PENDING：2 天後轉 DELETED
        Instant cutoffPending = now.minus(props.getKeepPending());
        int pendingExpired = processExpired(now, cutoffPending, List.of("PENDING"), props.getBatchSize());

        // 2) FAILED：7 天後轉 DELETED
        Instant cutoffFailed = now.minus(props.getKeepFailed());
        int failedExpired = processExpired(now, cutoffFailed, List.of("FAILED"), props.getBatchSize());

        // 3) DRAFT：15 天後轉 DELETED
        Instant cutoffDraft = now.minus(props.getKeepDraft());
        int draftExpired = processExpired(now, cutoffDraft, List.of("DRAFT"), props.getBatchSize());

        // 4) DELETED tombstone：15 天後 hard purge
        Instant cutoffDeleted = now.minus(props.getKeepDeletedTombstone());
        int deletedPurged = purgeDeletedTombstones(cutoffDeleted, props.getBatchSize());

        if (imageExpired + pendingExpired + failedExpired + draftExpired + deletedPurged > 0) {
            log.info(
                    "retention done. imageExpired={} pendingExpired={} failedExpired={} draftExpired={} deletedPurged={}",
                    imageExpired, pendingExpired, failedExpired, draftExpired, deletedPurged
            );
        }
    }

    /**
     * 只清原始圖片，不刪 food_logs row 本身。
     */
    private int processExpiredImages(Instant defaultCutoff, Instant savedCutoff, int limit) {
        int processed = 0;

        List<FoodLogEntity> logs = logRepo.claimImageExpiredForUpdate(defaultCutoff, savedCutoff, limit);

        for (FoodLogEntity logEntity : logs) {
            if (logEntity.getStatus() == FoodLogStatus.DELETED) {
                continue;
            }

            String objectKey = trimToNull(logEntity.getImageObjectKey());
            String sha256 = trimToNull(logEntity.getImageSha256());

            // 沒圖了就直接清欄位，避免殘值
            if (objectKey == null) {
                clearImageRefs(logEntity);
                logRepo.save(logEntity);
                continue;
            }

            // 避免重複建立 deletion job
            var existing = deletionRepo.findByFoodLogIdForUpdate(logEntity.getId()).orElse(null);
            if (existing == null) {
                String ext = sha256 == null ? null : blobService.findExtOrNull(logEntity.getUserId(), sha256);

                DeletionJobEntity job = new DeletionJobEntity();
                job.setFoodLogId(logEntity.getId());
                job.setUserId(logEntity.getUserId());
                job.setSha256(sha256); // nullable
                job.setExt(ext);
                job.setImageObjectKey(objectKey);
                job.setJobStatus(DeletionJobEntity.JobStatus.QUEUED);
                job.setAttempts(0);
                job.setNextRetryAtUtc(null);
                deletionRepo.save(job);
            }

            clearImageRefs(logEntity);
            logRepo.save(logEntity);
            processed++;
        }

        return processed;
    }

    /**
     * 把過期的 PENDING / FAILED / DRAFT 轉成 DELETED tombstone。
     */
    private int processExpired(Instant now, Instant cutoff, List<String> statuses, int limit) {
        int processed = 0;

        List<FoodLogEntity> logs = logRepo.claimExpiredForUpdate(statuses, cutoff, limit);

        for (FoodLogEntity logEntity : logs) {
            if (logEntity.getStatus() == FoodLogStatus.DELETED) {
                continue;
            }

            taskRepo.findByFoodLogIdForUpdate(logEntity.getId()).ifPresent(t -> {
                if (t.getTaskStatus() == FoodLogTaskEntity.TaskStatus.QUEUED
                    || t.getTaskStatus() == FoodLogTaskEntity.TaskStatus.RUNNING
                    || t.getTaskStatus() == FoodLogTaskEntity.TaskStatus.FAILED) {
                    t.markCancelled(now, "RETENTION_DELETED", "cancelled by retention");
                    taskRepo.save(t);
                }
            });

            logEntity.setStatus(FoodLogStatus.DELETED);
            logEntity.setDeletedBy("RETENTION");
            logEntity.setDeletedAtUtc(now);
            logEntity.setEffective(null);

            String objectKey = trimToNull(logEntity.getImageObjectKey());
            String sha256 = trimToNull(logEntity.getImageSha256());

            if (objectKey != null) {
                var existing = deletionRepo.findByFoodLogIdForUpdate(logEntity.getId()).orElse(null);
                if (existing == null) {
                    String ext = sha256 == null ? null : blobService.findExtOrNull(logEntity.getUserId(), sha256);

                    DeletionJobEntity job = new DeletionJobEntity();
                    job.setFoodLogId(logEntity.getId());
                    job.setUserId(logEntity.getUserId());
                    job.setSha256(sha256); // nullable
                    job.setExt(ext);
                    job.setImageObjectKey(objectKey);
                    job.setJobStatus(DeletionJobEntity.JobStatus.QUEUED);
                    job.setAttempts(0);
                    job.setNextRetryAtUtc(null);
                    deletionRepo.save(job);
                }

                // deletion job 已存在（或剛建立），可安全清掉 tombstone 上的圖片 refs
                clearImageRefs(logEntity);
            }

            logRepo.save(logEntity);
            processed++;
        }

        return processed;
    }

    /**
     * 超過 keepDeletedTombstone 的 DELETED row，真的從 DB 刪掉。
     * 注意這裡要看 deleted_at_utc，不是 server_received_at_utc。
     */
    private int purgeDeletedTombstones(Instant cutoff, int limit) {
        int processed = 0;

        List<FoodLogEntity> logs = logRepo.claimDeletedTombstonesForUpdate(cutoff, limit);

        for (FoodLogEntity logEntity : logs) {
            deletionRepo.findByFoodLogIdForUpdate(logEntity.getId())
                    .ifPresent(deletionRepo::delete);

            taskRepo.findByFoodLogIdForUpdate(logEntity.getId())
                    .ifPresent(taskRepo::delete);

            logRepo.delete(logEntity);
            processed++;
        }

        return processed;
    }

    private void clearImageRefs(FoodLogEntity logEntity) {
        logEntity.setImageObjectKey(null);
        logEntity.setImageSha256(null);
        logEntity.setImageContentType(null);
        logEntity.setImageSizeBytes(null);
    }

    private String trimToNull(String s) {
        if (s == null) return null;
        String v = s.trim();
        return v.isEmpty() ? null : v;
    }
}
