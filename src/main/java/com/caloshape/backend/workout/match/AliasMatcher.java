package com.caloshape.backend.workout.match;

import com.caloshape.backend.workout.entity.WorkoutDictionary;
import com.caloshape.backend.workout.nlp.Similarity;
import com.caloshape.backend.workout.nlp.TextNorm;

import java.util.*;

public final class AliasMatcher {

    private static final double TRUSTED_TOKEN_SCORE = 0.89;

    public static record Candidate(WorkoutDictionary dict, double score, String kind) {}

    private final Map<String, List<String>> synonymsByCanonical;
    private final List<WorkoutDictionary> dictionaries;

    public AliasMatcher(List<WorkoutDictionary> dictionaries,
                        Map<String, List<String>> synonymsByCanonical) {
        this.dictionaries = dictionaries;
        this.synonymsByCanonical = synonymsByCanonical != null ? synonymsByCanonical : Map.of();
    }

    /** 比對 displayNameEn + 同義詞，加上語言別加權 */
    public Candidate best(String phrase, String localeTag) {
        String p = TextNorm.normalize(phrase);
        if (hasSuspiciousAsciiGlue(p, dictionaries, synonymsByCanonical)) {
            return null;
        }

        Candidate best = null;

        for (WorkoutDictionary d : dictionaries) {
            String base = TextNorm.normalize(d.getDisplayNameEn());

            double sName = hasAsciiJunkAdjacentToEmbeddedToken(p, base)
                    ? 0.0
                    : Similarity.jaroWinkler(p, base);
            double sToken = containsTrustedToken(p, base) ? TRUSTED_TOKEN_SCORE : 0.0;
            double sMax = Math.max(sName, sToken);
            String why = sToken > sName ? "token" : "name";

            List<String> syns = synonymsByCanonical.getOrDefault(d.getCanonicalKey(), List.of());
            for (String syn : syns) {
                double sv = hasAsciiJunkAdjacentToEmbeddedToken(p, syn)
                        ? 0.0
                        : Similarity.jaroWinkler(p, syn);
                if (sv > sMax) { sMax = sv; why = "syn"; }
                if (containsTrustedToken(p, syn) && TRUSTED_TOKEN_SCORE > sMax) {
                    sMax = TRUSTED_TOKEN_SCORE;
                    why = "token";
                }
            }

            // 額外同義詞 token 加權（按語言）
            double boost = Similarity.boostBySynonyms(localeTag == null ? "*" : localeTag, p, d.getCanonicalKey());
            double finalScore = Math.min(1.0, sMax + boost); // 封頂 1.0

            if (best == null || finalScore > best.score) {
                best = new Candidate(d, finalScore, why);
            }
        }
        return best;
    }

    private static boolean containsTrustedToken(String phrase, String token) {
        String p = TextNorm.normalize(phrase);
        String t = TextNorm.normalize(token);
        if (p.isBlank() || t.isBlank()) return false;
        if (t.contains(" ") || !isAsciiLetters(t)) {
            return containsTrustedPhrase(p, t);
        }

        String tokenized = p.replaceAll("[^\\p{L}\\p{N}]+", " ");
        for (String word : tokenized.split("\\s+")) {
            if (word.equals(t)) return true;
        }
        return false;
    }

    private static boolean containsTrustedPhrase(String phrase, String token) {
        String p = TextNorm.normalize(phrase);
        String t = TextNorm.normalize(token);
        int start = 0;
        while (!p.isBlank() && !t.isBlank()) {
            int idx = p.indexOf(t, start);
            if (idx < 0) return false;
            int before = idx - 1;
            int after = idx + t.length();
            if (!isAsciiLetterOrDigitAt(p, before) && !isAsciiLetterOrDigitAt(p, after)) {
                return true;
            }
            start = idx + Math.max(1, t.length());
        }
        return false;
    }

    private static boolean hasAsciiJunkAdjacentToEmbeddedToken(String phrase, String token) {
        String p = TextNorm.normalize(phrase);
        String t = TextNorm.normalize(token);
        if (p.isBlank() || t.isBlank() || p.equals(t)) return false;
        if (containsNonAsciiLetter(t) && p.contains(t) && hasAsciiAdjacentToNonAsciiLetter(p)) {
            return true;
        }

        int start = 0;
        while (true) {
            int idx = p.indexOf(t, start);
            if (idx < 0) return false;
            int before = idx - 1;
            int after = idx + t.length();
            if (isAsciiLetterOrDigitAt(p, before) || isAsciiLetterOrDigitAt(p, after)) {
                return true;
            }
            start = idx + Math.max(1, t.length());
        }
    }

    private static boolean isAsciiLetterOrDigitAt(String s, int idx) {
        if (idx < 0 || idx >= s.length()) return false;
        char c = s.charAt(idx);
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    private static boolean hasSuspiciousAsciiGlue(
            String phrase,
            List<WorkoutDictionary> dictionaries,
            Map<String, List<String>> synonymsByCanonical
    ) {
        if (hasAsciiAdjacentToNonAsciiLetter(phrase)) {
            return true;
        }

        Set<String> trustedAsciiTokens = new HashSet<>();
        for (WorkoutDictionary d : dictionaries) {
            addAsciiToken(trustedAsciiTokens, d.getDisplayNameEn());
            for (String syn : synonymsByCanonical.getOrDefault(d.getCanonicalKey(), List.of())) {
                addAsciiToken(trustedAsciiTokens, syn);
            }
        }
        if (trustedAsciiTokens.isEmpty()) return false;

        for (String word : phrase.replaceAll("[^\\p{L}\\p{N}]+", " ").split("\\s+")) {
            if (word.isBlank() || !isAsciiLetters(word) || trustedAsciiTokens.contains(word)) {
                continue;
            }
            for (String token : trustedAsciiTokens) {
                if (word.contains(token)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void addAsciiToken(Set<String> tokens, String raw) {
        String token = TextNorm.normalize(raw);
        if (isAsciiLetters(token)) {
            tokens.add(token);
        }
    }

    private static boolean hasAsciiAdjacentToNonAsciiLetter(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int before = i - 1;
            int after = i + Character.charCount(cp);
            if (Character.isLetter(cp) && cp > 127
                    && (isAsciiLetterOrDigitAt(s, before) || isAsciiLetterOrDigitAt(s, after))) {
                return true;
            }
            i = after;
        }
        return false;
    }

    private static boolean containsNonAsciiLetter(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (Character.isLetter(cp) && cp > 127) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    private static boolean isAsciiLetters(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 'a' || c > 'z') return false;
        }
        return true;
    }

    /** 語言別自動核准門檻（CJK/阿文/泰文更嚴） */
    public static double autoApproveThreshold(String localeTag) {
        if (localeTag == null) return 0.95;
        String t = localeTag.toLowerCase(Locale.ROOT);
        if (t.startsWith("zh") || t.startsWith("ja") || t.startsWith("ko") || t.startsWith("ar") || t.startsWith("th")) {
            return 0.97;
        }
        return 0.95;
    }

    /** 中信心下限 */
    public static double mediumThreshold() { return 0.85; }
}
