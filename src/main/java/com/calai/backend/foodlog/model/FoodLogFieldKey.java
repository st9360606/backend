package com.calai.backend.foodlog.model;

import java.util.Locale;

public enum FoodLogFieldKey {
    FOOD_NAME,
    QUANTITY,
    NUTRIENTS,
    HEALTH_SCORE;

    public static FoodLogFieldKey parse(String raw) {
        if (raw == null) return null;
        String v = raw.trim().toUpperCase(Locale.ROOT);
        try {
            return FoodLogFieldKey.valueOf(v);
        } catch (Exception e) {
            return null;
        }
    }
}
