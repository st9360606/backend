package com.calai.backend.foodlog.provider;

import java.util.Locale;

/**
 * PHOTO / ALBUM：
 * - 主流程仍以 GEMINI 為主
 * - 最多 2 次 GEMINI
 * - 允許 OFF name-search fallback
 * - 先不開 OFF local barcode fast path，避免抓到背景條碼
 * BARCODE：
 * - 不在 GeminiProviderClient 內處理
 * - 仍由 FoodLogService#createBarcodeMvp() 走 OFF
 * LABEL：
 * - 保持既有行為
 */
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
