package com.calai.backend.foodlog.config;

import com.calai.backend.foodlog.model.ModelMode;
import com.calai.backend.foodlog.quota.model.ModelTier;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

public class AiModelRouter {

    public record Resolved(String provider, String modelId) {}

    private final EnumMap<ModelTier, EnumMap<ModelMode, Resolved>> map = new EnumMap<>(ModelTier.class);

    public AiModelRouter(AiModelTiersProperties props) {
        for (ModelTier tier : ModelTier.values()) {
            map.put(tier, new EnumMap<>(ModelMode.class));
        }

        Map<String, Map<String, AiModelTiersProperties.TierSpec>> raw = props.getModelTiers();
        if (raw == null || raw.isEmpty()) return;

        for (var tierEntry : raw.entrySet()) {
            ModelTier tier = parseTier(tierEntry.getKey());
            if (tier == null) continue;

            Map<String, AiModelTiersProperties.TierSpec> byMode = tierEntry.getValue();
            if (byMode == null) continue;

            for (var modeEntry : byMode.entrySet()) {
                ModelMode mode = parseMode(modeEntry.getKey());
                if (mode == null) continue;

                AiModelTiersProperties.TierSpec s = modeEntry.getValue();
                if (s == null) continue;

                String provider = norm(s.getProvider());
                String modelId  = (s.getModelId() == null) ? null : s.getModelId().trim();

                if (provider != null && modelId != null && !modelId.isBlank()) {
                    map.get(tier).put(mode, new Resolved(provider, modelId));
                }
            }
        }
    }

    public Resolved resolveOrThrow(ModelTier tier, ModelMode mode) {
        Resolved r = map.get(tier).get(mode);
        if (r == null) throw new IllegalStateException("MODEL_TIER_NOT_CONFIGURED: " + tier + "/" + mode);
        return r;
    }

    private static ModelTier parseTier(String s) {
        try { return ModelTier.valueOf(norm(s)); } catch (Exception e) { return null; }
    }

    private static ModelMode parseMode(String s) {
        try { return ModelMode.valueOf(norm(s)); } catch (Exception e) { return null; }
    }

    private static String norm(String s) {
        if (s == null) return null;
        String v = s.trim().toUpperCase(Locale.ROOT);
        return v.isEmpty() ? null : v;
    }
}