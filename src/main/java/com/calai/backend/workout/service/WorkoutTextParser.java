package com.calai.backend.workout.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 保守解析器：只做兩件事
 * - 抓分鐘數（支援「45分 / 45分鐘 / 45min / 45 mins / 45 minutes」）
 * - 抽出活動 token（去掉分鐘相關字串後的剩餘字）
 * 並避免任何 static heavy 初始化；Regex 用 lazy getter 包 try/catch。
 */
public final class WorkoutTextParser {

    public record Parsed(int minutes, String activityToken) {}

    private WorkoutTextParser() {}

    public static Parsed parse(String input, Locale locale) {
        String s = normalize(input);
        int minutes = extractMinutes(s);
        String token = extractToken(s);
        if (minutes <= 0) minutes = 30; // 合理的預設，避免 0 造成後續除以 0 或無效
        if (token.isBlank()) token = "other_exercise_moderate";
        return new Parsed(minutes, token);
    }

    private static String normalize(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFKC);
        return n.trim().replaceAll("\\s+", " ");
    }

    private static volatile Pattern MINUTES_PAT;

    private static Pattern minutesPattern() {
        Pattern p = MINUTES_PAT;
        if (p == null) {
            synchronized (WorkoutTextParser.class) {
                p = MINUTES_PAT;
                if (p == null) {
                    try {
                        // 支援：45分 / 45分鐘 / 45 min / 45mins / 45 minutes / 45m
                        MINUTES_PAT = p = Pattern.compile(
                                "(?i)(\\d{1,3})\\s*(?:分(?:鐘)?|minute(?:s)?|mins?|min|m)"
                        );
                    } catch (RuntimeException e) {
                        // 萬一 Regex 有問題，也不要在 class 初始化就炸；回退一個極簡版本
                        MINUTES_PAT = p = Pattern.compile("(\\d{1,3})");
                    }
                }
            }
        }
        return p;
    }

    private static int extractMinutes(String s) {
        Matcher m = minutesPattern().matcher(s);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignore) { /* fallthrough */ }
        }
        return 0;
    }

    private static String extractToken(String s) {
        // 把分鐘片段剔除，再取剩下字元當活動 token；英文小寫化，繁中保留原樣
        String cleaned = minutesPattern().matcher(s).replaceAll("").trim();
        // 去除連接符號
        cleaned = cleaned.replaceAll("[,，。．・・/\\\\]+", " ").trim();
        // 取第一個非空詞
        if (cleaned.isEmpty()) return "";
        // 常見： "running" / "慢跑" / "跑步"
        return cleaned.toLowerCase(Locale.ROOT);
    }
}
