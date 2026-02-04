package com.calai.backend.foodlog.provider;

import com.calai.backend.foodlog.model.ProviderRefuseReason;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 目的：只要命中 SAFETY/RECITATION/HARM → 立刻拒答（不 retry / 不 repair / 不 upgrade）
 */
public final class GeminiRefusalDetector {

    private GeminiRefusalDetector() {}

    public static ProviderRefuseReason detectOrNull(JsonNode resp) {
        if (resp == null || resp.isNull()) return null;

        // 1) promptFeedback.blockReason（有些情況 candidates 會是空）
        String blockReason = text(resp, "promptFeedback", "blockReason");
        if ("SAFETY".equalsIgnoreCase(blockReason)) return ProviderRefuseReason.SAFETY;
        if ("RECITATION".equalsIgnoreCase(blockReason)) return ProviderRefuseReason.RECITATION;

        // 2) candidates[0].finishReason
        JsonNode cand0 = resp.path("candidates").path(0);
        String finishReason = cand0.path("finishReason").asText(null);
        if ("SAFETY".equalsIgnoreCase(finishReason)) return ProviderRefuseReason.SAFETY;
        if ("RECITATION".equalsIgnoreCase(finishReason)) return ProviderRefuseReason.RECITATION;

        // 3) safetyRatings：只要被 blocked 就視為 HARM_CATEGORY
        if (hasBlockedRating(cand0.path("safetyRatings"))) return ProviderRefuseReason.HARM_CATEGORY;
        if (hasBlockedRating(resp.path("promptFeedback").path("safetyRatings"))) return ProviderRefuseReason.HARM_CATEGORY;

        return null;
    }

    private static boolean hasBlockedRating(JsonNode arr) {
        if (arr == null || !arr.isArray()) return false;
        for (JsonNode it : arr) {
            if (it == null || it.isNull()) continue;
            if (it.path("blocked").asBoolean(false)) return true;

            String prob = it.path("probability").asText("");
            if ("HIGH".equalsIgnoreCase(prob) && it.path("blocked").asBoolean(false)) return true;
        }
        return false;
    }

    private static String text(JsonNode root, String... path) {
        JsonNode cur = root;
        for (String p : path) cur = cur.path(p);
        if (cur == null || cur.isMissingNode() || cur.isNull()) return null;
        String s = cur.asText(null);
        return (s == null || s.isBlank()) ? null : s;
    }
}