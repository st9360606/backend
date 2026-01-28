package com.calai.backend.foodlog.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;

@RequiredArgsConstructor
@Service
public class EffectivePostProcessor {

    private final HealthScore healthScore;
    private final NutritionSanityChecker sanityChecker;

    /**
     * ✅ 向後相容：舊呼叫點不傳 method 時，預設走非 LABEL 邏輯
     */
    public ObjectNode apply(ObjectNode effective, String providerCode) {
        return apply(effective, providerCode, null);
    }

    /**
     * ✅ NEW：method-aware
     * - LABEL：不套用 NON_FOOD_SUSPECT（避免誤傷），但仍計算 healthScore（你要求）
     * - 其他：維持原本行為
     */
    public ObjectNode apply(ObjectNode effective, String providerCode, String method) {
        if (effective == null) throw new IllegalStateException("PROVIDER_BAD_RESPONSE");

        ObjectNode nutrients = getObj(effective, "nutrients");
        if (nutrients == null) throw new IllegalStateException("PROVIDER_BAD_RESPONSE");

        String m = (method == null) ? "" : method.trim().toUpperCase(Locale.ROOT);
        boolean isLabel = "LABEL".equals(m);

        Double conf = (effective.get("confidence") != null && effective.get("confidence").isNumber())
                ? effective.get("confidence").asDouble()
                : null;

        // ✅ degraded 判斷：加入 NO_LABEL_DETECTED（Label 專用）
        boolean noFood  = hasWarning(effective, FoodLogWarning.NO_FOOD_DETECTED.name());
        boolean unknown = hasWarning(effective, FoodLogWarning.UNKNOWN_FOOD.name());
        boolean noLabel = hasWarning(effective, FoodLogWarning.NO_LABEL_DETECTED.name());

        boolean degraded = noFood || unknown || noLabel;

        Integer score = null;

        // ===== 1) degraded：不計分（因為 nutrients 通常全 null / 不可信）=====
        if (degraded) {
            effective.remove("healthScore");

            // LOW_CONFIDENCE（保留）
            if (conf == null || conf <= 0.4) addWarningWhitelist(effective, FoodLogWarning.LOW_CONFIDENCE);

            // sanity check（保留）
            sanityChecker.apply(effective);

            // degradedReason 寫入 aiMeta
            setDegradedReasonIfAny(effective);

            // healthScore meta（保留格式）
            ObjectNode meta = JsonNodeFactory.instance.objectNode();
            meta.put("version", "v1");
            meta.put("computedAtUtc", Instant.now().toString());
            meta.put("provider", norm(providerCode));
            effective.set("healthScoreMeta", meta);

            sanitizeWarningsToWhitelist(effective);
            return effective;
        }

        // ===== 2) 非 degraded：Label 跳過 NON_FOOD_SUSPECT，但仍計分 =====
        if (isLabel) {
            score = healthScore.score(nutrients);
            if (score != null) effective.put("healthScore", score);
            else effective.remove("healthScore");
        } else {
            // 原本邏輯：非 Label 才做 NON_FOOD_SUSPECT
            boolean nonFood = isNonFoodSuspect(effective, conf);

            if (!nonFood) {
                score = healthScore.score(nutrients);
                if (score != null) effective.put("healthScore", score);
                else effective.remove("healthScore");
            } else {
                effective.remove("healthScore");
                addWarningWhitelist(effective, FoodLogWarning.NON_FOOD_SUSPECT);
                clampConfidenceMax(effective, 0.3);
            }
        }

        // LOW_CONFIDENCE（維持你原本）
        if (conf == null || conf <= 0.4) {
            addWarningWhitelist(effective, FoodLogWarning.LOW_CONFIDENCE);
        }

        sanityChecker.apply(effective);

        // healthScore meta
        ObjectNode meta = JsonNodeFactory.instance.objectNode();
        meta.put("version", "v1");
        meta.put("computedAtUtc", Instant.now().toString());
        meta.put("provider", norm(providerCode));
        if (score != null) meta.put("score", score);
        effective.set("healthScoreMeta", meta);

        setDegradedReasonIfAny(effective);
        sanitizeWarningsToWhitelist(effective);

        return effective;
    }

