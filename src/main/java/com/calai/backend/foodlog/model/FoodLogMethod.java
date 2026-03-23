package com.calai.backend.foodlog.model;

import java.util.Locale;

public enum FoodLogMethod {
    PHOTO,
    ALBUM,
    LABEL,
    BARCODE;

    public static FoodLogMethod from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return FoodLogMethod.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public boolean isVisionUpload() {
        return this == PHOTO || this == ALBUM || this == LABEL;
    }

    public boolean isRetryableByUser() {
        return this == PHOTO || this == ALBUM || this == LABEL;
    }

    public boolean isLabel() {
        return this == LABEL;
    }

    public boolean isBarcode() {
        return this == BARCODE;
    }

    public String code() {
        return name();
    }
}
