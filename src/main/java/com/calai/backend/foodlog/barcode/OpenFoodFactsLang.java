package com.calai.backend.foodlog.barcode;

import java.util.*;

public final class OpenFoodFactsLang {
    private OpenFoodFactsLang() {}

    public static String firstLangTagOrNull(String raw) {
        return pickFirstLangTag(raw);
    }

    public static List<String> langCandidates(String raw) {
        String tag = pickFirstLangTag(raw);
        if (tag == null || tag.isBlank()) return List.of();

        String norm = tag.trim().replace('_', '-').toLowerCase(Locale.ROOT);
        if ("*".equals(norm)) return List.of();

        String primary = norm.contains("-") ? norm.substring(0, norm.indexOf('-')) : norm;

        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.add(norm);
        out.add(primary);

        if ("fil".equals(primary)) out.add("tl");
        if ("tl".equals(primary)) out.add("fil");
        if ("jv".equals(primary)) out.add("jw");
        if ("jw".equals(primary)) out.add("jv");
        if ("nb".equals(primary)) out.add("no");
        if ("no".equals(primary)) out.add("nb");

        if (norm.startsWith("zh-hant")) {
            out.add("zh-tw");
            out.add("zh");
        } else if (norm.startsWith("zh-hans")) {
            out.add("zh-cn");
            out.add("zh");
        }

        if (norm.startsWith("zh-hk")) {
            out.add("zh-tw");
            out.add("zh");
        } else if (norm.startsWith("zh-tw")) {
            out.add("zh");
        } else if (norm.startsWith("zh-cn")) {
            out.add("zh");
        }

        return new ArrayList<>(out);
    }

    private static String pickFirstLangTag(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;

        String first = s.split(",")[0].trim();
        if (first.isEmpty()) return null;

        int semi = first.indexOf(';');
        if (semi >= 0) first = first.substring(0, semi).trim();

        return first.isEmpty() ? null : first;
    }
}
