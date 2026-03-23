package com.calai.backend.foodlog.service.command;

import com.calai.backend.entitlement.service.EntitlementService;
import com.calai.backend.foodlog.dto.FoodLogEnvelope;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.entity.FoodLogTaskEntity;
import com.calai.backend.foodlog.model.FoodLogErrorCode;
import com.calai.backend.foodlog.model.FoodLogStatus;
import com.calai.backend.foodlog.quota.guard.AbuseGuardService;
import com.calai.backend.foodlog.quota.model.ModelTier;
import com.calai.backend.foodlog.quota.service.QuotaService;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.repo.FoodLogTaskRepository;
import com.calai.backend.foodlog.service.limiter.UserRateLimiter;
import com.calai.backend.foodlog.service.support.FoodLogEnvelopeAssembler;
import com.calai.backend.foodlog.service.support.FoodLogRequestNormalizer;
import com.calai.backend.foodlog.web.error.FoodLogAppException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;

/**
 * 專門負責 food log retry 流程。
 *
 * 這層只做：
 * - 驗證是否可 retry
 * - 執行 precheck（tier / rate limit / abuse guard / quota）
 * - 重置 log / task
 * - 回傳 envelope
 */
@Service
@RequiredArgsConstructor
public class FoodLogRetryService {

    private final FoodLogRepository repo;
    private final FoodLogTaskRepository taskRepo;
    private final EntitlementService entitlementService;
    private final UserRateLimiter rateLimiter;
    private final AbuseGuardService abuseGuard;
    private final QuotaService quota;
    private final FoodLogEnvelopeAssembler envelopeAssembler;
    private final Clock clock;

    @Transactional
    public FoodLogEnvelope retry(
            Long userId,
            String foodLogId,
            String deviceId,
            String requestId
    ) {
        FoodLogEntity log = repo.findByIdForUpdate(foodLogId)
                .orElseThrow(() -> new FoodLogAppException(FoodLogErrorCode.FOOD_LOG_NOT_FOUND));

        if (!userId.equals(log.getUserId())) {
            throw new FoodLogAppException(FoodLogErrorCode.FOOD_LOG_NOT_FOUND);
        }

        if (log.getStatus() == FoodLogStatus.DELETED) {
            throw new FoodLogAppException(FoodLogErrorCode.FOOD_LOG_DELETED);
        }

        String method = log.getMethod() == null ? "" : log.getMethod().trim().toUpperCase(Locale.ROOT);
        boolean retryableMethod =
                "PHOTO".equals(method)
                || "ALBUM".equals(method)
                || "LABEL".equals(method);

        if (!retryableMethod || log.getStatus() != FoodLogStatus.FAILED) {
            throw new FoodLogAppException(FoodLogErrorCode.FOOD_LOG_NOT_RETRYABLE);
        }

        Instant now = clock.instant();
        ZoneId quotaTz = FoodLogRequestNormalizer.resolveQuotaTz();
        String did = FoodLogRequestNormalizer.normalizeDeviceId(userId, deviceId);

        // precheck 失敗時不得先 save
        EntitlementService.Tier tier = entitlementService.resolveTier(userId, now);
        rateLimiter.checkOrThrow(userId, tier, now);
        abuseGuard.onOperationAttempt(userId, did, false, now, quotaTz);

        QuotaService.Decision decision =
                quota.consumeOperationOrThrow(userId, tier, quotaTz, now);

        log.setStatus(FoodLogStatus.PENDING);
        log.setEffective(null);
        log.setLastErrorCode(null);
        log.setLastErrorMessage(null);
        log.setDegradeLevel(
                decision.tierUsed() == ModelTier.MODEL_TIER_HIGH ? "DG-0" : "DG-2"
        );

        FoodLogTaskEntity task = taskRepo.findByFoodLogIdForUpdate(foodLogId)
                .orElseGet(() -> {
                    FoodLogTaskEntity t = new FoodLogTaskEntity();
                    t.setFoodLogId(foodLogId);
                    return t;
                });

        task.setTaskStatus(FoodLogTaskEntity.TaskStatus.QUEUED);
        task.setNextRetryAtUtc(null);
        task.setPollAfterSec(2);
        task.setAttempts(0);
        task.setLastErrorCode(null);
        task.setLastErrorMessage(null);

        taskRepo.save(task);
        repo.save(log);

        return envelopeAssembler.assemble(log, task, requestId);
    }
}
