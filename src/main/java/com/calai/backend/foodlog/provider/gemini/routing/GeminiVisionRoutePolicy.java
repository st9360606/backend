package com.calai.backend.foodlog.provider.gemini.routing;

import com.calai.backend.foodlog.model.FoodLogMethod;

public final class GeminiVisionRoutePolicy {

    private GeminiVisionRoutePolicy() {}

    public static boolean isPhotoOrAlbum(String method) {
        FoodLogMethod m = FoodLogMethod.from(method);
        return m == FoodLogMethod.PHOTO || m == FoodLogMethod.ALBUM;
    }

    public static boolean isLabel(String method) {
        FoodLogMethod m = FoodLogMethod.from(method);
        return m != null && m.isLabel();
    }

    /**
     * PHOTO / ALBUM / LABEL：
     * - 一律只允許 1 次 Gemini
     * - 不允許 text-repair second pass
     * - 不允許 OFF fallback
     * BARCODE：
     * - 不在 GeminiProviderClient 內處理
     * - 仍由 FoodLogService#createBarcodeMvp() 走 OFF
     */
    public static int maxGeminiCalls(String method) {
        return (isPhotoOrAlbum(method) || isLabel(method)) ? 1 : Integer.MAX_VALUE;
    }
}