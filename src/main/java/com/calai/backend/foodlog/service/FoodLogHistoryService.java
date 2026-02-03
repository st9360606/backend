package com.calai.backend.foodlog.service;

import com.calai.backend.foodlog.dto.FoodLogEnvelope;
import com.calai.backend.foodlog.dto.FoodLogListResponse;
import com.calai.backend.foodlog.model.FoodLogStatus;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.task.FoodLogWarning;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.time.LocalDate;
import java.util.Locale;

@RequiredArgsConstructor
@Service
public class FoodLogHistoryService {

    private final FoodLogRepository logRepo;
    private final FoodLogService foodLogService;

    @Transactional
    public FoodLogEnvelope save(Long userId, String foodLogId, String requestId) {
        FoodLogEntity log = logRepo.findByIdForUpdate(foodLogId);
        if (!userId.equals(log.getUserId())) throw new IllegalArgumentException("FOOD_LOG_NOT_FOUND");

        if (log.getStatus() == FoodLogStatus.DELETED) throw new IllegalArgumentException("FOOD_LOG_DELETED");
        if (log.getStatus() == FoodLogStatus.SAVED) return foodLogService.getOne(userId, foodLogId, requestId);
        if (log.getStatus() == FoodLogStatus.PENDING) throw new IllegalArgumentException("FOOD_LOG_NOT_READY");
        if (log.getStatus() == FoodLogStatus.FAILED) throw new IllegalArgumentException("FOOD_LOG_FAILED");
        if (log.getStatus() != FoodLogStatus.DRAFT) throw new IllegalArgumentException("FOOD_LOG_NOT_SAVABLE");

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
        return listByStatus(userId, "SAVED", fromLocalDate, toLocalDate, page, size, requestId);
    }

    @Transactional(readOnly = true)
    public FoodLogListResponse listByStatus(
            Long userId,
            String statusRaw,
            LocalDate fromLocalDate,
            LocalDate toLocalDate,
            int page,
            int size,
            String requestId
    ) {
        FoodLogStatus status = parseStatusOrThrow(statusRaw);

        if (size <= 0) size = 20;
        if (size > 50) throw new IllegalArgumentException("PAGE_SIZE_TOO_LARGE");
        if (page < 0) page = 0;

        if (fromLocalDate == null || toLocalDate == null) throw new IllegalArgumentException("DATE_RANGE_REQUIRED");
        if (fromLocalDate.isAfter(toLocalDate)) throw new IllegalArgumentException("DATE_RANGE_INVALID");

        var pageable = PageRequest.of(page, size);
        var p = logRepo.findByUserIdAndStatusAndCapturedLocalDateRange(userId, status, fromLocalDate, toLocalDate, pageable);

        var items = p.getContent().stream().map(this::toItem).toList();

        return new FoodLogListResponse(
                items,
                new FoodLogListResponse.Page(p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages()),
                new FoodLogEnvelope.Trace(requestId)
        );
    }

    private static FoodLogStatus parseStatusOrThrow(String raw) {
        if (raw == null) throw new IllegalArgumentException("BAD_REQUEST");
        String v = raw.trim().toUpperCase(Locale.ROOT);
        try {
            return FoodLogStatus.valueOf(v);
        } catch (Exception e) {
            throw new IllegalArgumentException("BAD_REQUEST");
        }
    }

    private FoodLogListResponse.Item toItem(FoodLogEntity e) {
        JsonNode eff = e.getEffective();

        String foodName = null;

        Double kcal = null, protein = null, fat = null, carbs = null, fiber = null, sugar = null, sodium = null;
        Integer healthScore = null;
        Double confidence = null;

        List<String> warnings = null;
        String degradedReason = null;

        if (eff != null && eff.isObject()) {
            foodName = textOrNull(eff.get("foodName"));
            healthScore = intOrNull(eff.get("healthScore"));
            confidence = doubleOrNull(eff.get("confidence"));

            JsonNode n = eff.get("nutrients");
            if (n != null && n.isObject()) {
                kcal = doubleOrNull(n.get("kcal"));
                protein = doubleOrNull(n.get("protein"));
                fat = doubleOrNull(n.get("fat"));
                carbs = doubleOrNull(n.get("carbs"));
                fiber = doubleOrNull(n.get("fiber"));
                sugar = doubleOrNull(n.get("sugar"));
                sodium = doubleOrNull(n.get("sodium"));
            }

            // warnings（array -> List<String>）只回白名單
            JsonNode w = eff.get("warnings");
            if (w != null && w.isArray()) {
                warnings = new java.util.ArrayList<>();
                for (JsonNode it : w) {
                    if (it == null || it.isNull()) continue;
                    FoodLogWarning ww = FoodLogWarning.parseOrNull(it.asText());
                    if (ww != null) warnings.add(ww.name());
                }
                if (warnings.isEmpty()) warnings = null;
            }

            // degradedReason：優先讀 aiMeta.degradedReason；沒有就 fallback 看 warnings
            JsonNode aiMeta = eff.get("aiMeta");
            if (aiMeta != null && aiMeta.isObject()) {
                degradedReason = textOrNull(aiMeta.get("degradedReason"));
            }
            if (degradedReason == null && warnings != null) {
                if (warnings.contains("NO_FOOD_DETECTED")) degradedReason = "NO_FOOD";
                else if (warnings.contains("UNKNOWN_FOOD")) degradedReason = "UNKNOWN_FOOD";
            }
        }

        return new FoodLogListResponse.Item(
                e.getId(),
                e.getStatus().name(),
                e.getCapturedLocalDate() == null ? null : e.getCapturedLocalDate().toString(),
                e.getCapturedAtUtc() == null ? null : e.getCapturedAtUtc().toString(),
                new FoodLogListResponse.Nutrition(
                        foodName,
                        kcal,
                        protein,
                        fat,
                        carbs,
                        fiber,
                        sugar,
                        sodium,
                        healthScore,
                        confidence,
                        warnings,
                        degradedReason
                )
        );
    }

    private static String textOrNull(JsonNode v) {
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private static Double doubleOrNull(JsonNode v) {
        return (v == null || v.isNull() || !v.isNumber()) ? null : v.asDouble();
    }

    private static Integer intOrNull(JsonNode v) {
        return (v == null || v.isNull() || !v.isIntegralNumber()) ? null : v.asInt();
    }
}
