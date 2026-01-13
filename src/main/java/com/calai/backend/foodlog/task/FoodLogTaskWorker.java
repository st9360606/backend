package com.calai.backend.foodlog.task;

import com.calai.backend.foodlog.dto.FoodLogStatus;
import com.calai.backend.foodlog.entity.FoodLogTaskEntity;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.repo.FoodLogTaskRepository;
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
public class FoodLogTaskWorker {

    private static final int BATCH_SIZE = 10;
    private static final int MAX_ATTEMPTS = 5;
    private final FoodLogTaskRepository taskRepo;
    private final FoodLogRepository logRepo;
    private final ProviderClient providerClient;
    private final StorageService storage;

    @Scheduled(fixedDelay = 2000)
    @Transactional
    public void runOnce() {
        Instant now = Instant.now();
        List<FoodLogTaskEntity> tasks = taskRepo.claimRunnableForUpdate(now, BATCH_SIZE);

        for (FoodLogTaskEntity task : tasks) {
            var logEntity = logRepo.findByIdForUpdate(task.getFoodLogId());

            // ✅ 1) 已刪除：直接取消，不跑 provider
            if (logEntity.getStatus() == FoodLogStatus.DELETED) {
                task.markCancelled(now, "LOG_DELETED", "food_log already deleted");
                taskRepo.save(task);
                continue;
            }

            // ✅ 2) 已完成：避免重複跑（DRAFT/SAVED）
            if (logEntity.getStatus() == FoodLogStatus.DRAFT || logEntity.getStatus() == FoodLogStatus.SAVED) {
                task.markCancelled(now, "ALREADY_DONE", "food_log already processed");
                taskRepo.save(task);
                continue;
            }

            // ✅ 3) 基本檢查：缺 objectKey 視為資料不一致 → 取消（不重試）
            if (logEntity.getImageObjectKey() == null || logEntity.getImageObjectKey().isBlank()) {
                task.markCancelled(now, "IMAGE_OBJECT_KEY_MISSING", "missing imageObjectKey");
                logEntity.setStatus(FoodLogStatus.FAILED);
                logEntity.setLastErrorCode("IMAGE_OBJECT_KEY_MISSING");
                logEntity.setLastErrorMessage("missing imageObjectKey");
                taskRepo.save(task);
                logRepo.save(logEntity);
                continue;
            }

            if (task.getAttempts() >= MAX_ATTEMPTS) {
                task.setTaskStatus(FoodLogTaskEntity.TaskStatus.CANCELLED);
                task.setNextRetryAtUtc(null);
                task.setLastErrorCode("MAX_ATTEMPTS_EXCEEDED");
                task.setLastErrorMessage("cancelled after max attempts");
                // log 也標記 FAILED（但不再重跑）
                var log = logRepo.findByIdForUpdate(task.getFoodLogId());
                log.setStatus(FoodLogStatus.FAILED);
                log.setLastErrorCode("MAX_ATTEMPTS_EXCEEDED");
                log.setLastErrorMessage("cancelled after max attempts");
                continue;
            }

            try {
                // ✅ 真正要呼叫 provider 才算一次 attempt
                task.markRunning(now);
                taskRepo.save(task);

                var result = providerClient.process(logEntity.getId(), logEntity.getImageObjectKey(), storage);
                if (result == null || result.effective() == null) {
                    throw new IllegalStateException("PROVIDER_RETURNED_EMPTY");
                }

                logEntity.setEffective(result.effective());
                logEntity.setProvider(result.provider());
                logEntity.setStatus(FoodLogStatus.DRAFT);
                logEntity.setLastErrorCode(null);
                logEntity.setLastErrorMessage(null);

                task.markSucceeded(now);

                // ✅ 明確 save，避免你後續改成 detach / batch 時踩坑
                logRepo.save(logEntity);
                taskRepo.save(task);

            } catch (Exception e) {
                log.warn("task failed: {}", task.getId(), e);

                // ✅ 最大重試：停止自動重試（避免燒錢）
                if (TaskRetryPolicy.shouldGiveUp(task.getAttempts()) || isPermanent(e)) {
                    task.markCancelled(now, "PROVIDER_GIVE_UP", safeMsg(e));
                    taskRepo.save(task);

                    logEntity.setStatus(FoodLogStatus.FAILED);
                    logEntity.setLastErrorCode("PROVIDER_GIVE_UP");
                    logEntity.setLastErrorMessage(safeMsg(e));
                    logRepo.save(logEntity);
                    continue;
                }

                int delaySec = TaskRetryPolicy.nextDelaySec(task.getAttempts());
                task.markFailed(now, "PROVIDER_FAILED", safeMsg(e), delaySec);
                taskRepo.save(task);

                logEntity.setStatus(FoodLogStatus.FAILED);
                logEntity.setLastErrorCode("PROVIDER_FAILED");
                logEntity.setLastErrorMessage(safeMsg(e));
                logRepo.save(logEntity);
            }
        }
    }

    /** ✅ 先用簡單規則：IllegalArgument 視為不可恢復（例如 provider 參數/格式不合法） */
    private static boolean isPermanent(Exception e) {
        return (e instanceof IllegalArgumentException);
    }

    private static String safeMsg(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m;
    }
}
