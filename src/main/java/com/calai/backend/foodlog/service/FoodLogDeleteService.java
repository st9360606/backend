package com.calai.backend.foodlog.service;

import com.calai.backend.foodlog.dto.FoodLogEnvelope;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.model.FoodLogErrorCode;
import com.calai.backend.foodlog.model.FoodLogStatus;
import com.calai.backend.foodlog.repo.DeletionJobRepository;
import com.calai.backend.foodlog.repo.FoodLogOverrideRepository;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.repo.FoodLogRequestRepository;
import com.calai.backend.foodlog.repo.FoodLogTaskRepository;
import com.calai.backend.foodlog.web.error.FoodLogAppException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@RequiredArgsConstructor
@Service
public class FoodLogDeleteService {

    private final FoodLogRepository logRepo;
    private final FoodLogTaskRepository taskRepo;
    private final FoodLogOverrideRepository overrideRepo;
    private final DeletionJobRepository deletionRepo;
    private final FoodLogRequestRepository requestRepo;
    private final ImageBlobService blobService;
    private final UserDailyNutritionSummaryService dailySummaryService;

    @Transactional
    public FoodLogEnvelope deleteOne(Long userId, String foodLogId, String requestId) {
        FoodLogEntity log = logRepo.findByIdForUpdate(foodLogId)
                .orElseThrow(() -> new FoodLogAppException(FoodLogErrorCode.FOOD_LOG_NOT_FOUND));

        if (!userId.equals(log.getUserId())) {
            throw new FoodLogAppException(FoodLogErrorCode.FOOD_LOG_NOT_FOUND);
        }

        String sha256 = trimToNull(log.getImageSha256());

        LocalDate localDate = log.getCapturedLocalDate();

        // 先刪 child rows，再刪 parent
        taskRepo.deleteByFoodLogId(foodLogId);
        overrideRepo.deleteByFoodLogId(foodLogId);
        deletionRepo.deleteByFoodLogId(foodLogId);
        requestRepo.deleteByFoodLogId(foodLogId);

        logRepo.delete(log);
        logRepo.flush();
        dailySummaryService.recomputeDay(userId, localDate);

        // blob 不能直接刪 objectKey，要走 ref_count release
        if (sha256 != null) {
            blobService.release(userId, sha256);
        }

        return new FoodLogEnvelope(
                foodLogId,
                FoodLogStatus.DELETED.name(),
                null, // degradeLevel
                null, // tierUsed
                false, // fromCache
                null, // portionMultiplier
                null, // updatedAtUtc
                null, // serverReceivedAtUtc
                null, // capturedAtUtc
                null, // capturedLocalDate
                null, // nutritionResult
                null, // task
                null, // error
                null, // hints
                new FoodLogEnvelope.Trace(requestId)
        );
    }

    private String trimToNull(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        return v.isEmpty() ? null : v;
    }
}
