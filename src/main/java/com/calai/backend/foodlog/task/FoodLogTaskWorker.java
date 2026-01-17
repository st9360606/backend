package com.calai.backend.foodlog.task;

import com.calai.backend.foodlog.dto.FoodLogStatus;
import com.calai.backend.foodlog.entity.FoodLogTaskEntity;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.repo.FoodLogTaskRepository;
import com.calai.backend.foodlog.storage.StorageService;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    private final ProviderRouter router;
    private final StorageService storage;
    private final EffectivePostProcessor postProcessor;

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
                task.markRunning(now);
                taskRepo.save(task);

                ProviderClient client = router.pickStrict(logEntity);
                var result = client.process(logEntity, storage);

                if (result == null || result.effective() == null) {
                    throw new IllegalStateException("PROVIDER_RETURNED_EMPTY");
                }
                // 需要 ObjectMapper 把 JsonNode 轉成 ObjectNode（避免 provider 回 Array/非 Object）
                ObjectNode eff = (result.effective() != null && result.effective().isObject())
                        ? ((ObjectNode) result.effective()).deepCopy()
                        : null;

                if (eff == null) throw new IllegalStateException("PROVIDER_BAD_RESPONSE");
                // ✅ 後處理：統一計分/降級/加 meta
                ObjectNode finalEff = postProcessor.apply(eff, result.provider());
                logEntity.setEffective(finalEff);
                logEntity.setProvider(result.provider());
                logEntity.setStatus(FoodLogStatus.DRAFT);
                logEntity.setLastErrorCode(null);
                logEntity.setLastErrorMessage(null);

                task.markSucceeded(now);

                logRepo.save(logEntity);
                taskRepo.save(task);

            } catch (Exception e) {
                log.warn("task failed: {}", task.getId(), e);

                ProviderErrorMapper.Mapped mapped = ProviderErrorMapper.map(e);
                String mappedCode = mapped.code();
                String mappedMsg = mapped.message();
                Integer retryAfter = mapped.retryAfterSec();

                // ✅ 1) 不可重試：直接取消
                if (isNonRetryable(mappedCode)) {
                    task.markCancelled(now, mappedCode, mappedMsg);
                    taskRepo.save(task);

                    logEntity.setStatus(FoodLogStatus.FAILED);
                    logEntity.setLastErrorCode(mappedCode);
                    logEntity.setLastErrorMessage(mappedMsg);
                    logRepo.save(logEntity);
                    continue;
                }

                // ✅ 2) 可重試：到上限就 GIVE UP
                if (task.getAttempts() >= TaskRetryPolicy.MAX_ATTEMPTS) {
                    String giveUpMsg = "[" + mappedCode + "] " + (mappedMsg == null ? "" : mappedMsg);
                    giveUpMsg = giveUpMsg.trim();

                    task.markCancelled(now, "PROVIDER_GIVE_UP", giveUpMsg);
                    taskRepo.save(task);

                    logEntity.setStatus(FoodLogStatus.FAILED);
                    logEntity.setLastErrorCode("PROVIDER_GIVE_UP");
                    logEntity.setLastErrorMessage(giveUpMsg);
                    logRepo.save(logEntity);
                    continue;
                }

                // ✅ 3) 未達上限 → 排程重試（429 時尊重 Retry-After）
                int delaySec = TaskRetryPolicy.nextDelaySec(task.getAttempts());
                if (retryAfter != null) delaySec = Math.max(delaySec, retryAfter);
                task.markFailed(now, mappedCode, mappedMsg, delaySec);
                taskRepo.save(task);

                logEntity.setStatus(FoodLogStatus.FAILED);
                logEntity.setLastErrorCode(mappedCode);
                logEntity.setLastErrorMessage(mappedMsg);
                logRepo.save(logEntity);
            }
        }
    }

    /**
     * ✅ 不可重試錯誤（配置/授權/請求格式問題）
     * - PROVIDER_NOT_CONFIGURED：程式/設定問題，重試也不會好
     * - PROVIDER_AUTH_FAILED：API key/token 錯，重試只會一直 401/403
     * - PROVIDER_BAD_REQUEST：你的 request 組裝錯/不符合 API，重試無效
     * 你之後可以再加：
     * - GEMINI_API_KEY_MISSING
     * - PROVIDER_BLOCKED (視策略：通常也不該重試)
     */
    private static boolean isNonRetryable(String code) {
        if (code == null || code.isBlank()) return false;
        return switch (code) {
            case "PROVIDER_NOT_CONFIGURED",
                 "PROVIDER_NOT_AVAILABLE",    // ✅ 新增：嚴格模式下 provider 不存在
                 "PROVIDER_AUTH_FAILED",
                 "PROVIDER_BAD_REQUEST",
                 "GEMINI_API_KEY_MISSING",
                 "PROVIDER_BLOCKED" -> true;
            default -> false;
        };
    }
}
