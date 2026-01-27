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
    private final NutritionSanityChecker sanityChecker; // ✅ Step 7-06

    public ObjectNode apply(ObjectNode effective, String providerCode) {
        if (effective == null) throw new IllegalStateException("PROVIDER_BAD_RESPONSE");

        ObjectNode nutrients = getObj(effective, "nutrients");
        if (nutrients == null) throw new IllegalStateException("PROVIDER_BAD_RESPONSE");

        Double conf = (effective.get("confidence") != null && effective.get("confidence").isNumber())
                ? effective.get("confidence").asDouble()
                : null;

        // ✅ 1) 降級優先：NO_FOOD / UNKNOWN_FOOD 時，不再追加 NON_FOOD_SUSPECT（避免 UI 混亂）
        boolean noFood = hasWarning(effective, FoodLogWarning.NO_FOOD_DETECTED.name());
        boolean unknown = hasWarning(effective, FoodLogWarning.UNKNOWN_FOOD.name());
        boolean degraded = noFood || unknown;

        Integer score = null;

        if (degraded) {
            // 直接移除 healthScore（降級不計分）
            effective.remove("healthScore");

            // LOW_CONFIDENCE（保留）
            if (conf == null || conf <= 0.4) addWarningWhitelist(effective, FoodLogWarning.LOW_CONFIDENCE);

            // ✅ sanity check（保留：會補 UNIT_UNKNOWN / OUTLIER 等，但 nutrients all-null 會早退）
            sanityChecker.apply(effective);

            // ✅ degradedReason 寫入 aiMeta（你原本就有）
            setDegradedReasonIfAny(effective);

            // healthScore meta（保留你原本格式，但 score=null）
            ObjectNode meta = JsonNodeFactory.instance.objectNode();
            meta.put("version", "v1");
            meta.put("computedAtUtc", Instant.now().toString());
            meta.put("provider", norm(providerCode));
            effective.set("healthScoreMeta", meta);

            // warnings 白名單化
            sanitizeWarningsToWhitelist(effective);
            return effective;
        }

        // ✅ 2) 非降級：才做 NON_FOOD_SUSPECT 判斷
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

        // LOW_CONFIDENCE（保留你原本）
        if (conf == null || conf <= 0.4) {
            addWarningWhitelist(effective, FoodLogWarning.LOW_CONFIDENCE);
        }

        sanityChecker.apply(effective);

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
        boolean noFood = hasWarning(eff, FoodLogWarning.NO_FOOD_DETECTED.name());
        boolean unknown = hasWarning(eff, FoodLogWarning.UNKNOWN_FOOD.name());

        String reason = null;
        if (noFood) reason = "NO_FOOD";
        else if (unknown) reason = "UNKNOWN_FOOD";

        if (reason == null) return;

        ObjectNode aiMeta = ensureObj(eff, "aiMeta");
        // 不覆蓋既有值（如果你未來在 provider 端先寫，也能保留）
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
