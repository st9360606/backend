package com.calai.backend.foodlog.job.worker;

import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.entity.FoodLogTaskEntity;
import com.calai.backend.foodlog.provider.support.ProviderErrorMapper;
import com.calai.backend.foodlog.model.FoodLogStatus;
import com.calai.backend.foodlog.provider.routing.ProviderRouter;
import com.calai.backend.foodlog.provider.spi.ProviderClient;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.repo.FoodLogTaskRepository;
import com.calai.backend.foodlog.storage.StorageService;
import com.calai.backend.foodlog.processing.FoodLogEffectivePostProcessor;
import com.calai.backend.foodlog.unit.FoodLogWarning;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
public class FoodLogTaskWorker {

    private static final int BATCH_SIZE = 10;

    private final FoodLogTaskRepository taskRepo;
    private final FoodLogRepository logRepo;
    private final ProviderRouter router;
    private final StorageService storage;
    private final FoodLogEffectivePostProcessor postProcessor;
    private final TransactionTemplate txTemplate;
    private final Clock clock;

    public FoodLogTaskWorker(
            FoodLogTaskRepository taskRepo,
            FoodLogRepository logRepo,
            ProviderRouter router,
            StorageService storage,
            FoodLogEffectivePostProcessor postProcessor,
            PlatformTransactionManager txManager,
            Clock clock
    ) {
        this.taskRepo = taskRepo;
        this.logRepo = logRepo;
        this.router = router;
        this.storage = storage;
        this.postProcessor = postProcessor;
        this.txTemplate = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 2000)
    public void runOnce() {
        Instant claimAt = clock.instant();

        List<String> taskIds = txTemplate.execute(status ->
                taskRepo.claimRunnableForUpdate(claimAt, BATCH_SIZE).stream()
                        .map(FoodLogTaskEntity::getId)
                        .toList()
        );

        if (taskIds == null || taskIds.isEmpty()) {
            return;
        }

        for (String taskId : taskIds) {
            processOne(taskId);
        }
    }

    private void processOne(String taskId) {
        TaskExecution execution = txTemplate.execute(status -> prepareExecution(taskId));
        if (execution == null) {
            return;
        }

        try {
            // 外部 I/O：交易外執行
            ProviderClient client = router.pickStrict(execution.logEntity());
            var result = client.process(execution.logEntity(), storage);

            if (result == null || result.effective() == null) {
                throw new IllegalStateException("PROVIDER_RETURNED_EMPTY");
            }

            ObjectNode eff = result.effective().isObject()
                    ? ((ObjectNode) result.effective()).deepCopy()
                    : null;

            if (eff == null) {
                throw new IllegalStateException("PROVIDER_BAD_RESPONSE");
            }

            ObjectNode finalEff = postProcessor.apply(eff, result.provider(), execution.method());

            txTemplate.executeWithoutResult(status ->
                    applySuccess(execution.taskId(), execution.foodLogId(), result.provider(), finalEff)
            );

        } catch (Exception e) {
            log.warn("task failed: {}", execution.taskId(), e);

            ProviderErrorMapper.Mapped mapped = ProviderErrorMapper.map(e);

            txTemplate.executeWithoutResult(status ->
                    applyFailure(
                            execution.taskId(),
                            execution.foodLogId(),
                            execution.method(),
                            execution.attemptsAfterStart(),
                            mapped
                    )
            );
        }
    }

