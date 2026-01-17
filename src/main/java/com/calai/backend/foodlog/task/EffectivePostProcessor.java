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

    /**
     * 統一後處理：
     * - 補 healthScore + meta
     * - 非食物降級（warnings/confidence/healthScore）
     */
    public ObjectNode apply(ObjectNode effective, String providerCode) {
        if (effective == null) throw new IllegalStateException("PROVIDER_BAD_RESPONSE");

        ObjectNode nutrients = getObj(effective, "nutrients");
        if (nutrients == null) throw new IllegalStateException("PROVIDER_BAD_RESPONSE");

        // 1) 非食物偵測（MVP：用 foodName + nutrients 全 0 作啟發式）
        boolean nonFood = isNonFoodSuspect(effective, nutrients);

        // 2) health score（僅在非食物=false 且資料足夠時計算）
        Integer score = null;
        if (!nonFood) {
            score = healthScore.score(nutrients);
            if (score != null) {
                effective.put("healthScore", score);
            } else {
                // 缺資料就不硬算
                effective.remove("healthScore");
            }
        } else {
            // 非食物：不評分（推薦）
            effective.remove("healthScore");
            addWarning(effective, "NON_FOOD_SUSPECT");
            clampConfidenceMax(effective, 0.3);
        }

        // 3) healthScore meta（版本化）
        ObjectNode meta = JsonNodeFactory.instance.objectNode();
        meta.put("version", "v1");
        meta.put("computedAtUtc", Instant.now().toString());
        meta.put("provider", norm(providerCode));
        if (score != null) meta.put("score", score);

        effective.set("healthScoreMeta", meta);

        return effective;
    }

    private static boolean isNonFoodSuspect(ObjectNode eff, ObjectNode nutrients) {
        String name = eff.path("foodName").asText("").toLowerCase(Locale.ROOT);

        boolean nameLooksNonFood =
                name.contains("glass") || name.contains("mug") || name.contains("cup")
                || name.contains("plate") || name.contains("bottle") || name.contains("table");

        boolean allZero =
                isZero(nutrients.get("kcal")) &&
                isZero(nutrients.get("protein")) &&
                isZero(nutrients.get("fat")) &&
                isZero(nutrients.get("carbs")) &&
                isZero(nutrients.get("fiber")) &&
                isZero(nutrients.get("sugar")) &&
                isZero(nutrients.get("sodium"));

        // MVP：name 非食物 + 全零 → non-food
        return nameLooksNonFood && allZero;
    }

    private static boolean isZero(JsonNode v) {
        if (v == null || v.isNull() || !v.isNumber()) return true;
        return v.asDouble() == 0d;
    }

    private static void addWarning(ObjectNode eff, String w) {
        JsonNode arr = eff.get("warnings");
        ArrayNode out;
        if (arr != null && arr.isArray()) out = (ArrayNode) arr;
        else {
            out = JsonNodeFactory.instance.arrayNode();
            eff.set("warnings", out);
        }
        // 避免重複
        for (JsonNode n : out) {
            if (w.equalsIgnoreCase(n.asText())) return;
        }
        out.add(w);
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
