package com.calai.backend.foodlog.service.support;

import com.calai.backend.foodlog.barcode.normalize.BarcodeNutrientsNormalizer;
import com.calai.backend.foodlog.dto.FoodLogEnvelope;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.entity.FoodLogTaskEntity;
import com.calai.backend.foodlog.mapper.ClientActionMapper;
import com.calai.backend.foodlog.mapper.FoodLogDisplayNameResolver;
import com.calai.backend.foodlog.model.ClientAction;
import com.calai.backend.foodlog.model.FoodLogStatus;
import com.calai.backend.foodlog.quota.model.ModelTier;
import com.calai.backend.foodlog.quota.support.DegradeLevelToModelTierResolver;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 專門負責把 FoodLogEntity / FoodLogTaskEntity 組裝成 API response envelope。
 *
 * 這層只做：
 * - response assembling
 * - warnings / degradedReason 推導
 * - BARCODE nutrients view 補 0
 * - task / error / hints 顯示邏輯
 *
 * 這層不做：
 * - repo 存取
 * - transaction
 * - quota consume
 * - storage / upload
 * - provider call
 */
@Component
@RequiredArgsConstructor
public class FoodLogEnvelopeAssembler {

    private final ClientActionMapper clientActionMapper;
    private final Clock clock;

    private static final Pattern P_SUGGESTED_RETRY_AFTER =
            Pattern.compile("suggestedRetryAfterSec=(\\d+)");

    private static final Pattern P_RETRY_AFTER =
            Pattern.compile("retryAfterSec=(\\d+)");

