package com.calai.backend.foodlog.packagedfood;

import com.calai.backend.foodlog.barcode.BarcodeNormalizer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record PackageProductCandidate(
        boolean packagedFood,
        String brand,
        String productName,
        String variant,
        String sizeText,
        String visibleBarcode
) {
    public List<String> searchQueries() {
        Set<String> out = new LinkedHashSet<>();

        String b = clean(brand);
        String p = clean(productName);
        String v = clean(variant);

        addIfUseful(out, join(b, p, v));
        addIfUseful(out, join(p, v));
        addIfUseful(out, join(b, p));
        addIfUseful(out, p);

        return new ArrayList<>(out);
    }

    public boolean hasUsefulName() {
        return clean(productName) != null;
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
        if (t.length() < 3) return;
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
                + ", visibleBarcode=" + safe(visibleBarcode))
                .toLowerCase(Locale.ROOT);
    }

    private static String safe(String s) {
        return s == null ? "null" : s;
    }
}