    private static void setDegradedReasonIfAny(ObjectNode eff) {
        boolean noFood  = hasWarning(eff, FoodLogWarning.NO_FOOD_DETECTED.name());
        boolean unknown = hasWarning(eff, FoodLogWarning.UNKNOWN_FOOD.name());
        boolean noLabel = hasWarning(eff, FoodLogWarning.NO_LABEL_DETECTED.name());

        String reason = null;
        if (noFood) reason = "NO_FOOD";
        else if (unknown) reason = "UNKNOWN_FOOD";
        else if (noLabel) reason = "NO_LABEL";

        if (reason == null) return;

        ObjectNode aiMeta = ensureObj(eff, "aiMeta");
        if (aiMeta.get("degradedReason") == null || aiMeta.get("degradedReason").isNull()) {
            aiMeta.put("degradedReason", reason);
        }
        if (aiMeta.get("degradedAtUtc") == null || aiMeta.get("degradedAtUtc").isNull()) {
            aiMeta.put("degradedAtUtc", Instant.now().toString());
        }
    }

    private static ObjectNode ensureObj(ObjectNode root, String field) {
        JsonNode n = root.get(field);
        if (n != null && n.isObject()) return (ObjectNode) n;
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        root.set(field, out);
        return out;
    }

    private static boolean isNonFoodSuspect(ObjectNode eff, Double conf) {
        String rawName = eff.path("foodName").asText("");
        String name = rawName.toLowerCase(Locale.ROOT);

        boolean noFoodDetected = hasWarning(eff, FoodLogWarning.NO_FOOD_DETECTED.name());

        boolean nameMissing = rawName.isBlank();
        boolean veryLowConf = (conf != null && conf <= 0.2);
        boolean implicitNoFood = nameMissing && veryLowConf;

        boolean nameLooksNonFood =
                name.contains("glass") || name.contains("mug") || name.contains("cup")
                || name.contains("plate") || name.contains("bottle") || name.contains("table");

        boolean lowConf = (conf != null && conf <= 0.4);

        boolean nameLooksNoFood =
                name.contains("no food")
                || name.contains("not food")
                || name.contains("no meal")
                || name.contains("unknown")
                || name.contains("undetected");

        return noFoodDetected
               || implicitNoFood
               || (nameLooksNonFood && lowConf)
               || (nameLooksNoFood && lowConf);
    }

    private static boolean hasWarning(ObjectNode eff, String code) {
        JsonNode w = eff.get("warnings");
        if (w == null || !w.isArray()) return false;
        for (JsonNode n : w) {
            if (n != null && code.equalsIgnoreCase(n.asText())) return true;
        }
        return false;
    }

    private static void addWarningWhitelist(ObjectNode eff, FoodLogWarning w) {
        ArrayNode out = ensureWarningsArray(eff);
        for (JsonNode n : out) {
            if (n != null && w.name().equalsIgnoreCase(n.asText())) return;
        }
        out.add(w.name());
    }

    private static ArrayNode ensureWarningsArray(ObjectNode eff) {
        JsonNode arr = eff.get("warnings");
        if (arr != null && arr.isArray()) return (ArrayNode) arr;
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        eff.set("warnings", out);
        return out;
    }

    private static void sanitizeWarningsToWhitelist(ObjectNode eff) {
        JsonNode w = eff.get("warnings");
        if (w == null || !w.isArray()) return;

        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        for (JsonNode it : w) {
            if (it == null || it.isNull()) continue;
            FoodLogWarning ww = FoodLogWarning.parseOrNull(it.asText());
            if (ww != null) out.add(ww.name());
        }

        if (out.isEmpty()) eff.remove("warnings");
        else eff.set("warnings", out);
    }

    private static void clampConfidenceMax(ObjectNode eff, double max) {
        JsonNode c = eff.get("confidence");
        if (c != null && c.isNumber()) {
            double v = c.asDouble();
            if (v > max) eff.put("confidence", max);
        }
    }

    private static ObjectNode getObj(ObjectNode root, String field) {
        JsonNode n = root.get(field);
        return (n != null && n.isObject()) ? (ObjectNode) n : null;
    }

    private static String norm(String s) {
        if (s == null) return null;
        String v = s.trim().toUpperCase(Locale.ROOT);
        return v.isEmpty() ? null : v;
    }
}
