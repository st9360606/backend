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

    public ObjectNode apply(ObjectNode effective, String providerCode) {
        if (effective == null) throw new IllegalStateException("PROVIDER_BAD_RESPONSE");

        ObjectNode nutrients = getObj(effective, "nutrients");
        if (nutrients == null) throw new IllegalStateException("PROVIDER_BAD_RESPONSE");

        // ✅ 先取 confidence（讓後面可補 LOW_CONFIDENCE）
        Double conf = (effective.get("confidence") != null && effective.get("confidence").isNumber())
                ? effective.get("confidence").asDouble()
                : null;

        boolean nonFood = isNonFoodSuspect(effective, conf);

        Integer score = null;
        if (!nonFood) {
            score = healthScore.score(nutrients);
            if (score != null) effective.put("healthScore", score);
            else effective.remove("healthScore");
        } else {
            effective.remove("healthScore");
            addWarningWhitelist(effective, FoodLogWarning.NON_FOOD_SUSPECT);
            clampConfidenceMax(effective, 0.3);
        }

        // ✅ conf 缺失或偏低：加上 LOW_CONFIDENCE（但不直接把 conf==null 當 non-food）
        if (conf == null || conf <= 0.4) {
            addWarningWhitelist(effective, FoodLogWarning.LOW_CONFIDENCE);
        }

        ObjectNode meta = JsonNodeFactory.instance.objectNode();
        meta.put("version", "v1");
        meta.put("computedAtUtc", Instant.now().toString());
        meta.put("provider", norm(providerCode));
        if (score != null) meta.put("score", score);
        effective.set("healthScoreMeta", meta);

        // ✅ 最後再做一次 warnings 白名單化（保險）
        sanitizeWarningsToWhitelist(effective);

        return effective;
    }

    /**
     * ✅ 新 non-food 規則：
     * - warnings 有 NO_FOOD_DETECTED -> non-food
     * - 或 nameLooksNonFood 且 confidence <= 0.4 -> non-food
     * - 或 nameLooksNoFood 且 confidence <= 0.4 -> non-food
     * ✅ 注意：confidence==null 不再直接視為 lowConf（避免誤判）
     */
    private static boolean isNonFoodSuspect(ObjectNode eff, Double conf) {
        String rawName = eff.path("foodName").asText("");
        String name = rawName.toLowerCase(Locale.ROOT);

        boolean noFoodDetected = hasWarning(eff, FoodLogWarning.NO_FOOD_DETECTED.name());

        // ✅ foodName 缺失 + 非常低信心 → 當成 no-food（就算 provider 沒吐 warnings）
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

    /** ✅ 把 warnings 收斂成 enum 白名單（移除 provider 自由字串） */
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