    /**
     * 短交易：
     * 1. 取 task/log
     * 2. 做 early-exit 檢查
     * 3. markRunning
     * 4. 回傳 detached logEntity 給交易外 process 使用
     */
    private TaskExecution prepareExecution(String taskId) {
        FoodLogTaskEntity task = taskRepo.findByIdForUpdate(taskId).orElse(null);
        if (task == null) {
            return null;
        }

        Instant now = clock.instant();

        // claim 與真正執行之間，重新確認仍是 runnable
        // ✅ 已關閉自動 retry：只允許 QUEUED，不允許 FAILED 再次進入執行
        if (task.getTaskStatus() != FoodLogTaskEntity.TaskStatus.QUEUED) {
            return null;
        }

        FoodLogEntity logEntity = logRepo.findByIdForUpdate(task.getFoodLogId()).orElse(null);
        if (logEntity == null) {
            task.markCancelled(now, "FOOD_LOG_NOT_FOUND", "food_log missing");
            taskRepo.save(task);
            return null;
        }

        // ===== early exit =====
        if (logEntity.getStatus() == FoodLogStatus.DELETED) {
            task.markCancelled(now, "LOG_DELETED", "food_log already deleted");
            taskRepo.save(task);
            return null;
        }

        if (logEntity.getStatus() == FoodLogStatus.DRAFT || logEntity.getStatus() == FoodLogStatus.SAVED) {
            task.markCancelled(now, "ALREADY_DONE", "food_log already processed");
            taskRepo.save(task);
            return null;
        }

        if (logEntity.getImageObjectKey() == null || logEntity.getImageObjectKey().isBlank()) {
            task.markCancelled(now, "IMAGE_OBJECT_KEY_MISSING", "missing imageObjectKey");
            taskRepo.save(task);

            logEntity.setStatus(FoodLogStatus.FAILED);
            logEntity.setLastErrorCode("IMAGE_OBJECT_KEY_MISSING");
            logEntity.setLastErrorMessage("missing imageObjectKey");
            logRepo.save(logEntity);
            return null;
        }

        int maxAttempts = maxAttemptsForMethod(logEntity.getMethod());
        if (task.getAttempts() >= maxAttempts) {
            task.markCancelled(now, "MAX_ATTEMPTS_EXCEEDED", "cancelled after max attempts");
            taskRepo.save(task);

            logEntity.setStatus(FoodLogStatus.FAILED);
            logEntity.setLastErrorCode("MAX_ATTEMPTS_EXCEEDED");
            logEntity.setLastErrorMessage("cancelled after max attempts");
            logRepo.save(logEntity);
            return null;
        }

        task.markRunning(now);
        taskRepo.save(task);

        return new TaskExecution(
                task.getId(),
                logEntity.getId(),
                logEntity.getMethod(),
                task.getAttempts(), // markRunning 後的 attempts
                logEntity
        );
    }

    /**
     * 短交易：成功結果回寫
     */
    private void applySuccess(String taskId, String foodLogId, String provider, ObjectNode finalEff) {
        FoodLogTaskEntity task = taskRepo.findByIdForUpdate(taskId).orElse(null);
        if (task == null) {
            return;
        }

        FoodLogEntity logEntity = logRepo.findByIdForUpdate(foodLogId).orElse(null);
        if (logEntity == null) {
            task.markCancelled(clock.instant(), "FOOD_LOG_NOT_FOUND", "food_log missing");
            taskRepo.save(task);
            return;
        }

        if (logEntity.getStatus() == FoodLogStatus.DELETED) {
            task.markCancelled(clock.instant(), "LOG_DELETED", "food_log already deleted");
            taskRepo.save(task);
            return;
        }

        if (logEntity.getStatus() == FoodLogStatus.DRAFT || logEntity.getStatus() == FoodLogStatus.SAVED) {
            task.markCancelled(clock.instant(), "ALREADY_DONE", "food_log already processed");
            taskRepo.save(task);
            return;
        }

        Instant doneAt = clock.instant();

        logEntity.setEffective(finalEff);
        logEntity.setProvider(provider);
        logEntity.setStatus(FoodLogStatus.DRAFT);
        logEntity.setLastErrorCode(null);
        logEntity.setLastErrorMessage(null);

        task.markSucceeded(doneAt);

        logRepo.save(logEntity);
        taskRepo.save(task);
    }

