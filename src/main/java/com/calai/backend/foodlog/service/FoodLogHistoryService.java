package com.calai.backend.foodlog.service;

import com.calai.backend.foodlog.barcode.normalize.BarcodeNutrientsNormalizer;
import com.calai.backend.foodlog.dto.FoodLogEnvelope;
import com.calai.backend.foodlog.dto.FoodLogListResponse;
import com.calai.backend.foodlog.mapper.FoodLogDisplayNameResolver;
import com.calai.backend.foodlog.model.FoodLogErrorCode;
import com.calai.backend.foodlog.model.FoodLogMethod;
import com.calai.backend.foodlog.model.FoodLogStatus;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.service.support.FoodLogEffectiveViewSupport;
import com.calai.backend.foodlog.unit.FoodLogWarning;
import com.calai.backend.foodlog.web.error.FoodLogAppException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

// NOTE:
// warnings parsing / degradedReason canonicalization 目前暫不抽共用。
// 原因：EnvelopeAssembler 與 HistoryService 在非 LABEL 情境下的既有行為不完全一致，
// 先保留原邏輯以避免改動已通過測試的 response 細節。
@RequiredArgsConstructor
@Service
public class FoodLogHistoryService {

    private final FoodLogRepository logRepo;
    private final FoodLogService foodLogService;
    private final Clock clock;

