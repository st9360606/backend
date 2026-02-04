package com.calai.backend.foodlog.task;

import com.calai.backend.foodlog.model.FoodLogStatus;
import com.calai.backend.foodlog.entity.FoodLogTaskEntity;
import com.calai.backend.foodlog.mapper.ProviderErrorMapper;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.repo.FoodLogTaskRepository;
import com.calai.backend.foodlog.storage.StorageService;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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
        // ✅ 僅用於「claim 任務」；不要拿這個時間當作 retry/狀態的時間點
        Instant claimAt = Instant.now();
        List<FoodLogTaskEntity> tasks = taskRepo.claimRunnableForUpdate(claimAt, BATCH_SIZE);

        for (FoodLogTaskEntity task : tasks) {
            var logEntity = logRepo.findByIdForUpdate(task.getFoodLogId());

            // ===== early exit =====
            if (logEntity.getStatus() == FoodLogStatus.DELETED) {
                task.markCancelled(Instant.now(), "LOG_DELETED", "food_log already deleted");
                taskRepo.save(task);
                continue;
            }

            if (logEntity.getStatus() == FoodLogStatus.DRAFT || logEntity.getStatus() == FoodLogStatus.SAVED) {
                task.markCancelled(Instant.now(), "ALREADY_DONE", "food_log already processed");
                taskRepo.save(task);
                continue;
            }

            if (logEntity.getImageObjectKey() == null || logEntity.getImageObjectKey().isBlank()) {
                Instant failAt = Instant.now();
                task.markCancelled(failAt, "IMAGE_OBJECT_KEY_MISSING", "missing imageObjectKey");
                taskRepo.save(task);

                logEntity.setStatus(FoodLogStatus.FAILED);
                logEntity.setLastErrorCode("IMAGE_OBJECT_KEY_MISSING");
                logEntity.setLastErrorMessage("missing imageObjectKey");
                logRepo.save(logEntity);
                continue;
            }

            int maxAttempts = maxAttemptsForMethod(logEntity.getMethod());

            if (task.getAttempts() >= maxAttempts) {
                Instant failAt = Instant.now();
                task.markCancelled(failAt, "MAX_ATTEMPTS_EXCEEDED", "cancelled after max attempts");
                taskRepo.save(task);

                logEntity.setStatus(FoodLogStatus.FAILED);
                logEntity.setLastErrorCode("MAX_ATTEMPTS_EXCEEDED");
                logEntity.setLastErrorMessage("cancelled after max attempts");
                logRepo.save(logEntity);
                continue;
            }

            try {
                // ✅ RUNNING：用「當下」最準（attempts 會 +1）
                Instant startAt = Instant.now();
                task.markRunning(startAt);
                taskRepo.save(task);

                ProviderClient client = router.pickStrict(logEntity);
                var result = client.process(logEntity, storage);

                if (result == null || result.effective() == null) {
                    throw new IllegalStateException("PROVIDER_RETURNED_EMPTY");
                }

                ObjectNode eff = (result.effective().isObject())
                        ? ((ObjectNode) result.effective()).deepCopy()
                        : null;

                if (eff == null) throw new IllegalStateException("PROVIDER_BAD_RESPONSE");

                Instant doneAt = Instant.now();

                ObjectNode finalEff = postProcessor.apply(eff, result.provider(), logEntity.getMethod());
                logEntity.setEffective(finalEff);
                logEntity.setProvider(result.provider());
                logEntity.setStatus(FoodLogStatus.DRAFT);
                logEntity.setLastErrorCode(null);
                logEntity.setLastErrorMessage(null);

                task.markSucceeded(doneAt);

                logRepo.save(logEntity);
                taskRepo.save(task);

            } catch (Exception e) {
                log.warn("task failed: {}", task.getId(), e);

                ProviderErrorMapper.Mapped mapped = ProviderErrorMapper.map(e);
                String mappedCode = mapped.code();
                String mappedMsg = mapped.message();
                Integer retryAfter = mapped.retryAfterSec();

                // ✅ failAt 一定用「當下」(避免 latency 吃掉 retryAfter)
                Instant failAt = Instant.now();

                // ✅ 429：一律不自動重試（避免上線羊群效應）
                if ("PROVIDER_RATE_LIMITED".equals(mappedCode)) {
                    String msg = buildRateLimitedMsg(mappedMsg, retryAfter, task.getAttempts());

                    task.markCancelled(failAt, "PROVIDER_RATE_LIMITED", msg);
                    taskRepo.save(task);

                    logEntity.setStatus(FoodLogStatus.FAILED);
                    logEntity.setLastErrorCode("PROVIDER_RATE_LIMITED");
                    logEntity.setLastErrorMessage(msg);
                    logRepo.save(logEntity);
                    continue;
                }

                // ✅ LABEL：最後一次仍 PROVIDER_BAD_RESPONSE → 不要留 FAILED，直接降級成 DRAFT + NO_LABEL_DETECTED
                if ("LABEL".equalsIgnoreCase(logEntity.getMethod())
                    && "PROVIDER_BAD_RESPONSE".equals(mappedCode)
                    && task.getAttempts() >= maxAttempts) {

                    ObjectNode fb = fallbackNoLabelDetectedEffective();
                    ObjectNode finalEff = postProcessor.apply(fb, "GEMINI", "LABEL");

                    logEntity.setEffective(finalEff);
                    logEntity.setProvider("GEMINI");
                    logEntity.setStatus(FoodLogStatus.DRAFT);
                    logEntity.setLastErrorCode(null);
                    logEntity.setLastErrorMessage(null);

                    Instant doneAt = Instant.now();
                    task.markSucceeded(doneAt);

                    logRepo.save(logEntity);
                    taskRepo.save(task);
                    continue;
                }


                // ✅ BAD_RESPONSE：最多重試 1 次（attempts>=2 直接停）
                if ("PROVIDER_BAD_RESPONSE".equals(mappedCode) && task.getAttempts() >= 2) {
                    String msg = "bad json from provider (give up) attempts=" + task.getAttempts();

                    task.markCancelled(failAt, "PROVIDER_BAD_RESPONSE", msg);
                    taskRepo.save(task);

                    logEntity.setStatus(FoodLogStatus.FAILED);
                    logEntity.setLastErrorCode("PROVIDER_BAD_RESPONSE");
                    logEntity.setLastErrorMessage(msg);
                    logRepo.save(logEntity);
                    continue;
                }

                // ✅ 不可重試：直接取消
                if (isNonRetryable(mappedCode)) {
                    task.markCancelled(failAt, mappedCode, mappedMsg);
                    taskRepo.save(task);

                    logEntity.setStatus(FoodLogStatus.FAILED);
                    logEntity.setLastErrorCode(mappedCode);
                    logEntity.setLastErrorMessage(mappedMsg);
                    logRepo.save(logEntity);
                    continue;
                }

                // ✅ 達到上限：give up
                if (task.getAttempts() >= maxAttempts) {
                    String giveUpMsg = ("[" + mappedCode + "] " + (mappedMsg == null ? "" : mappedMsg)).trim();

                    task.markCancelled(failAt, "PROVIDER_GIVE_UP", giveUpMsg);
                    taskRepo.save(task);

                    logEntity.setStatus(FoodLogStatus.FAILED);
                    logEntity.setLastErrorCode("PROVIDER_GIVE_UP");
                    logEntity.setLastErrorMessage(giveUpMsg);
                    logRepo.save(logEntity);
                    continue;
                }

                // ✅ 可重試：用 failAt 計算 nextRetryAt
                int delaySec = TaskRetryPolicy.nextDelaySec(task.getAttempts());
                if (retryAfter != null) delaySec = Math.max(delaySec, retryAfter);

                task.markFailed(failAt, mappedCode, mappedMsg, delaySec);
                taskRepo.save(task);

                logEntity.setStatus(FoodLogStatus.FAILED);
                logEntity.setLastErrorCode(mappedCode);
                logEntity.setLastErrorMessage(mappedMsg);
                logRepo.save(logEntity);
            }
        }
    }

    private static int maxAttemptsForMethod(String method) {
        if (method == null) return TaskRetryPolicy.MAX_ATTEMPTS;
        if ("LABEL".equalsIgnoreCase(method)) return 2; // ✅ 最多重試一次
        return TaskRetryPolicy.MAX_ATTEMPTS;
    }

    private static ObjectNode fallbackNoLabelDetectedEffective() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.putNull("foodName");

        ObjectNode q = root.putObject("quantity");
        q.put("value", 1d);
        q.put("unit", "SERVING");

        ObjectNode n = root.putObject("nutrients");
        n.putNull("kcal");
        n.putNull("protein");
        n.putNull("fat");
        n.putNull("carbs");
        n.putNull("fiber");
        n.putNull("sugar");
        n.putNull("sodium");

        root.put("confidence", 0.1);
        root.putArray("warnings").add(FoodLogWarning.NO_LABEL_DETECTED.name());
        return root;
    }

    /**
     * ✅ 讓 FoodLogService.parseRetryAfterFromMessageOrNull 抽得到 suggestedRetryAfterSec=xx
     */
    private static String buildRateLimitedMsg(String mappedMsg, Integer retryAfterSec, int attempts) {
        String base = (mappedMsg == null || mappedMsg.isBlank()) ? "rate limited" : mappedMsg.trim();
        if (retryAfterSec != null) base = base + " suggestedRetryAfterSec=" + retryAfterSec;
        return base + " attempts=" + attempts;
    }

    private static boolean isNonRetryable(String code) {
        if (code == null || code.isBlank()) return false;
        // SAFETY / RECITATION / HARM_CATEGORY 一律不重試
        if (code.startsWith("PROVIDER_REFUSED_")) return true;
        return switch (code) {
            case "PROVIDER_NOT_CONFIGURED",
                 "PROVIDER_NOT_AVAILABLE",
                 "PROVIDER_AUTH_FAILED",
                 "PROVIDER_BAD_REQUEST",
                 "GEMINI_API_KEY_MISSING",
                 "PROVIDER_BLOCKED" -> true;
            default -> false;
        };
    }
}
