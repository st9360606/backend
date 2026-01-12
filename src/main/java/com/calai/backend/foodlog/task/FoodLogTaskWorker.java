 package com.calai.backend.foodlog.task;

import com.calai.backend.foodlog.db.FoodLogEntity;
import com.calai.backend.foodlog.db.FoodLogRepository;
import com.calai.backend.foodlog.db.FoodLogTaskEntity;
import com.calai.backend.foodlog.db.FoodLogTaskRepository;
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

    private final FoodLogTaskRepository taskRepo;
    private final FoodLogRepository logRepo;
    private final ProviderClient providerClient; // 你之後接 LogMeal/Gemini 的介面

    @Scheduled(fixedDelay = 2000)
    @Transactional
    public void runOnce() {
        Instant now = Instant.now();
        List<FoodLogTaskEntity> tasks = taskRepo.findRunnable(now);

        for (FoodLogTaskEntity task : tasks) {
            try {
                task.markRunning(now);

                // === 1) 呼叫 provider（stub）===
                ProviderResult result = providerClient.process(task);

                // === 2) 更新 food_logs → DRAFT（effective/original）===
                FoodLogEntity log = logRepo.findByIdForUpdate(task.getFoodLogId());
                log.applySuccess(result, now);

                // === 3) task → SUCCEEDED ===
                task.markSucceeded(now);

            } catch (Exception e) {
                log.warn("task failed: {}", task.getId(), e);
                task.markFailed(now, "PROVIDER_FAILED", e.getMessage());
                FoodLogEntity log = logRepo.findByIdForUpdate(task.getFoodLogId());
                log.applyFailed("PROVIDER_FAILED", e.getMessage(), now);
            }
        }
    }
}
