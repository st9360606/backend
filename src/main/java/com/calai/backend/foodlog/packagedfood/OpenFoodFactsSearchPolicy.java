package com.calai.backend.foodlog.packagedfood;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 集中管理：
 * 1. name search 分數門檻
 * 2. low confidence 分數門檻
 * 3. package size normalize / similarity score
 *
 * 改善點：
 * - 支援 2x85g / 85g x 2 / 12x33cl / 12 fl oz / 10 oz
 * - 先做單位正規化，再比較相似度
 */
@Component
public class OpenFoodFactsSearchPolicy {

    @Value("${app.openfoodfacts.name-search.accept-min-score:0.78}")
    private double acceptMinScore;

    @Value("${app.openfoodfacts.name-search.low-confidence-score:0.90}")
    private double lowConfidenceScore;

    /**
     * 支援格式：
     * - 2x85g
     * - 2 x 85 g
     * - 85g x 2
     * - 33cl
     * - 12 fl oz
     * - 10 oz
     */
    private static final Pattern P_QTY = Pattern.compile(
            "(?:(\\d+)\\s*[x×]\\s*)?" +                 // prefix multiplier, e.g. 2x85g
            "([\\d][\\d.,']*)" +                       // numeric value
            "\\s*" +
            "(fl\\s*oz|fluid\\s*ounces?|oz|kg|g|mg|ml|l|lt|cl|dl)" + // unit
            "\\b" +
            "(?:\\s*[x×]\\s*(\\d+))?",                 // suffix multiplier, e.g. 85g x 2
            Pattern.CASE_INSENSITIVE
    );

    private static final double GRAMS_PER_OUNCE = 28.349523125d;
    private static final double ML_PER_FLUID_OUNCE = 29.5735295625d;

    public boolean isAcceptableScore(double score) {
        return score >= acceptMinScore;
    }

    public boolean isLowConfidenceScore(double score) {
        return score < lowConfidenceScore;
    }

    /**
     * size score 先 normalize 再比較
     *
     * 範例：
     * - 33cl vs 330ml -> 高分
     * - 0.5l vs 500ml -> 高分
     * - 2x85g vs 170g -> 高分
     * - 85g x 2 vs 170g -> 高分
     * - 12 fl oz vs 355ml -> 高分
     */
    public double sizeSimilarityScore(String leftRaw, String rightRaw) {
        CanonicalSize left = parseCanonicalSize(leftRaw);
        CanonicalSize right = parseCanonicalSize(rightRaw);

        if (left == null || right == null) return 0.0;
        if (!left.kind().equals(right.kind())) return 0.0;

        double max = Math.max(left.value(), right.value());
        double min = Math.min(left.value(), right.value());

        if (max <= 0.0) return 0.0;

        double ratio = min / max;

        if (ratio >= 0.98) return 1.0;
        if (ratio >= 0.90) return 0.85;
        if (ratio >= 0.75) return 0.65;
        if (ratio >= 0.50) return 0.35;
        return 0.10;
    }

    private CanonicalSize parseCanonicalSize(String raw) {
        if (raw == null || raw.isBlank()) return null;

        String s = raw.trim().toLowerCase(Locale.ROOT);
        Matcher m = P_QTY.matcher(s);

        CanonicalSize best = null;

        while (m.find()) {
            String prefixMultRaw = m.group(1);
            String valueRaw = m.group(2);
            String unitRaw = m.group(3);
            String suffixMultRaw = m.group(4);

            if (valueRaw == null || unitRaw == null) continue;

            String normalizedValue = normalizeNumberish(valueRaw);

            double value;
            try {
                value = Double.parseDouble(normalizedValue);
            } catch (NumberFormatException ex) {
                continue;
            }

            if (value <= 0.0) continue;

            int multiplier = 1;
            multiplier *= parsePositiveIntOrOne(prefixMultRaw);
            multiplier *= parsePositiveIntOrOne(suffixMultRaw);

            CanonicalSize converted = toCanonical(value * multiplier, unitRaw);
            if (converted == null) continue;

            if (best == null || converted.value() > best.value()) {
                best = converted;
            }
        }

        return best;
    }

    private CanonicalSize toCanonical(double value, String unitRaw) {
        if (unitRaw == null || value <= 0.0) return null;

        String u = normalizeUnit(unitRaw);

        return switch (u) {
            case "g" -> new CanonicalSize(value, "MASS_G");
            case "kg" -> new CanonicalSize(value * 1000.0, "MASS_G");
            case "mg" -> new CanonicalSize(value / 1000.0, "MASS_G");
            case "oz" -> new CanonicalSize(value * GRAMS_PER_OUNCE, "MASS_G");

            case "ml" -> new CanonicalSize(value, "VOLUME_ML");
            case "l", "lt" -> new CanonicalSize(value * 1000.0, "VOLUME_ML");
            case "cl" -> new CanonicalSize(value * 10.0, "VOLUME_ML");
            case "dl" -> new CanonicalSize(value * 100.0, "VOLUME_ML");
            case "fl oz" -> new CanonicalSize(value * ML_PER_FLUID_OUNCE, "VOLUME_ML");

            default -> null;
        };
    }

    private static String normalizeUnit(String raw) {
        if (raw == null) return "";
        String u = raw.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");

        if (u.equals("fluid ounce") || u.equals("fluid ounces")) {
            return "fl oz";
        }
        return u;
    }

    private static int parsePositiveIntOrOne(String raw) {
        if (raw == null || raw.isBlank()) return 1;
        try {
            int v = Integer.parseInt(raw.trim());
            return v > 0 ? v : 1;
        } catch (NumberFormatException ignore) {
            return 1;
        }
    }

    private static String normalizeNumberish(String raw) {
        String s = raw.trim()
                .replace("\u00A0", "")
                .replace(" ", "")
                .replace("'", "")
                .replace("’", "");

        int lastComma = s.lastIndexOf(',');
        int lastDot = s.lastIndexOf('.');

        if (lastComma >= 0 && lastDot >= 0) {
            if (lastComma > lastDot) {
                s = s.replace(".", "").replace(',', '.');
            } else {
                s = s.replace(",", "");
            }
        } else if (lastComma >= 0) {
            if (looksLikeThousandsSeparated(s, ',')) {
                s = s.replace(",", "");
            } else {
                s = s.replace(',', '.');
            }
        } else if (lastDot >= 0) {
            if (looksLikeThousandsSeparated(s, '.')) {
                s = s.replace(".", "");
            }
        }

        return s;
    }

    private static boolean looksLikeThousandsSeparated(String s, char sep) {
        if (s == null || s.isBlank()) return false;

        int start = (s.startsWith("-") || s.startsWith("+")) ? 1 : 0;
        if (start >= s.length()) return false;

        String body = s.substring(start);
        String[] parts = body.split(Pattern.quote(String.valueOf(sep)));
        if (parts.length < 2) return false;

        if (parts[0].isEmpty() || parts[0].length() > 3) return false;
        if (!parts[0].chars().allMatch(Character::isDigit)) return false;

        for (int i = 1; i < parts.length; i++) {
            String p = parts[i];
            if (p.length() != 3) return false;
            if (!p.chars().allMatch(Character::isDigit)) return false;
        }
        return true;
    }

    private record CanonicalSize(double value, String kind) {}
}