    public FoodLogEnvelope assemble(FoodLogEntity e, FoodLogTaskEntity t, String requestId) {
        Instant now = clock.instant();

        JsonNode eff = e.getEffective();

        String tierUsed = resolveTierUsedDisplay(e);
        boolean fromCache = resolveResultFromCache(eff);

        FoodLogEnvelope.NutritionResult nr = null;
        List<String> warnings = null;
        String degradedReason = null;
        String reasoning = null;
        FoodLogEnvelope.LabelMeta labelMeta = null;
        FoodLogEnvelope.AiMetaView aiMetaView = null;

        String resolvedBy = null;

        if (eff != null && eff.isObject()) {
            JsonNode w = eff.get("warnings");
            if (w != null && w.isArray()) {
                warnings = new java.util.ArrayList<>();
                for (JsonNode it : w) {
                    if (it == null || it.isNull()) continue;
                    String s = it.asText(null);
                    if (s != null && !s.isBlank()) warnings.add(s);
                }
                if (warnings.isEmpty()) warnings = null;
            }

            reasoning = textOrNull(eff, "_reasoning");
            labelMeta = toLabelMeta(eff.get("labelMeta"));

            JsonNode aiMeta = eff.get("aiMeta");
            if (aiMeta != null && aiMeta.isObject()) {
                aiMetaView = toAiMetaView(aiMeta);

                JsonNode src = aiMeta.get("source");
                if (src != null && !src.isNull()) {
                    String s = src.asText(null);
                    if (s != null && !s.isBlank()) {
                        resolvedBy = s.trim();
                    }
                }
            }

            // 不直接信任 aiMeta.degradedReason，統一重新 canonicalize
            if (warnings != null) {
                String method = e.getMethod() == null ? "" : e.getMethod().trim().toUpperCase(Locale.ROOT);

                if ("LABEL".equals(method)) {
                    if (warnings.stream().anyMatch("NO_LABEL_DETECTED"::equalsIgnoreCase)) {
                        degradedReason = "NO_LABEL";
                    } else if (warnings.stream().anyMatch("MISSING_NUTRITION_FACTS"::equalsIgnoreCase)) {
                        degradedReason = "MISSING_NUTRITION_FACTS";
                    } else if (warnings.stream().anyMatch("NO_FOOD_DETECTED"::equalsIgnoreCase)) {
                        degradedReason = "NO_LABEL";
                    } else if (warnings.stream().anyMatch("UNKNOWN_FOOD"::equalsIgnoreCase)) {
                        degradedReason = "UNKNOWN_FOOD";
                    }
                } else {
                    if (warnings.stream().anyMatch("NO_FOOD_DETECTED"::equalsIgnoreCase)) {
                        degradedReason = "NO_FOOD";
                    } else if (warnings.stream().anyMatch("UNKNOWN_FOOD"::equalsIgnoreCase)) {
                        degradedReason = "UNKNOWN_FOOD";
                    } else if (warnings.stream().anyMatch("NO_LABEL_DETECTED"::equalsIgnoreCase)) {
                        degradedReason = "NO_LABEL";
                    } else if (warnings.stream().anyMatch("MISSING_NUTRITION_FACTS"::equalsIgnoreCase)) {
                        degradedReason = "MISSING_NUTRITION_FACTS";
                    }
                }
            }

            // 若 warnings 沒推導出來，再 fallback 到 aiMeta.degradedReason
            if (degradedReason == null && aiMetaView != null) {
                degradedReason = aiMetaView.degradedReason();
            }

            // degraded case：不要帶誤導性的 labelMeta
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

            // 同步修正 aiMetaView，避免 response 內 degradedReason 與 aiMeta.degradedReason 打架
            if (aiMetaView != null && degradedReason != null) {
                aiMetaView = new FoodLogEnvelope.AiMetaView(
                        degradedReason,
                        aiMetaView.degradedAtUtc(),
                        aiMetaView.resultFromCache(),
                        aiMetaView.foodCategory(),
                        aiMetaView.foodSubCategory(),
                        aiMetaView.source(),
                        aiMetaView.basis(),
                        aiMetaView.lang()
                );
            }
        }

        if (resolvedBy == null || resolvedBy.isBlank()) {
            resolvedBy = e.getProvider();
        }

        if (eff != null && !eff.isNull()) {
            JsonNode n = eff.get("nutrients");
            JsonNode q = eff.get("quantity");
            boolean barcodeZeroFill = "BARCODE".equalsIgnoreCase(e.getMethod());

            FoodLogEnvelope.Nutrients nutrientsView;

            if (barcodeZeroFill) {
                // BARCODE：即使整個 nutrients 缺失，也要回全 0.0
                nutrientsView = new FoodLogEnvelope.Nutrients(
                        round1(BarcodeNutrientsNormalizer.readNumber(n, "kcal", true)),
                        round1(BarcodeNutrientsNormalizer.readNumber(n, "protein", true)),
                        round1(BarcodeNutrientsNormalizer.readNumber(n, "fat", true)),
                        round1(BarcodeNutrientsNormalizer.readNumber(n, "carbs", true)),
                        round1(BarcodeNutrientsNormalizer.readNumber(n, "fiber", true)),
                        round1(BarcodeNutrientsNormalizer.readNumber(n, "sugar", true)),
                        round1(BarcodeNutrientsNormalizer.readNumber(n, "sodium", true))
                );
            } else {
                nutrientsView = (n == null) ? null : new FoodLogEnvelope.Nutrients(
                        round1(doubleOrNull(n, "kcal")),
                        round1(doubleOrNull(n, "protein")),
                        round1(doubleOrNull(n, "fat")),
                        round1(doubleOrNull(n, "carbs")),
                        round1(doubleOrNull(n, "fiber")),
                        round1(doubleOrNull(n, "sugar")),
                        round1(doubleOrNull(n, "sodium"))
                );
            }

            nr = new FoodLogEnvelope.NutritionResult(
                    FoodLogDisplayNameResolver.resolve(e.getMethod(), degradedReason, eff),
                    q == null ? null : new FoodLogEnvelope.Quantity(doubleOrNull(q, "value"), textOrNull(q, "unit")),
                    nutrientsView,
                    intOrNull(eff, "healthScore"),
                    doubleOrNull(eff, "confidence"),
                    warnings,
                    degradedReason,
                    aiMetaTextOrNull(eff, "foodCategory"),
                    aiMetaTextOrNull(eff, "foodSubCategory"),
                    reasoning,
                    labelMeta,
                    aiMetaView,
                    new FoodLogEnvelope.Source(
                            e.getMethod(),
                            e.getProvider(),
                            resolvedBy
                    )
            );
        }

        FoodLogEnvelope.Task task = null;
        boolean taskMeaningful = (t != null);

        if (taskMeaningful && (e.getStatus() == FoodLogStatus.PENDING || e.getStatus() == FoodLogStatus.FAILED)) {
            int poll = computePollAfterSec(e.getStatus(), t, now);
            task = new FoodLogEnvelope.Task(t.getId(), poll);
        }

        FoodLogEnvelope.ApiError err = null;
        if (e.getStatus() == FoodLogStatus.FAILED) {

            Integer retryAfter = computeRetryAfterSecOrNull(t, now);

            if (retryAfter == null && t != null) {
                retryAfter = parseRetryAfterFromMessageOrNull(t.getLastErrorMessage());
            }

            if (retryAfter == null) {
                retryAfter = parseRetryAfterFromMessageOrNull(e.getLastErrorMessage());
            }

            if (retryAfter == null && "PROVIDER_RATE_LIMITED".equalsIgnoreCase(e.getLastErrorCode())) {
                retryAfter = 20;
            }

            String action = Optional.ofNullable(clientActionMapper.fromErrorCode(e.getLastErrorCode()))
                    .orElse(ClientAction.RETRY_LATER)
                    .name();

            err = new FoodLogEnvelope.ApiError(
                    e.getLastErrorCode(),
                    action,
                    retryAfter
            );
        }

        List<FoodLogEnvelope.Hint> hints = null;

        if (e.getStatus() == FoodLogStatus.DRAFT && nr != null && warnings != null) {

            boolean hasServingUnknown = warnings.stream()
                    .anyMatch("SERVING_SIZE_UNKNOWN"::equalsIgnoreCase);

            boolean isPer100GramOrMl = nr.quantity() != null
                                       && nr.quantity().unit() != null
                                       && ("GRAM".equalsIgnoreCase(nr.quantity().unit())
                                           || "ML".equalsIgnoreCase(nr.quantity().unit()))
                                       && nr.quantity().value() != null
                                       && Math.abs(nr.quantity().value() - 100.0) < 0.0001;

            if (hasServingUnknown && isPer100GramOrMl) {
                hints = List.of(
                        new FoodLogEnvelope.Hint(
                                "SERVING_SIZE_UNKNOWN",
                                ClientAction.RETAKE_PHOTO.name(),
                                "Please include net weight or serving size in the photo."
                        )
                );
            }
        }

        return new FoodLogEnvelope(
                e.getId(),
                e.getStatus().name(),
                e.getDegradeLevel(),
                tierUsed,
                fromCache,
                nr,
                task,
                err,
                hints,
                new FoodLogEnvelope.Trace(requestId)
        );
    }