    private void applyFailure(
            String taskId,
            String foodLogId,
            String method,
            int attemptsAfterStart,
            ProviderErrorMapper.Mapped mapped
    ) {
        FoodLogTaskEntity task = taskRepo.findByIdForUpdate(taskId).orElse(null);
        if (task == null) {
            return;
        }

        FoodLogEntity logEntity = logRepo.findByIdForUpdate(foodLogId).orElse(null);
        if (logEntity == null) {
            task.markCancelled(clock.instant(), "FOOD_LOG_NOT_FOUND", "food_log missing");
            taskRepo.save(task);
            return;
        }

        if (logEntity.getStatus() == FoodLogStatus.DELETED) {
            task.markCancelled(clock.instant(), "LOG_DELETED", "food_log already deleted");
            taskRepo.save(task);
            return;
        }

        String mappedCode = mapped.code();
        String mappedMsg = mapped.message();
        Integer retryAfter = mapped.retryAfterSec();
        Instant failAt = clock.instant();

        // 429：保留特殊訊息，方便前端顯示 retryAfterSec，但不自動重試
        if ("PROVIDER_RATE_LIMITED".equals(mappedCode)) {
            String msg = buildRateLimitedMsg(mappedMsg, retryAfter, attemptsAfterStart);

            task.markCancelled(failAt, "PROVIDER_RATE_LIMITED", msg);
            taskRepo.save(task);

            logEntity.setStatus(FoodLogStatus.FAILED);
            logEntity.setLastErrorCode("PROVIDER_RATE_LIMITED");
            logEntity.setLastErrorMessage(msg);
            logRepo.save(logEntity);
            return;
        }

        // LABEL：單次失敗若是 PROVIDER_BAD_RESPONSE，直接降級成 NO_LABEL_DETECTED，不做 retry
        if ("LABEL".equalsIgnoreCase(method) && "PROVIDER_BAD_RESPONSE".equals(mappedCode)) {
            ObjectNode fallbackNoLabe = fallbackNoLabelDetectedEffective();
            ObjectNode finalEff = postProcessor.apply(fallbackNoLabe, "GEMINI", "LABEL");

            logEntity.setEffective(finalEff);
            logEntity.setProvider("GEMINI");
            logEntity.setStatus(FoodLogStatus.DRAFT);
            logEntity.setLastErrorCode(null);
            logEntity.setLastErrorMessage(null);

            task.markSucceeded(clock.instant());

            logRepo.save(logEntity);
            taskRepo.save(task);
            return;
        }

        // 其他失敗：直接結束，不做任何 retry
        task.markCancelled(failAt, mappedCode, mappedMsg);
        taskRepo.save(task);

        logEntity.setStatus(FoodLogStatus.FAILED);
        logEntity.setLastErrorCode(mappedCode);
        logEntity.setLastErrorMessage(mappedMsg);
        logRepo.save(logEntity);
    }

    private static int maxAttemptsForMethod(String method) {
        return 1;
    }

    private static ObjectNode fallbackNoLabelDetectedEffective() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.putNull("foodName");

        ObjectNode q = root.putObject("quantity");
        q.put("value", 1d);
        q.put("unit", "SERVING");

        ObjectNode n = root.putObject("nutrients");
        n.put("kcal", 0.0);
        n.put("protein", 0.0);
        n.put("fat", 0.0);
        n.put("carbs", 0.0);
        n.put("fiber", 0.0);
        n.put("sugar", 0.0);
        n.put("sodium", 0.0);

        root.putNull("confidence");
        root.putNull("healthScore");

        root.putArray("warnings")
                .add(FoodLogWarning.NO_LABEL_DETECTED.name())
                .add(FoodLogWarning.LOW_CONFIDENCE.name());

        ObjectNode lm = root.putObject("labelMeta");
        lm.putNull("servingsPerContainer");
        lm.putNull("basis");

        return root;
    }

    /**
     * 讓 FoodLogService.parseRetryAfterFromMessageOrNull 抽得到 suggestedRetryAfterSec=xx
     */
    private static String buildRateLimitedMsg(String mappedMsg, Integer retryAfterSec, int attempts) {
        String base = (mappedMsg == null || mappedMsg.isBlank()) ? "rate limited" : mappedMsg.trim();
        if (retryAfterSec != null) base = base + " suggestedRetryAfterSec=" + retryAfterSec;
        return base + " attempts=" + attempts;
    }

    private record TaskExecution(
            String taskId,
            String foodLogId,
            String method,
            int attemptsAfterStart,
            FoodLogEntity logEntity
    ) {}
}
