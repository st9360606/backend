package com.calai.backend.foodlog.service.support;

import com.calai.backend.foodlog.dto.FoodLogEnvelope;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public final class FoodLogEffectiveViewSupport {

    private FoodLogEffectiveViewSupport() {
    }

    public static FoodLogEnvelope.LabelMeta toLabelMeta(JsonNode node) {
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

    public static FoodLogEnvelope.AiMetaView toAiMetaView(JsonNode node) {
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

    public static FoodLogEnvelope.LabelMeta normalizeLabelMetaForDisplay(
            String degradedReason,
            List<String> warnings,
            FoodLogEnvelope.LabelMeta labelMeta
    ) {
        if (labelMeta == null) {
            return null;
        }

        if ("NO_FOOD".equals(degradedReason)
                || "UNKNOWN_FOOD".equals(degradedReason)
                || "NO_LABEL".equals(degradedReason)) {
            return null;
        }

        if ("MISSING_NUTRITION_FACTS".equals(degradedReason)
                && warnings != null
                && warnings.contains("PACKAGE_SIZE_UNVERIFIED")
                && "WHOLE_PACKAGE".equals(labelMeta.basis())) {
            return new FoodLogEnvelope.LabelMeta(null, "ESTIMATED_PORTION");
        }

        if ("ESTIMATED_PORTION".equals(labelMeta.basis())
                && labelMeta.servingsPerContainer() != null
                && Math.abs(labelMeta.servingsPerContainer() - 1.0d) < 0.0001d) {
            return new FoodLogEnvelope.LabelMeta(null, "ESTIMATED_PORTION");
        }

        return labelMeta;
    }

    public static FoodLogEnvelope.AiMetaView overrideAiMetaDegradedReason(
            FoodLogEnvelope.AiMetaView aiMeta,
            String degradedReason
    ) {
        if (aiMeta == null || degradedReason == null) {
            return aiMeta;
        }

        return new FoodLogEnvelope.AiMetaView(
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

    private static Boolean booleanOrNull(JsonNode node) {
        return (node == null || node.isNull() || !node.isBoolean()) ? null : node.asBoolean();
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String s = node.asText(null);
        return (s == null || s.isBlank()) ? null : s;
    }

    private static Double doubleOrNull(JsonNode node) {
        if (node == null || node.isNull() || !node.isNumber()) {
            return null;
        }
        double d = node.asDouble();
        return Double.isFinite(d) ? d : null;
    }
}
