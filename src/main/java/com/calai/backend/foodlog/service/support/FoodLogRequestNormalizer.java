package com.calai.backend.foodlog.service.support;

import java.time.ZoneId;
import java.time.ZoneOffset;

public final class FoodLogRequestNormalizer {

    private FoodLogRequestNormalizer() {
    }

    /**
     * 與既有邏輯一致：
     * null / blank -> uid-{userId}
     */
    public static String normalizeDeviceId(Long userId, String deviceId) {
        if (deviceId == null) {
            return "uid-" + userId;
        }
        String s = deviceId.trim();
        return s.isEmpty() ? ("uid-" + userId) : s;
    }

    /**
     * client 時區解析失敗時，一律回 UTC。
     */
    public static ZoneId parseClientTzOrUtc(String tz) {
        try {
            return (tz == null || tz.isBlank()) ? ZoneOffset.UTC : ZoneId.of(tz);
        } catch (Exception ignored) {
            return ZoneOffset.UTC;
        }
    }

    /**
     * quota / cooldown / abuse guard 一律用 UTC，
     * 避免 client 透過切時區影響 bucket。
     */
    public static ZoneId resolveQuotaTz() {
        return ZoneOffset.UTC;
    }
}
