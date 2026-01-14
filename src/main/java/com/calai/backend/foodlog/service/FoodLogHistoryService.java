package com.calai.backend.foodlog.service;

import com.calai.backend.foodlog.dto.FoodLogEnvelope;
import com.calai.backend.foodlog.dto.FoodLogListResponse;
import com.calai.backend.foodlog.dto.FoodLogStatus;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;

@RequiredArgsConstructor
@Service
public class FoodLogHistoryService {

    private final FoodLogRepository logRepo;
    private final FoodLogService foodLogService; // 重用 getOne(toEnvelope)

    @Transactional
    public FoodLogEnvelope save(Long userId, String foodLogId, String requestId) {
        Instant now = Instant.now();

        FoodLogEntity log = logRepo.findByIdForUpdate(foodLogId);
        if (!userId.equals(log.getUserId())) throw new IllegalArgumentException("FOOD_LOG_NOT_FOUND");

        if (log.getStatus() == FoodLogStatus.DELETED) {
            throw new IllegalArgumentException("FOOD_LOG_DELETED");
        }

        // ✅ 冪等：已 SAVED 直接回
        if (log.getStatus() == FoodLogStatus.SAVED) {
            return foodLogService.getOne(userId, foodLogId, requestId);
        }

        if (log.getStatus() == FoodLogStatus.PENDING) {
            throw new IllegalArgumentException("FOOD_LOG_NOT_READY");
        }

        if (log.getStatus() == FoodLogStatus.FAILED) {
            throw new IllegalArgumentException("FOOD_LOG_FAILED");
        }

        if (log.getStatus() != FoodLogStatus.DRAFT) {
            throw new IllegalArgumentException("FOOD_LOG_NOT_SAVEABLE");
        }

        // ✅ 最小：DRAFT -> SAVED
        log.setStatus(FoodLogStatus.SAVED);
        logRepo.save(log);

        return foodLogService.getOne(userId, foodLogId, requestId);
    }

    @Transactional(readOnly = true)
    public FoodLogListResponse listSaved(
            Long userId,
            LocalDate fromLocalDate,
            LocalDate toLocalDate,
            int page,
            int size,
            String requestId
    ) {
        if (size <= 0) size = 20;
        if (size > 50) throw new IllegalArgumentException("PAGE_SIZE_TOO_LARGE");
        if (page < 0) page = 0;

        if (fromLocalDate == null || toLocalDate == null) {
            throw new IllegalArgumentException("DATE_RANGE_REQUIRED");
        }
        if (fromLocalDate.isAfter(toLocalDate)) {
            throw new IllegalArgumentException("DATE_RANGE_INVALID");
        }

        var pageable = PageRequest.of(page, size);
        var p = logRepo.findByUserIdAndStatusAndCapturedLocalDateRange(
                userId,
                FoodLogStatus.SAVED,
                fromLocalDate,
                toLocalDate,
                pageable
        );

        var items = p.getContent().stream()
                .map(this::toItem)
                .toList();

        return new FoodLogListResponse(
                items,
                new FoodLogListResponse.Page(p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages()),
                new FoodLogEnvelope.Trace(requestId)
        );
    }

    private FoodLogListResponse.Item toItem(FoodLogEntity e) {
        JsonNode eff = e.getEffective();
        String foodName = null;
        Double kcal = null, protein = null, fat = null, carbs = null;

        if (eff != null && !eff.isNull()) {
            foodName = textOrNull(eff, "foodName");
            JsonNode n = eff.get("nutrients");
            if (n != null && !n.isNull()) {
                kcal = doubleOrNull(n, "kcal");
                protein = doubleOrNull(n, "protein");
                fat = doubleOrNull(n, "fat");
                carbs = doubleOrNull(n, "carbs");
            }
        }

        return new FoodLogListResponse.Item(
                e.getId(),
                e.getStatus().name(),
                e.getCapturedLocalDate() == null ? null : e.getCapturedLocalDate().toString(),
                e.getCapturedAtUtc() == null ? null : e.getCapturedAtUtc().toString(),
                new FoodLogListResponse.Nutrition(foodName, kcal, protein, fat, carbs)
        );
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private static Double doubleOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asDouble();
    }
}
