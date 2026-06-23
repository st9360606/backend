package com.caloshape.backend.foodlog.service.query;

import com.caloshape.backend.foodlog.dto.FoodLogEnvelope;
import com.caloshape.backend.foodlog.entity.FoodLogEntity;
import com.caloshape.backend.foodlog.entity.FoodLogTaskEntity;
import com.caloshape.backend.foodlog.model.FoodLogErrorCode;
import com.caloshape.backend.foodlog.model.FoodLogStatus;
import com.caloshape.backend.foodlog.model.ProviderRefuseReason;
import com.caloshape.backend.foodlog.repo.FoodLogRepository;
import com.caloshape.backend.foodlog.repo.FoodLogTaskRepository;
import com.caloshape.backend.foodlog.service.support.FoodLogEnvelopeAssembler;
import com.caloshape.backend.foodlog.web.error.FoodLogAppException;
import com.caloshape.backend.foodlog.web.error.ModelRefusedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 專門負責 food log 查詢與 envelope 組裝。
 *
 * 這層只做：
 * - 讀取 FoodLogEntity
 * - 決定是否附帶 task
 * - provider refused 轉成 422 例外
 * - 呼叫 assembler 組裝 response
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FoodLogQueryService {

    private final FoodLogRepository repo;
    private final FoodLogTaskRepository taskRepo;
    private final FoodLogEnvelopeAssembler envelopeAssembler;

    @Transactional(readOnly = true)
    public FoodLogEnvelope getOne(Long userId, String id, String requestId) {
        FoodLogEntity e = repo.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new FoodLogAppException(FoodLogErrorCode.FOOD_LOG_NOT_FOUND));

        ProviderRefuseReason reason = ProviderRefuseReason.fromErrorCodeOrNull(e.getLastErrorCode());
        if (reason != null) {
            log.warn("food_log_provider_refused foodLogId={} reason={} code={} msg={}",
                    e.getId(),
                    reason,
                    e.getLastErrorCode(),
                    e.getLastErrorMessage());

            throw new ModelRefusedException(reason, e.getLastErrorCode());
        }

        FoodLogTaskEntity t = null;
        if (e.getStatus() == FoodLogStatus.PENDING || e.getStatus() == FoodLogStatus.FAILED) {
            FoodLogTaskEntity tmp = taskRepo.findByFoodLogId(e.getId()).orElse(null);

            if (tmp != null) {
                var ts = tmp.getTaskStatus();
                if (ts == FoodLogTaskEntity.TaskStatus.QUEUED
                    || ts == FoodLogTaskEntity.TaskStatus.RUNNING
                    || ts == FoodLogTaskEntity.TaskStatus.FAILED) {
                    t = tmp;
                }
            }
        }

        return envelopeAssembler.assemble(e, t, requestId);
    }
}