    private static FoodLogEnvelope.LabelMeta toLabelMeta(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }

        Double servingsPerContainer = doubleOrNull(node, "servingsPerContainer");
        String basis = textOrNull(node, "basis");

        if (servingsPerContainer == null && (basis == null || basis.isBlank())) {
            return null;
        }

        return new FoodLogEnvelope.LabelMeta(servingsPerContainer, basis);
    }

    private static FoodLogEnvelope.AiMetaView toAiMetaView(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }

        String degradedReason = textOrNull(node, "degradedReason");
        String degradedAtUtc = textOrNull(node, "degradedAtUtc");
        Boolean resultFromCache = booleanOrNull(node, "resultFromCache");
        String foodCategory = textOrNull(node, "foodCategory");
        String foodSubCategory = textOrNull(node, "foodSubCategory");
        String source = textOrNull(node, "source");
        String basis = textOrNull(node, "basis");
        String lang = textOrNull(node, "lang");

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

    private static Boolean booleanOrNull(JsonNode node, String field) {
        if (node == null || node.isNull()) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        return v.isBoolean() ? v.asBoolean() : null;
    }

    private static boolean resolveResultFromCache(JsonNode effective) {
        if (effective == null || effective.isNull() || !effective.isObject()) {
            return false;
        }
        JsonNode aiMeta = effective.get("aiMeta");
        if (aiMeta == null || !aiMeta.isObject()) {
            return false;
        }
        JsonNode resultCache = aiMeta.get("resultFromCache");
        return resultCache != null && resultCache.isBoolean() && resultCache.asBoolean();
    }

    private static Double round1(Double v) {
        if (v == null) return null;
        if (v.isNaN() || v.isInfinite()) return null;

        return BigDecimal.valueOf(v)
                .setScale(1, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private static String aiMetaTextOrNull(JsonNode effective, String field) {
        if (effective == null || effective.isNull()) return null;

        JsonNode aiMeta = effective.get("aiMeta");
        if (aiMeta == null || !aiMeta.isObject()) return null;

        JsonNode v = aiMeta.get(field);
        if (v == null || v.isNull()) return null;

        String s = v.asText(null);
        if (s == null) return null;

        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || node.isNull()) return null;
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private static Integer intOrNull(JsonNode node, String field) {
        if (node == null || node.isNull()) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        if (v.isIntegralNumber()) return v.asInt();
        if (v.isTextual()) {
            String s = v.asText(null);
            if (s == null || s.isBlank()) return null;
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    private static Double doubleOrNull(JsonNode node, String field) {
        if (node == null || node.isNull()) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        if (v.isNumber()) {
            double d = v.asDouble();
            return Double.isFinite(d) ? d : null;
        }
        if (v.isTextual()) {
            String s = v.asText(null);
            if (s == null || s.isBlank()) return null;
            try {
                double d = Double.parseDouble(s.trim());
                return Double.isFinite(d) ? d : null;
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    private static Integer computeRetryAfterSecOrNull(FoodLogTaskEntity t, Instant now) {
        if (t == null || t.getNextRetryAtUtc() == null) return null;
        long sec = Duration.between(now, t.getNextRetryAtUtc()).getSeconds();
        if (sec < 0) sec = 0;
        if (sec > Integer.MAX_VALUE) sec = Integer.MAX_VALUE;
        return (int) sec;
    }

    private static Integer parseRetryAfterFromMessageOrNull(String msg) {
        if (msg == null || msg.isBlank()) return null;

        Matcher m1 = P_SUGGESTED_RETRY_AFTER.matcher(msg);
        if (m1.find()) {
            try {
                return clampInt(Integer.parseInt(m1.group(1)), 0, 3600);
            } catch (Exception ignored) {
            }
        }

        Matcher m2 = P_RETRY_AFTER.matcher(msg);
        if (m2.find()) {
            try {
                return clampInt(Integer.parseInt(m2.group(1)), 0, 3600);
            } catch (Exception ignored) {
            }
        }

        return null;
    }

    private static String resolveTierUsedDisplay(FoodLogEntity e) {
        if (e == null) return null;

        String method = e.getMethod();
        if (method != null && "BARCODE".equalsIgnoreCase(method.trim())) {
            return "BARCODE";
        }

        ModelTier mt = DegradeLevelToModelTierResolver.resolve(e.getDegradeLevel());
        return (mt == null) ? null : mt.name();
    }

    private static int computePollAfterSec(FoodLogStatus status, FoodLogTaskEntity t, Instant now) {
        if (t == null) return 2;

        Integer pollAfterObj = t.getPollAfterSec();
        int base = Math.max(1, pollAfterObj == null ? 2 : pollAfterObj);

        FoodLogTaskEntity.TaskStatus ts = t.getTaskStatus();

        if (status == FoodLogStatus.FAILED) {
            Integer retryAfter = computeRetryAfterSecOrNull(t, now);
            int v = (retryAfter == null) ? 5 : retryAfter;
            return clamp(v, 2, 60);
        }

        if (status == FoodLogStatus.PENDING) {

            if (ts == FoodLogTaskEntity.TaskStatus.QUEUED) {
                Instant created = t.getCreatedAtUtc();
                if (created == null) {
                    return clamp(base, 2, 10);
                }

                long queuedSec = Duration.between(created, now).getSeconds();
                if (queuedSec < 0) queuedSec = 0;

                int v;
                if (queuedSec > 120) v = 10;
                else if (queuedSec > 60) v = 8;
                else if (queuedSec > 30) v = 5;
                else v = base;

                return clamp(v, 2, 10);
            }

            if (ts == FoodLogTaskEntity.TaskStatus.RUNNING) {
                return clamp(base, 2, 10);
            }

            Integer attemptsObj = t.getAttempts();
            int attempts = Math.max(0, attemptsObj == null ? 0 : attemptsObj);
            int v = base + Math.min(attempts, 6);
            return clamp(v, 2, 10);
        }

        return clamp(base, 2, 10);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
