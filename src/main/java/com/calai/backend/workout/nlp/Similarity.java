package com.calai.backend.workout.nlp;

import java.util.List;
import java.util.Map;

/**
 * 統一的字串相似度入口。
 * - 正規化(TextNorm) → 委派 JaroWinkler.similarity(...)
 * - 額外提供依語言/類別詞的微量 boost（可外部化或改 DB）
 */
public final class Similarity {
    private Similarity(){}

    /** 對外：先做 TextNorm，再跑 Jaro-Winkler（委派單一實作） */
    public static double jaroWinkler(String s1, String s2) {
        if (s1 == null || s2 == null) return 0.0;
        String a = TextNorm.normalize(s1);
        String b = TextNorm.normalize(s2);
        return JaroWinkler.similarity(a, b); // ★ 委派到唯一實作，避免重複
    }

    /**
     * 同義詞/類別詞微加權（+0.05；可視線上數據調整或外部化）
     * - lang：語言（如 "zh-TW" / "en"），不識別時傳 "*"
     * - phrase：已正規化的使用者片語（此方法內會再正規化以保險）
     * - dictCanonical：字典 canonical key（例：running / cycling）
     */
    public static double boostBySynonyms(String lang, String phrase, String dictCanonical) {
        String norm = TextNorm.normalize(phrase);
        Map<String, List<String>> lex = LEX.getOrDefault(lang, LEX.get("*"));
        for (Map.Entry<String, List<String>> e : lex.entrySet()) {
            String key = e.getKey();
            if (!dictCanonical.contains(key)) continue;
            for (String token : e.getValue()) {
                if (norm.contains(token)) return 0.05;
            }
        }
        return 0.0;
    }

    /** 可替換為 DB 同義詞表（A11） */
    private static final Map<String, Map<String, List<String>>> LEX = Map.of(
            "*", Map.of(
                    "run",   List.of("run","running","jog","慢跑","跑步","ラン","ジョグ","달리기"),
                    "swim",  List.of("swim","游泳","수영","natación","natação"),
                    "cycle", List.of("cycle","bike","bicycle","騎車","自転車","자전거")
            ),
            "es", Map.of("run", List.of("correr","trote"))
    );
}
