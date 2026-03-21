package com.calai.backend.foodlog.service;

import com.calai.backend.foodlog.barcode.normalize.BarcodeNutrientsNormalizer;
import com.calai.backend.foodlog.dto.FoodLogEnvelope;
import com.calai.backend.foodlog.dto.FoodLogListResponse;
import com.calai.backend.foodlog.mapper.FoodLogDisplayNameResolver;
import com.calai.backend.foodlog.model.FoodLogStatus;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.repo.FoodLogRepository;
import com.calai.backend.foodlog.unit.FoodLogWarning;
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
        FoodLogEntity log = logRepo.findByIdForUpdate(foodLogId)
                .orElseThrow(() -> new IllegalArgumentException("FOOD_LOG_NOT_FOUND"));
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
            boolean barcodeZeroFill = "BARCODE".equalsIgnoreCase(e.getMethod());

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

            labelMeta = toLabelMeta(eff.get("labelMeta"));

            JsonNode aiMetaNode = eff.get("aiMeta");
            if (aiMetaNode != null && aiMetaNode.isObject()) {
                aiMeta = toAiMetaView(aiMetaNode);
                degradedReason = textOrNull(aiMetaNode.get("degradedReason"));
                foodCategory = textOrNull(aiMetaNode.get("foodCategory"));
                foodSubCategory = textOrNull(aiMetaNode.get("foodSubCategory"));
            }

            String method = e.getMethod() == null ? "" : e.getMethod().trim().toUpperCase(java.util.Locale.ROOT);

            if (warnings != null) {
                if ("LABEL".equals(method)) {
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

            if ("NO_FOOD".equals(degradedReason)
                || "UNKNOWN_FOOD".equals(degradedReason)
                || "NO_LABEL".equals(degradedReason)) {
                labelMeta = null;
            }

            if ("MISSING_NUTRITION_FACTS".equals(degradedReason)
                && warnings != null
                && warnings.contains("PACKAGE_SIZE_UNVERIFIED")
                && labelMeta != null
                && "WHOLE_PACKAGE".equals(labelMeta.basis())) {
                labelMeta = new FoodLogEnvelope.LabelMeta(null, "ESTIMATED_PORTION");
            }

            if (labelMeta != null
                && "ESTIMATED_PORTION".equals(labelMeta.basis())
                && labelMeta.servingsPerContainer() != null
                && Math.abs(labelMeta.servingsPerContainer() - 1.0d) < 0.0001d) {
                labelMeta = new FoodLogEnvelope.LabelMeta(null, "ESTIMATED_PORTION");
            }
            if (aiMeta != null && degradedReason != null) {
                aiMeta = new FoodLogEnvelope.AiMetaView(
                        degradedReason,
                        aiMeta.degradedAtUtc(),
                        aiMeta.resultFromCache(),
                        aiMeta.foodCategory(),
                        aiMeta.foodSubCategory(),
                        aiMeta.source(),
                        aiMeta.basis(),
                        aiMeta.lang()
                );
            }
            // ✅ 最終顯示名稱：與 toEnvelope() 保持一致
            foodName = FoodLogDisplayNameResolver.resolve(e.getMethod(), degradedReason, eff);
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
                        degradedReason,
                        foodCategory,
                        foodSubCategory,
                        reasoning,
                        labelMeta,
                        aiMeta
                )
        );
    }

    private static FoodLogEnvelope.LabelMeta toLabelMeta(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }

        Double servingsPerContainer = doubleOrNull(node.get("servingsPerContainer"));
        String basis = textOrNull(node.get("basis"));

        if (servingsPerContainer == null && (basis == null || basis.isBlank())) {
            return null;
        }

        return new FoodLogEnvelope.LabelMeta(servingsPerContainer, basis);
    }

    private static FoodLogEnvelope.AiMetaView toAiMetaView(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }

        String degradedReason = textOrNull(node.get("degradedReason"));
        String degradedAtUtc = textOrNull(node.get("degradedAtUtc"));
        Boolean resultFromCache = booleanOrNull(node.get("resultFromCache"));
        String foodCategory = textOrNull(node.get("foodCategory"));
        String foodSubCategory = textOrNull(node.get("foodSubCategory"));
        String source = textOrNull(node.get("source"));
        String basis = textOrNull(node.get("basis"));
        String lang = textOrNull(node.get("lang"));

        if (degradedReason == null
            && degradedAtUtc == null
            && resultFromCache == null
            && foodCategory == null
            && foodSubCategory == null
            && source == null
            && basis == null
            && lang == null) {
            return null;
        }

        return new FoodLogEnvelope.AiMetaView(
                degradedReason,
                degradedAtUtc,
                resultFromCache,
                foodCategory,
                foodSubCategory,
                source,
                basis,
                lang
        );
    }

    private static Boolean booleanOrNull(JsonNode v) {
        return (v == null || v.isNull() || !v.isBoolean()) ? null : v.asBoolean();
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
