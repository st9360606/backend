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

            if (logEntity.getStatus() == FoodLogStatus.DELETED) {
                task.markCancelled(now, "LOG_DELETED", "food_log already deleted");
                taskRepo.save(task);
                continue;
            }

            if (logEntity.getStatus() == FoodLogStatus.DRAFT || logEntity.getStatus() == FoodLogStatus.SAVED) {
                task.markCancelled(now, "ALREADY_DONE", "food_log already processed");
                taskRepo.save(task);
                continue;
            }

            if (logEntity.getImageObjectKey() == null || logEntity.getImageObjectKey().isBlank()) {
                task.markCancelled(now, "IMAGE_OBJECT_KEY_MISSING", "missing imageObjectKey");
                taskRepo.save(task);

                logEntity.setStatus(FoodLogStatus.FAILED);
                logEntity.setLastErrorCode("IMAGE_OBJECT_KEY_MISSING");
                logEntity.setLastErrorMessage("missing imageObjectKey");
                logRepo.save(logEntity);
                continue;
            }

            // ✅ 單一真相：使用 TaskRetryPolicy.MAX_ATTEMPTS
            if (task.getAttempts() >= TaskRetryPolicy.MAX_ATTEMPTS) {
                task.markCancelled(now, "MAX_ATTEMPTS_EXCEEDED", "cancelled after max attempts");
                taskRepo.save(task);

                logEntity.setStatus(FoodLogStatus.FAILED);
                logEntity.setLastErrorCode("MAX_ATTEMPTS_EXCEEDED");
                logEntity.setLastErrorMessage("cancelled after max attempts");
                logRepo.save(logEntity);
                continue;
            }

            try {
                task.markRunning(now); // 你目前的語意：markRunning 會 attempts +1
                taskRepo.save(task);

                var result = providerClient.process(logEntity, storage);
                if (result == null || result.effective() == null) {
                    throw new IllegalStateException("PROVIDER_RETURNED_EMPTY");
                }

                logEntity.setEffective(result.effective());
                logEntity.setProvider(result.provider());
                logEntity.setStatus(FoodLogStatus.DRAFT);
                logEntity.setLastErrorCode(null);
                logEntity.setLastErrorMessage(null);

                task.markSucceeded(now);

                logRepo.save(logEntity);
                taskRepo.save(task);

            } catch (Exception e) {
                log.warn("task failed: {}", task.getId(), e);

                // ✅ 這次失敗後，如果已達最大嘗試次數 → GIVE UP → CANCELLED
                if (task.getAttempts() >= TaskRetryPolicy.MAX_ATTEMPTS) {
                    task.markCancelled(now, "PROVIDER_GIVE_UP", safeMsg(e));
                    taskRepo.save(task);

                    logEntity.setStatus(FoodLogStatus.FAILED);
                    logEntity.setLastErrorCode("PROVIDER_GIVE_UP");   // ✅ 你的測試期待這個
                    logEntity.setLastErrorMessage(safeMsg(e));
                    logRepo.save(logEntity);
                    continue;
                }

                // ✅ 未達上限 → 排程重試
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

    private static String safeMsg(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m;
    }
}
