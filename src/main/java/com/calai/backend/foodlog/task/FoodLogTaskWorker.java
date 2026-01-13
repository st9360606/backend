package com.calai.backend.foodlog.task;

import com.calai.backend.foodlog.dto.FoodLogStatus;
import com.calai.backend.foodlog.entity.FoodLogTaskEntity;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.repo.FoodLogTaskRepository;
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

    @Scheduled(fixedDelay = 2000)
    @Transactional
    public void runOnce() {
        Instant now = Instant.now();

        // ✅ 硬化：領取 + lock 一批 runnable tasks（避免多 instance 重複撿）
        List<FoodLogTaskEntity> tasks = taskRepo.claimRunnableForUpdate(now, BATCH_SIZE);

        for (FoodLogTaskEntity task : tasks) {
            try {
                // 標記 RUNNING（在同一個 TX 內，row 已經被鎖住）
                task.markRunning(now);

                var result = providerClient.process(task);

                var log = logRepo.findByIdForUpdate(task.getFoodLogId());
                log.setEffective(result.effective());
                log.setProvider(result.provider());
                log.setStatus(FoodLogStatus.DRAFT);
                log.setLastErrorCode(null);
                log.setLastErrorMessage(null);

                task.markSucceeded(now);

            } catch (Exception e) {
                log.warn("task failed: {}", task.getId(), e);

                // 失敗：下次 10 秒後再試
                task.markFailed(now, "PROVIDER_FAILED", e.getMessage(), 10);

                var log = logRepo.findByIdForUpdate(task.getFoodLogId());
                log.setStatus(FoodLogStatus.FAILED);
                log.setLastErrorCode("PROVIDER_FAILED");
                log.setLastErrorMessage(e.getMessage());
            }
        }
    }
}
