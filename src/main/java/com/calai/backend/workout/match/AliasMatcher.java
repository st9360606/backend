package com.calai.backend.workout.match;

import com.calai.backend.workout.entity.WorkoutDictionary;
import com.calai.backend.workout.nlp.Similarity;
import com.calai.backend.workout.nlp.TextNorm;

import java.util.*;

public final class AliasMatcher {

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
        Candidate best = null;

        for (WorkoutDictionary d : dictionaries) {
            String base = TextNorm.normalize(d.getDisplayNameEn());

            double sName = Similarity.jaroWinkler(p, base);
            double sMax = sName;
            String why = "name";

            List<String> syns = synonymsByCanonical.getOrDefault(d.getCanonicalKey(), List.of());
            for (String syn : syns) {
                double sv = Similarity.jaroWinkler(p, syn);
                if (sv > sMax) { sMax = sv; why = "syn"; }
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
