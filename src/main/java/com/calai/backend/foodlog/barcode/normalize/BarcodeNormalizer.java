package com.calai.backend.foodlog.barcode.normalize;

/**
 * OFF barcode normalization:
 * - remove leading zeros
 * - if length <= 7 -> pad to 8
 * - if length 9..12 -> pad to 13
 * - else keep as-is (8, 13, 14, ...)
 * Ref: OFF "Reference: Barcode Normalization"
 */
public final class BarcodeNormalizer {

    private BarcodeNormalizer() {}

    public record Norm(String rawInput, String digitsOnly, String normalized) {}

    public static Norm normalizeOrThrow(String raw) {
        if (raw == null) throw new IllegalArgumentException("BARCODE_REQUIRED");

        String trimmed = raw.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("BARCODE_REQUIRED");

        // Some scanners may include spaces/hyphens. Keep digits only.
        String digits = trimmed.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) throw new IllegalArgumentException("BARCODE_INVALID");
        if (digits.length() > 32) throw new IllegalArgumentException("BARCODE_INVALID");
        if (!digits.chars().allMatch(Character::isDigit)) throw new IllegalArgumentException("BARCODE_INVALID");

        // remove leading zeros (keep at least 1 digit)
        int i = 0;
        while (i < digits.length() - 1 && digits.charAt(i) == '0') i++;
        String noLeadingZeros = digits.substring(i);

        int n = noLeadingZeros.length();
        String norm;
        if (n <= 7) {
            norm = leftPadZeros(noLeadingZeros, 8);
        } else if (n >= 9 && n <= 12) {
            norm = leftPadZeros(noLeadingZeros, 13);
        } else {
            norm = noLeadingZeros;
        }

        return new Norm(trimmed, digits, norm);
    }

    private static String leftPadZeros(String s, int totalLen) {
        if (s.length() >= totalLen) return s;
        StringBuilder sb = new StringBuilder(totalLen);
        for (int k = s.length(); k < totalLen; k++) sb.append('0');
        sb.append(s);
        return sb.toString();
    }
}
