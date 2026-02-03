package com.calai.backend.foodlog.config;

import com.calai.backend.foodlog.quota.model.ModelTier;

public class FoodLogTierResolver {

    private FoodLogTierResolver() {}

    /** 先沿用你目前 Step1 的約定：DG-0=HIGH、DG-2=LOW。 */
    public static ModelTier resolve(String degradeLevel) {
        if (degradeLevel == null) return ModelTier.MODEL_TIER_LOW;
        String v = degradeLevel.trim().toUpperCase();
        return v.startsWith("DG-0") ? ModelTier.MODEL_TIER_HIGH : ModelTier.MODEL_TIER_LOW;
    }
}