    @Transactional
    public FoodLogEnvelope save(Long userId, String foodLogId, String requestId) {
        FoodLogEntity log = logRepo.findByIdForUpdate(foodLogId)
                .orElseThrow(() -> new FoodLogAppException(FoodLogErrorCode.FOOD_LOG_NOT_FOUND));
        if (!userId.equals(log.getUserId())) {
            throw new FoodLogAppException(FoodLogErrorCode.FOOD_LOG_NOT_FOUND);
        }

        if (log.getStatus() == FoodLogStatus.DELETED) {
            throw new FoodLogAppException(FoodLogErrorCode.FOOD_LOG_DELETED);
        }
        if (log.getStatus() == FoodLogStatus.SAVED) {
            return foodLogService.getOne(userId, foodLogId, requestId);
        }
        if (log.getStatus() == FoodLogStatus.PENDING) {
            throw new FoodLogAppException(FoodLogErrorCode.FOOD_LOG_NOT_READY);
        }
        if (log.getStatus() == FoodLogStatus.FAILED) {
            throw new FoodLogAppException(FoodLogErrorCode.FOOD_LOG_FAILED);
        }
        if (log.getStatus() != FoodLogStatus.DRAFT) {
            throw new FoodLogAppException(FoodLogErrorCode.FOOD_LOG_NOT_SAVABLE);
        }

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

    @Transactional
    public FoodLogEnvelope unsave(Long userId, String foodLogId, String requestId) {
        FoodLogEntity log = logRepo.findByIdForUpdate(foodLogId)
                .orElseThrow(() -> new FoodLogAppException(FoodLogErrorCode.FOOD_LOG_NOT_FOUND));

        if (!userId.equals(log.getUserId())) {
            throw new FoodLogAppException(FoodLogErrorCode.FOOD_LOG_NOT_FOUND);
        }

        if (log.getStatus() == FoodLogStatus.DELETED) {
            throw new FoodLogAppException(FoodLogErrorCode.FOOD_LOG_DELETED);
        }
        if (log.getStatus() == FoodLogStatus.DRAFT) {
            return foodLogService.getOne(userId, foodLogId, requestId);
        }
        if (log.getStatus() == FoodLogStatus.PENDING) {
            throw new FoodLogAppException(FoodLogErrorCode.FOOD_LOG_NOT_READY);
        }
        if (log.getStatus() == FoodLogStatus.FAILED) {
            throw new FoodLogAppException(FoodLogErrorCode.FOOD_LOG_FAILED);
        }
        if (log.getStatus() != FoodLogStatus.SAVED) {
            throw new FoodLogAppException(FoodLogErrorCode.FOOD_LOG_NOT_SAVABLE);
        }

        log.setStatus(FoodLogStatus.DRAFT);
        logRepo.save(log);
        return foodLogService.getOne(userId, foodLogId, requestId);
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
        if (size > 50) throw new FoodLogAppException(FoodLogErrorCode.PAGE_SIZE_TOO_LARGE);
        if (page < 0) page = 0;

        if (fromLocalDate == null || toLocalDate == null) {
            throw new FoodLogAppException(FoodLogErrorCode.DATE_RANGE_REQUIRED);
        }
        if (fromLocalDate.isAfter(toLocalDate)) {
            throw new FoodLogAppException(FoodLogErrorCode.DATE_RANGE_INVALID);
        }

        var pageable = PageRequest.of(page, size);
        var p = logRepo.findByUserIdAndStatusAndCapturedLocalDateRange(
                userId, status, fromLocalDate, toLocalDate, pageable
        );

        var items = p.getContent().stream().map(this::toItem).toList();

        return new FoodLogListResponse(
                items,
                new FoodLogListResponse.Page(
                        p.getNumber(),
                        p.getSize(),
                        p.getTotalElements(),
                        p.getTotalPages()
                ),
                new FoodLogEnvelope.Trace(requestId)
        );
    }

    private static FoodLogStatus parseStatusOrThrow(String raw) {
        if (raw == null) {
            throw new FoodLogAppException(FoodLogErrorCode.BAD_REQUEST);
        }
        String v = raw.trim().toUpperCase(Locale.ROOT);
        try {
            return FoodLogStatus.valueOf(v);
        } catch (Exception e) {
            throw new FoodLogAppException(FoodLogErrorCode.BAD_REQUEST);
        }
    }

    @Transactional(readOnly = true)
    public FoodLogListResponse listSavedRecent(
            Long userId,
            int lookBackDays,
            int size,
            String requestId
    ) {
        if (lookBackDays <= 0) lookBackDays = 15;
        if (size <= 0) size = 20;
        if (size > 100) size = 100;

        Instant fromUtc = Instant.now(clock).minus(Duration.ofDays(lookBackDays));

        List<FoodLogEntity> rows = logRepo.findSavedRecentByServerReceivedAtUtc(
                userId,
                fromUtc,
                size
        );

        var items = rows.stream()
                .map(this::toItem)
                .toList();

        return new FoodLogListResponse(
                items,
                new FoodLogListResponse.Page(
                        0,
                        size,
                        items.size(),
                        1
                ),
                new FoodLogEnvelope.Trace(requestId)
        );
    }

    private FoodLogListResponse.Item toItem(FoodLogEntity e) {
        JsonNode eff = e.getEffective();
        FoodLogMethod method = FoodLogMethod.from(e.getMethod());

        String foodName = null;

        Double kcal = null, protein = null, fat = null, carbs = null, fiber = null, sugar = null, sodium = null;
        Integer healthScore = null;
        Double confidence = null;

        List<String> warnings = null;
        String degradedReason = null;
        String foodCategory = null;
        String foodSubCategory = null;
        String reasoning = null;
        FoodLogEnvelope.LabelMeta labelMeta = null;
        FoodLogEnvelope.AiMetaView aiMeta = null;

        if (eff != null && eff.isObject()) {
            foodName = textOrNull(eff.get("foodName"));
            healthScore = intOrNull(eff.get("healthScore"));
            confidence = doubleOrNull(eff.get("confidence"));
            reasoning = textOrNull(eff.get("_reasoning"));

            JsonNode n = eff.get("nutrients");
            boolean barcodeZeroFill = method != null && method.isBarcode();

            if (barcodeZeroFill) {
                // ✅ BARCODE：即使 nutrients 不存在，也補全 0.0
                kcal = BarcodeNutrientsNormalizer.readNumber(n, "kcal", true);
                protein = BarcodeNutrientsNormalizer.readNumber(n, "protein", true);
                fat = BarcodeNutrientsNormalizer.readNumber(n, "fat", true);
                carbs = BarcodeNutrientsNormalizer.readNumber(n, "carbs", true);
                fiber = BarcodeNutrientsNormalizer.readNumber(n, "fiber", true);
                sugar = BarcodeNutrientsNormalizer.readNumber(n, "sugar", true);
                sodium = BarcodeNutrientsNormalizer.readNumber(n, "sodium", true);
            } else if (n != null && n.isObject()) {
                // ✅ 非 BARCODE：維持原本行為
                kcal = doubleOrNull(n.get("kcal"));
                protein = doubleOrNull(n.get("protein"));
                fat = doubleOrNull(n.get("fat"));
                carbs = doubleOrNull(n.get("carbs"));
                fiber = doubleOrNull(n.get("fiber"));
                sugar = doubleOrNull(n.get("sugar"));
                sodium = doubleOrNull(n.get("sodium"));
            }

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

            labelMeta = FoodLogEffectiveViewSupport.toLabelMeta(eff.get("labelMeta"));

            JsonNode aiMetaNode = eff.get("aiMeta");
            if (aiMetaNode != null && aiMetaNode.isObject()) {
                aiMeta = FoodLogEffectiveViewSupport.toAiMetaView(aiMetaNode);
                degradedReason = textOrNull(aiMetaNode.get("degradedReason"));
                foodCategory = textOrNull(aiMetaNode.get("foodCategory"));
                foodSubCategory = textOrNull(aiMetaNode.get("foodSubCategory"));
            }

            if (warnings != null) {
                if (method != null && method.isLabel()) {
                    // LABEL 模式下，允許用 warnings 覆蓋掉 aiMeta 先前寫錯的 NO_FOOD
                    if (warnings.contains("NO_LABEL_DETECTED")) {
                        degradedReason = "NO_LABEL";
                    } else if (warnings.contains("MISSING_NUTRITION_FACTS")) {
                        degradedReason = "MISSING_NUTRITION_FACTS";
                    } else if (warnings.contains("NO_FOOD_DETECTED")) {
                        degradedReason = "NO_LABEL";
                    } else if (warnings.contains("UNKNOWN_FOOD")) {
                        degradedReason = "UNKNOWN_FOOD";
                    }
                } else if (degradedReason == null) {
                    if (warnings.contains("NO_FOOD_DETECTED")) {
                        degradedReason = "NO_FOOD";
                    } else if (warnings.contains("UNKNOWN_FOOD")) {
                        degradedReason = "UNKNOWN_FOOD";
                    } else if (warnings.contains("NO_LABEL_DETECTED")) {
                        degradedReason = "NO_LABEL";
                    } else if (warnings.contains("MISSING_NUTRITION_FACTS")) {
                        degradedReason = "MISSING_NUTRITION_FACTS";
                    }
                }
            }

            labelMeta = FoodLogEffectiveViewSupport.normalizeLabelMetaForDisplay(
                    degradedReason,
                    warnings,
                    labelMeta
            );

            aiMeta = FoodLogEffectiveViewSupport.overrideAiMetaDegradedReason(
                    aiMeta,
                    degradedReason
            );

            // ✅ 最終顯示名稱：與 toEnvelope() 保持一致
            foodName = FoodLogDisplayNameResolver.resolve(e.getMethod(), degradedReason, eff);
        }

        return new FoodLogListResponse.Item(
                e.getId(),
                e.getStatus().name(),
                e.getCapturedLocalDate() == null ? null : e.getCapturedLocalDate().toString(),
                e.getCapturedAtUtc() == null ? null : e.getCapturedAtUtc().toString(),
                e.getServerReceivedAtUtc() == null ? null : e.getServerReceivedAtUtc().toString(),
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
                        degradedReason,
                        foodCategory,
                        foodSubCategory,
                        reasoning,
                        labelMeta,
                        aiMeta
                )
        );
    }

    @Transactional(readOnly = true)
    public FoodLogListResponse listRecentPreviews(
            Long userId,
            int lookBackHours,
            int size,
            String requestId
    ) {
        int safeHours = Math.max(1, Math.min(lookBackHours, 24 * 7));
        int safeSize = Math.max(1, Math.min(size, 20));

        Instant fromUtc = Instant.now(clock).minus(Duration.ofHours(safeHours));
        var items = logRepo.findRecentPreviewItems(userId, fromUtc, safeSize)
                .stream()
                .map(this::toItem)
                .toList();

        return new FoodLogListResponse(
                items,
                new FoodLogListResponse.Page(0, safeSize, items.size(), 1),
                new FoodLogEnvelope.Trace(requestId)
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
