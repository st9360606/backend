package com.calai.backend.foodlog.retention;

import com.calai.backend.foodlog.model.FoodLogStatus;
import com.calai.backend.foodlog.entity.DeletionJobEntity;
import com.calai.backend.foodlog.entity.FoodLogTaskEntity;
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
@ConditionalOnProperty(prefix = "app.retention.foodlog", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FoodLogRetentionWorker {

    private final FoodLogRetentionProperties props;
    private final FoodLogRepository logRepo;
    private final FoodLogTaskRepository taskRepo;
    private final DeletionJobRepository deletionRepo;
    private final ImageBlobService blobService;

    /** ✅ 用 Clock 方便測試（Testcontainers + fixed clock） */
    private final Clock clock = Clock.systemUTC();

    // 每天凌晨 05:30 跑（你可改）
    @Scheduled(cron = "0 30 5 * * *")
    @Transactional
    public void runDaily() {
        if (!props.isEnabled()) return;

        Instant now = Instant.now(clock);

        // 1) 短期：DRAFT/PENDING/FAILED
        Instant cutoffDraft = now.minus(props.getKeepDraft());
        int n1 = processExpired(now, cutoffDraft, List.of("DRAFT","PENDING","FAILED"), props.getBatchSize());

        // 2) 長期：SAVED
        Instant cutoffSaved = now.minus(props.getKeepSaved());
        int n2 = processExpired(now, cutoffSaved, List.of("SAVED"), props.getBatchSize());

        if (n1 + n2 > 0) {
            log.info("retention done. draftExpired={} savedExpired={}", n1, n2);
        }
    }

    private int processExpired(Instant now, Instant cutoff, List<String> statuses, int limit) {
        int processed = 0;

        // 批次領（避免一次太大）
        List<com.calai.backend.foodlog.entity.FoodLogEntity> logs =
                logRepo.claimExpiredForUpdate(statuses, cutoff, limit);

        for (var logEntity : logs) {
            // 已刪就跳過（保守）
            if (logEntity.getStatus() == FoodLogStatus.DELETED) continue;

            // 取消 task（避免 worker 競態）
            taskRepo.findByFoodLogIdForUpdate(logEntity.getId()).ifPresent(t -> {
                if (t.getTaskStatus() == FoodLogTaskEntity.TaskStatus.QUEUED
                    || t.getTaskStatus() == FoodLogTaskEntity.TaskStatus.RUNNING
                    || t.getTaskStatus() == FoodLogTaskEntity.TaskStatus.FAILED) {
                    t.markCancelled(now, "RETENTION_DELETED", "cancelled by retention");
                    taskRepo.save(t);
                }
            });

            // 軟刪（並標記來源）
            logEntity.setStatus(FoodLogStatus.DELETED);
            logEntity.setDeletedBy("RETENTION");
            logEntity.setDeletedAtUtc(now);

            // ✅ 依你的要求「結果/歷史也要刪」：先做脫敏（MVP）
            // 先清空 effective，避免保留營養結果與 warnings
            logEntity.setEffective(null);

            logRepo.save(logEntity);

            // enqueue deletion job：釋放 blob / 刪照片
            if (logEntity.getImageSha256() != null && !logEntity.getImageSha256().isBlank()) {
                String ext = blobService.findExtOrNull(logEntity.getUserId(), logEntity.getImageSha256());

                DeletionJobEntity job = new DeletionJobEntity();
                job.setFoodLogId(logEntity.getId());
                job.setUserId(logEntity.getUserId());
                job.setSha256(logEntity.getImageSha256());
                job.setExt(ext);
                job.setImageObjectKey(logEntity.getImageObjectKey());
                job.setJobStatus(DeletionJobEntity.JobStatus.QUEUED);
                job.setAttempts(0);
                job.setNextRetryAtUtc(null);
                deletionRepo.save(job);
            }

            processed++;
        }

        return processed;
    }
}
