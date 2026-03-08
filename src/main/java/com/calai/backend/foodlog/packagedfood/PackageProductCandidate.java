package com.calai.backend.foodlog.packagedfood;

import com.calai.backend.foodlog.barcode.BarcodeNormalizer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record PackageProductCandidate(
        boolean packagedFood,
        String brand,
        String productName,
        String variant,
        String sizeText,
        String visibleBarcode,
        List<String> searchAliases
) {
    public PackageProductCandidate {
        searchAliases = (searchAliases == null)
                ? List.of()
                : searchAliases.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
    }

    public List<String> searchQueries() {
        Set<String> out = new LinkedHashSet<>();

        String b = clean(brand);
        String p = clean(productName);
        String v = clean(variant);

        // 主查詢
        addIfUseful(out, join(b, p, v));
        addIfUseful(out, join(p, v));
        addIfUseful(out, join(b, p));
        addIfUseful(out, p);

        // ✅ 品牌單獨保留，讓 OFF 至少先回同品牌商品候選
        addIfUseful(out, b);

        // 主商品名 token windows
        addSlidingWindows(out, p, 2, 4, 24);
        addSlidingWindows(out, join(b, p), 2, 5, 24);
        addDistinctiveSingles(out, p, 24);

        // ✅ aliases：保留 native script / alternate visible names
        for (String aliasRaw : searchAliases) {
            String a = clean(aliasRaw);
            if (a == null) continue;

            // ✅ 若 alias 本身已經包含品牌，就不要再 join 出 "Bourbon Bourbon Chocoliere"
            boolean aliasAlreadyContainsBrand =
                    b != null && containsWholeTokenIgnoreCase(a, b);

            if (!aliasAlreadyContainsBrand) {
                addIfUseful(out, join(b, a));
            }

            addIfUseful(out, a);

            // alias 若是單一 token（例如 チョコリエール），直接保留即可
            addSlidingWindows(out, a, 1, 4, 24);
            addDistinctiveSingles(out, a, 24);
        }

        return new ArrayList<>(out);
    }

    private static boolean containsWholeTokenIgnoreCase(String text, String token) {
        if (text == null || token == null) return false;

        String t = text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{Nd}]+", " ").trim();
        String k = token.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{Nd}]+", " ").trim();

        if (t.isBlank() || k.isBlank()) return false;

        for (String part : t.split("\\s+")) {
            if (part.equals(k)) return true;
        }
        return false;
    }

    public boolean hasUsefulName() {
        if (clean(productName) != null) {
            return true;
        }
        for (String alias : searchAliases) {
            if (clean(alias) != null) {
                return true;
            }
        }
        return false;
    }

    private static void addSlidingWindows(Set<String> out, String raw, int minWindow, int maxWindow, int maxQueries) {
        if (raw == null || raw.isBlank() || out.size() >= maxQueries) return;

        String[] tokens = raw.trim().split("\\s+");
        if (tokens.length == 0) return;

        int upper = Math.min(maxWindow, tokens.length);

        for (int win = upper; win >= minWindow; win--) {
            for (int start = 0; start + win <= tokens.length; start++) {
                if (out.size() >= maxQueries) return;

                String candidate = joinTokens(tokens, start, start + win);
                addIfUseful(out, candidate);
            }
        }
    }

    private static void addDistinctiveSingles(Set<String> out, String raw, int maxQueries) {
        if (raw == null || raw.isBlank() || out.size() >= maxQueries) return;

        String[] tokens = raw.trim().split("\\s+");
        for (String token : tokens) {
            if (out.size() >= maxQueries) return;

            String t = cleanToken(token);
            if (!isUsefulSingleToken(t)) continue;

            addIfUseful(out, t);
        }
    }

    private static String joinTokens(String[] tokens, int startInclusive, int endExclusive) {
        StringBuilder sb = new StringBuilder();
        for (int i = startInclusive; i < endExclusive; i++) {
            String t = cleanToken(tokens[i]);
            if (t == null) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(t);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static String cleanToken(String token) {
        if (token == null) return null;
        String t = token.trim().replaceAll("^[^\\p{L}\\p{Nd}]+|[^\\p{L}\\p{Nd}]+$", "");
        return t.isBlank() ? null : t;
    }

    private static boolean isUsefulSingleToken(String token) {
        if (token == null || token.isBlank()) return false;
        if (token.length() < 2) return false;
        return token.chars().anyMatch(Character::isLetter);
    }

    public String normalizedVisibleBarcode() {
        String s = clean(visibleBarcode);
        if (s == null) return null;

        try {
            return BarcodeNormalizer.normalizeOrThrow(s).normalized();
        } catch (Exception ignore) {
            // fallback: 去掉非數字再試一次
        }

        String digits = s.replaceAll("[^0-9]", "");
        if (digits.isBlank()) return null;

        try {
            return BarcodeNormalizer.normalizeOrThrow(digits).normalized();
        } catch (Exception ignore) {
            return null;
        }
    }

    private static void addIfUseful(Set<String> out, String s) {
        if (s == null) return;
        String t = s.trim();
        if (t.length() < 2) return;
        out.add(t);
    }

    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isBlank()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(p.trim());
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static String clean(String s) {
        if (s == null) return null;
        String t = s.trim().replaceAll("\\s+", " ");
        if (t.isBlank()) return null;
        return t;
    }

    public String debugLabel() {
        return ("brand=" + safe(brand)
                + ", productName=" + safe(productName)
                + ", variant=" + safe(variant)
                + ", sizeText=" + safe(sizeText)
                + ", visibleBarcode=" + safe(visibleBarcode)
                + ", searchAliases=" + searchAliases)
                .toLowerCase(Locale.ROOT);
    }

    private static String safe(String s) {
        return s == null ? "null" : s;
    }
}
