package com.calai.backend.foodlog.provider.util;

import java.util.Locale;

/**
 * 統一處理圖片 MIME：
 * 1. normalize 已存在的 content-type
 * 2. 從 bytes sniff 真實 MIME
 * 3. 提供 fallback 預設值
 * 目前支援：
 * - image/jpeg
 * - image/png
 * - image/webp
 * 暫不支援：
 * - image/heic
 * - image/heif
 */
public final class ImageMimeResolver {

    private ImageMimeResolver() {}

    public static final String DEFAULT_MIME = "image/jpeg";

    /**
     * 解析最終可用的圖片 MIME。
     * 規則：
     * 1. 若 storedMime 有值，先 normalize 後回傳
     * 2. 若 storedMime 缺失，從 bytes sniff
     * 3. 若仍判斷不出來，回傳預設 image/jpeg
     */
    public static String resolveOrDefault(String storedMime, byte[] bytes) {
        String normalized = normalize(storedMime);
        if (normalized != null) {
            return normalized;
        }

        String sniffed = sniffFromBytes(bytes);
        if (sniffed != null) {
            return sniffed;
        }

        return DEFAULT_MIME;
    }

    /**
     * 正規化 MIME 字串。
     * 例如：
     * - image/jpg   -> image/jpeg
     * - image/x-png -> image/png
     * - image/webp  -> image/webp
     * 若為 null / blank，回傳 null。
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }

        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return null;
        }

        // 去掉像 "image/jpeg; charset=binary" 這種附加參數
        int semicolon = s.indexOf(';');
        if (semicolon >= 0) {
            s = s.substring(0, semicolon).trim();
        }

        return switch (s) {
            case "image/jpg" -> "image/jpeg";
            case "image/x-png" -> "image/png";
            case "image/jpeg", "image/png", "image/webp" -> s;
            default -> s;
        };
    }

    /**
     * 依照 magic number 從 bytes 判斷 MIME。
     */
    public static String sniffFromBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }

        // PNG
        if (startsWith(bytes, new byte[] {
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        })) {
            return "image/png";
        }

        // JPEG
        if (bytes.length >= 3
                && bytes[0] == (byte) 0xFF
                && bytes[1] == (byte) 0xD8
                && bytes[2] == (byte) 0xFF) {
            return "image/jpeg";
        }

        // WebP: 0..3 = RIFF, 8..11 = WEBP
        if (startsWithAt(bytes, 0, new byte[] { 0x52, 0x49, 0x46, 0x46 })
                && startsWithAt(bytes, 8, new byte[] { 0x57, 0x45, 0x42, 0x50 })) {
            return "image/webp";
        }

        return null;
    }

    private static boolean startsWith(byte[] buf, byte[] prefix) {
        return startsWithAt(buf, 0, prefix);
    }

    private static boolean startsWithAt(byte[] buf, int offset, byte[] prefix) {
        if (buf == null || prefix == null) {
            return false;
        }
        if (buf.length < offset + prefix.length) {
            return false;
        }

        for (int i = 0; i < prefix.length; i++) {
            if (buf[offset + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
