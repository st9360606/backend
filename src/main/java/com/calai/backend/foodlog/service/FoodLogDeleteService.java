package com.calai.backend.foodlog.service;

import com.calai.backend.foodlog.dto.FoodLogEnvelope;
import com.calai.backend.foodlog.dto.FoodLogStatus;
import com.calai.backend.foodlog.entity.DeletionJobEntity;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.entity.FoodLogTaskEntity;
import com.calai.backend.foodlog.repo.DeletionJobRepository;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.repo.FoodLogTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@RequiredArgsConstructor
@Service
public class FoodLogDeleteService {

    private final FoodLogRepository logRepo;
    private final FoodLogTaskRepository taskRepo;
    private final DeletionJobRepository deletionRepo;
    private final ImageBlobService blobService;
    private final FoodLogService foodLogService; // 用它的 toEnvelope/getOne（先重用）

    @Transactional
    public FoodLogEnvelope deleteOne(Long userId, String foodLogId, String requestId) {
        Instant now = Instant.now();

        FoodLogEntity log = logRepo.findByIdForUpdate(foodLogId);
        if (!userId.equals(log.getUserId())) throw new IllegalArgumentException("FOOD_LOG_NOT_FOUND");

        // ✅ 冪等刪除：已 DELETED 直接回
        if (log.getStatus() == FoodLogStatus.DELETED) {
            return foodLogService.getOne(userId, foodLogId, requestId);
        }

        // ✅ 先取消 task（更硬一層，避免 worker 競態）
        var optTask = taskRepo.findByFoodLogIdForUpdate(foodLogId);
        if (optTask.isPresent()) {
            FoodLogTaskEntity t = optTask.get();
            if (t.getTaskStatus() == FoodLogTaskEntity.TaskStatus.QUEUED
                || t.getTaskStatus() == FoodLogTaskEntity.TaskStatus.RUNNING
                || t.getTaskStatus() == FoodLogTaskEntity.TaskStatus.FAILED) {
                t.markCancelled(now, "LOG_DELETED", "cancelled by delete");
                taskRepo.save(t);
            }
        }

        // ✅ 設為 DELETED（軟刪）
        log.setStatus(FoodLogStatus.DELETED);
        log.setDeletedAtUtc(now);
        log.setDeletedBy("USER");
        logRepo.save(log);

        // ✅ enqueue deletion job（只要有 sha256 才需要 release blob）
        if (log.getImageSha256() != null && !log.getImageSha256().isBlank()) {
            // ✅ 不要 parse objectKey：ext 從 image_blobs 取（若沒有就給 null，worker 會 CANCELLED）
            String ext = blobService.findExtOrNull(userId, log.getImageSha256());

            DeletionJobEntity job = new DeletionJobEntity();
            job.setFoodLogId(foodLogId);
            job.setUserId(userId);
            job.setSha256(log.getImageSha256());
            job.setExt(ext);
            job.setImageObjectKey(log.getImageObjectKey());
            job.setJobStatus(DeletionJobEntity.JobStatus.QUEUED);
            job.setAttempts(0);
            job.setNextRetryAtUtc(null);
            deletionRepo.save(job);
        }

        return foodLogService.getOne(userId, foodLogId, requestId);
    }
}
