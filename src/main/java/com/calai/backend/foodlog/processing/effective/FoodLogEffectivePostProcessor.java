package com.calai.backend.foodlog.processing.effective;

import com.calai.backend.foodlog.processing.nutrition.NutritionSanityChecker;
import com.calai.backend.foodlog.unit.FoodLogWarning;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;

@RequiredArgsConstructor
@Service
public class FoodLogEffectivePostProcessor {

    private final NutritionSanityChecker sanityChecker;

    public ObjectNode apply(ObjectNode effective, String providerCode, String method) {
        if (effective == null) throw new IllegalStateException("PROVIDER_BAD_RESPONSE");

        ObjectNode nutrients = getObj(effective, "nutrients");
        if (nutrients == null) throw new IllegalStateException("PROVIDER_BAD_RESPONSE");

        Double conf = null;
        JsonNode confNode = effective.get("confidence");
        if (confNode != null && !confNode.isNull()) {
            if (confNode.isNumber()) {
                conf = confNode.asDouble();
            } else if (confNode.isTextual()) {
                try {
                    conf = Double.parseDouble(confNode.asText().trim());
                } catch (Exception ignore) {
                    conf = null;
                }
            }
        }

        Integer hs = parseHealthScoreStrict(effective.get("healthScore"));

        if (hs != null) {
            effective.put("healthScore", hs);
        } else {
            effective.remove("healthScore");
        }

        boolean noFood = hasWarning(effective, FoodLogWarning.NO_FOOD_DETECTED.name());
        boolean unknown = hasWarning(effective, FoodLogWarning.UNKNOWN_FOOD.name());
        boolean noLabel = hasWarning(effective, FoodLogWarning.NO_LABEL_DETECTED.name());
        boolean missingNutritionFacts = hasWarning(effective, FoodLogWarning.MISSING_NUTRITION_FACTS.name());

        boolean degraded = noFood || unknown || noLabel || missingNutritionFacts;

        if (conf != null && conf <= 0.4) {
            addWarningWhitelist(effective, FoodLogWarning.LOW_CONFIDENCE);
        }

        sanityChecker.apply(effective);
        setDegradedReasonIfAny(effective, method);

        String provider = norm(providerCode);

        ObjectNode meta = JsonNodeFactory.instance.objectNode();
        meta.put("version", "v1");
        meta.put("computedAtUtc", Instant.now().toString());

        if (provider != null) {
            meta.put("provider", provider);
        } else {
            meta.putNull("provider");
        }

        if (hs != null) {
            meta.put("score", hs);
            if (provider != null) {
                meta.put("scoreSource", provider);
            } else {
                meta.putNull("scoreSource");
            }
        }

        if (provider != null) {
            meta.put("confidenceSource", provider);
        } else {
            meta.putNull("confidenceSource");
        }

        meta.put("degraded", degraded);
        effective.set("healthScoreMeta", meta);

        sanitizeWarningsToWhitelist(effective);
        return effective;
    }

    private static void setDegradedReasonIfAny(ObjectNode eff, String method) {
        boolean noFood = hasWarning(eff, FoodLogWarning.NO_FOOD_DETECTED.name());
        boolean unknown = hasWarning(eff, FoodLogWarning.UNKNOWN_FOOD.name());
        boolean noLabel = hasWarning(eff, FoodLogWarning.NO_LABEL_DETECTED.name());
        boolean missingNutritionFacts = hasWarning(eff, FoodLogWarning.MISSING_NUTRITION_FACTS.name());

        String m = (method == null) ? "" : method.trim().toUpperCase(Locale.ROOT);

        String reason = null;

        if ("LABEL".equals(m)) {
            if (noLabel) reason = "NO_LABEL";
            else if (missingNutritionFacts) reason = "MISSING_NUTRITION_FACTS";
            else if (noFood) reason = "NO_LABEL";
            else if (unknown) reason = "UNKNOWN_FOOD";
        } else {
            if (noFood) reason = "NO_FOOD";
            else if (unknown) reason = "UNKNOWN_FOOD";
            else if (noLabel) reason = "NO_LABEL";
            else if (missingNutritionFacts) reason = "MISSING_NUTRITION_FACTS";
        }

        if (reason == null) return;

        ObjectNode aiMeta = ensureObj(eff, "aiMeta");
        aiMeta.put("degradedReason", reason);
        if (aiMeta.get("degradedAtUtc") == null || aiMeta.get("degradedAtUtc").isNull()) {
            aiMeta.put("degradedAtUtc", Instant.now().toString());
        }
    }

    private static Integer parseHealthScoreStrict(JsonNode hsNode) {
        if (hsNode == null || hsNode.isNull()) {
            return null;
        }
        if (hsNode.isIntegralNumber()) {
            int v = hsNode.asInt();
            return (v >= 0 && v <= 10) ? v : null;
        }
        if (hsNode.isTextual()) {
            try {
                String raw = hsNode.asText().trim();
                if (raw.isEmpty()) {
                    return null;
                }
                double d = Double.parseDouble(raw);
                if (!Double.isFinite(d)) {
                    return null;
                }
                if (d < 0 || d > 10) {
                    return null;
                }
                if (d % 1 != 0) {
                    return null;
                }
                return (int) d;
            } catch (Exception ignore) {
                return null;
            }
        }
        return null;
    }

    private static ObjectNode ensureObj(ObjectNode root, String field) {
        JsonNode n = root.get(field);
        if (n != null && n.isObject()) return (ObjectNode) n;
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        root.set(field, out);
        return out;
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
