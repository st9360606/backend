package com.calai.backend.foodlog.provider;

import java.util.Locale;

/// PHOTO / ALBUM：
/// - 只走 GEMINI
/// - 最多 2 次 GEMINI
/// - 不允許 OFF local fast path
/// - 不允許 OFF name-search fallback
/// BARCODE：
/// - 不在 GeminiProviderClient 內處理
/// - 仍由 FoodLogService#createBarcodeMvp() 走 OFF
/// LABEL：
/// - 保持既有行為
public final class GeminiVisionRoutePolicy {

    private GeminiVisionRoutePolicy() {}

    public static boolean isPhotoOrAlbum(String method) {
        if (method == null) return false;
        String m = method.trim().toUpperCase(Locale.ROOT);
        return "PHOTO".equals(m) || "ALBUM".equals(m);
    }

    public static boolean isLabel(String method) {
        return method != null && "LABEL".equalsIgnoreCase(method.trim());
    }

    /**
     * 目前需求：
     * PHOTO / ALBUM 不允許 OFF local barcode fast path
     */
    public static boolean allowOffLocalFastPath(String method) {
        return false;
    }

    /**
     * 目前需求：
     * PHOTO / ALBUM 不允許 OFF name-search fallback
     */
    public static boolean allowOffNameSearchFallback(String method) {
        return false;
    }

    /**
     * PHOTO / ALBUM 最多 2 次 Gemini。
     * 其他 method 目前不限制，由既有流程控制。
     */
    public static int maxGeminiCalls(String method) {
        return isPhotoOrAlbum(method) ? 2 : Integer.MAX_VALUE;
    }
}
