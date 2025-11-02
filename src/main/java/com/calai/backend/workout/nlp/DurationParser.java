package com.calai.backend.workout.nlp;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多語時長解析（含小數小時、半小時句型）：
 * - 支援 1:30 / 1h30 / 1.5h / 1,5 h / 1 個半小時 / 1 and a half hours / 1 hora y media / 1 hora e meia
 * - 若同時含「冒號時間」與多段 + / -，只保留冒號時間後的第一段，避免過度累加
 */
public final class DurationParser {
    private DurationParser() {}

    /* ========= 正規化後的單位 ========= */
    private static final String MIN_UNITS_NORM =
            "(?:min|mins|minute|minutes|minuto|minutos|minuta|minuty|minut|minuten|minuut|minuter|"
                    +  "dakika|мин|минута|минуты|минут|دقيقة|دقائق|דקות|phut|นาที|menit|minit|分|分鐘|分钟|분)";

    private static final String HR_UNITS_NORM  =
            "(?:h|hr|hrs|hour|hours|hora|horas|ore|uur|std|stunden|godz|saat|час|часы|ساعة|ساعات|שעה|"
                    +  "gio|ชั่วโมง|jam|小时|小時|時間|시간)";

    private static final Pattern P_MIN = Pattern.compile("(\\d{1,4})\\s*" + MIN_UNITS_NORM,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern P_HR = Pattern.compile("(\\d{1,3})\\s*" + HR_UNITS_NORM,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    /* ========= 半小時（獨立詞）========= */
    private static final Pattern P_HALF_ALONE = Pattern.compile(
            "(?:"
                    +  "half\\s+(?:an\\s+)?hour\\b"                       // en
                    + "|半\\s*(?:個|个)?\\s*(?:小時|小时|鐘頭|鐘|時間)"       // zh
                    + "|demi\\s*-?\\s*heure(?:s)?\\b"                     // fr
                    + "|halbe\\s*stunde\\b"                               // de
                    + "|mezz'?\\s*ora\\b|mezza\\s*ora\\b"                 // it
                    + "|half\\s*uur\\b"                                   // nl
                    + "|p(?:oł|ol)\\s*godzin(?:a|y|e)?\\.?\\b|p(?:oł|ol)\\s*godz\\.?\\b" // pl（含去重音）
                    + "|yarim\\s*saat\\b"                                 // tr（yarım → yarim）
                    + "|полчаса\\b|пол\\s*часа\\b"                        // ru
                    + "|نصف\\s*ساعة"                                      // ar
                    + "|חצי\\s*שעה"                                       // he
                    + "|nua\\s*gio\\b|nửa\\s*giờ\\b"                      // vi（含去重音/原字）
                    + "|ครึ่ง\\s*ชั่วโมง"                                 // th
                    + "|setengah\\s*jam\\b"                               // id/ms
                    + "|半時間\\b|반\\s*시간\\b"                            // ja/ko
                    + ")",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS
    );

    /* ========= 複合句型 ========= */
    private static final Pattern P_EN_X_AND_HALF_HOURS = Pattern.compile(
            "(\\d{1,3})\\s*and\\s*a\\s*half\\s*(?:h|hr|hrs|hour|hours)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern P_EN_X_HOUR_AND_HALF = Pattern.compile(
            "(\\d{1,3})\\s*(?:h|hr|hrs|hour|hours)\\s*and\\s*a\\s*half",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern P_ZH_X_HALF_HOURS = Pattern.compile(
            "(\\d{1,3})\\s*(?:個|个)?\\s*半\\s*(?:小時|小时|鐘頭|鐘|時間)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern P_ES_X_HORA_Y_MEDIA = Pattern.compile(
            "(\\d{1,3})\\s*hora(?:s)?\\s*y\\s*media",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern P_PT_X_HORA_E_MEIA = Pattern.compile(
            "(\\d{1,3})\\s*hora(?:s)?\\s*e\\s*meia",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    /* ========= 冒號 & 小數小時 ========= */
    private static final Pattern P_COLON = Pattern.compile("(\\d{1,3})\\s*[:：]\\s*(\\d{1,2})");

    // 注意：這裡是「正規化前」先展開，所以單位採 raw 寫法（盡量覆蓋常見）
    private static final String HR_UNITS_RAW =
            "(?:h|hr|hrs|hour|hours|hora|horas|ore|uur|std|stunden|godz|saat|час|часы|ساعة|ساعات|שעה|"
                    +  "giờ|gio|ชั่วโมง|jam|小时|小時|時間|시간)";
    private static final Pattern P_HR_DECIMAL = Pattern.compile(
            "(\\d{1,3})\\s*[\\.,]\\s*(\\d{1,3})\\s*" + HR_UNITS_RAW,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    // 泰文 raw（normalize 可能破壞關鍵字，故保留原字檢測）
    private static final Pattern P_THAI_HALF_RAW =
            Pattern.compile("ครึ่ง\\s*ชั่วโมง", Pattern.UNICODE_CHARACTER_CLASS);

    /** 解析分鐘數；無法解析回 null */
    public static Integer parseMinutes(String text) {
        if (text == null) return null;

        // 若同時含冒號時間與多段 + / -，只保留冒號時間 + 第一段
        String pre = cropAfterFirstExtraIfColon(text);

        // 展開 1:30 / 1h30 → "1 h 30 min"
        String s = pre
                .replaceAll("(\\d{1,3})\\s*[:：]\\s*(\\d{1,2})", "$1 h $2 min")
                .replaceAll("(\\d{1,3})\\s*h\\s*(\\d{1,2})", "$1 h $2 min");

        // 展開 1.5h / 1,5 h / 1.5 小時
        s = expandFractionalHours(s);

        // raw 泰文半小時旗標（正規化前）
        final boolean thaiHalfRaw = P_THAI_HALF_RAW.matcher(pre).find();

        // 正規化（NFKC、去重音、轉小寫、清標點）
        String norm = TextNorm.normalize(s);

        int total = 0;
        boolean matchedComplexHalf = false;

        // (A) 先處理「數字不緊貼 hour 的複合句型」→ 直接加 X*60+30，並從字串剔除，避免重複加總
        ConsumeResult r1 = consumeAndAddHoursPlusHalf(norm, P_EN_X_AND_HALF_HOURS);
        norm = r1.text; total += r1.minutes; matchedComplexHalf |= r1.matched;

        ConsumeResult r2 = consumeAndAddHoursPlusHalf(norm, P_ZH_X_HALF_HOURS);
        norm = r2.text; total += r2.minutes; matchedComplexHalf |= r2.matched;

        // (B) 一般小時/分鐘（可重複出現）
        Matcher hr = P_HR.matcher(norm);
        while (hr.find()) total += Integer.parseInt(hr.group(1)) * 60;

        Matcher mn = P_MIN.matcher(norm);
        while (mn.find()) total += Integer.parseInt(mn.group(1));

        // (C) 其餘複合句型（數字緊貼 hour）：只補 +30，避免與 (B) 的小時重複
        Matcher enHalf2 = P_EN_X_HOUR_AND_HALF.matcher(norm);
        while (enHalf2.find()) { total += 30; matchedComplexHalf = true; }

        Matcher esHalf = P_ES_X_HORA_Y_MEDIA.matcher(norm);
        while (esHalf.find()) { total += 30; matchedComplexHalf = true; }

        Matcher ptHalf = P_PT_X_HORA_E_MEIA.matcher(norm);
        while (ptHalf.find()) { total += 30; matchedComplexHalf = true; }

        // (D) 半小時獨立詞（若已命中複合句型則不再加）
        if (!matchedComplexHalf && (thaiHalfRaw || P_HALF_ALONE.matcher(norm).find())) {
            total += 30;
        }

        return total > 0 ? total : null;
    }

    /** 將 "X and a half hours" / "X 個半小時" 直接加 X*60+30，並把該片段自字串剔除 */
    private static ConsumeResult consumeAndAddHoursPlusHalf(String src, Pattern pattern) {
        Matcher m = pattern.matcher(src);
        StringBuffer sb = new StringBuffer();
        int add = 0;
        boolean matched = false;
        while (m.find()) {
            int h = Integer.parseInt(m.group(1));
            add += h * 60 + 30;
            matched = true;
            m.appendReplacement(sb, " "); // 移除匹配片段
        }
        m.appendTail(sb);
        return new ConsumeResult(sb.toString(), add, matched);
    }

    private static final class ConsumeResult {
        final String text; final int minutes; final boolean matched;
        ConsumeResult(String t, int m, boolean k) { text = t; minutes = m; matched = k; }
    }

    /** 若同時含冒號時間與多段 + / -，僅保留冒號時間以及第一個 + / - 片段 */
    private static String cropAfterFirstExtraIfColon(String src) {
        Matcher m = P_COLON.matcher(src);
        if (!m.find()) return src;
        int lastEnd = 0; m.reset();
        while (m.find()) lastEnd = m.end();

        int firstOp = indexOfOp(src, lastEnd);
        if (firstOp < 0) return src;
        int secondOp = indexOfOp(src, firstOp + 1);
        if (secondOp < 0) return src;

        return src.substring(0, secondOp);
    }

    private static int indexOfOp(String s, int from) {
        int plus = s.indexOf('+', from);
        int minus = s.indexOf('-', from);
        if (plus < 0) return minus;
        if (minus < 0) return plus;
        return Math.min(plus, minus);
    }

    /** 將 "X.Y hours" 或 "X,Y 小時" 展開為 "X h Y min" */
    private static String expandFractionalHours(String src) {
        Matcher m = P_HR_DECIMAL.matcher(src);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String intPart = m.group(1);
            String fracStr = m.group(2);
            double frac = parseFraction(fracStr);
            int min = (int) Math.round(frac * 60.0);
            int hours = Integer.parseInt(intPart);
            if (min >= 60) { hours += 1; min -= 60; }
            String replacement = (min > 0) ? (hours + " h " + min + " min") : (hours + " h");
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** "5" → 0.5；"25" → 0.25；"375" → 0.375 */
    private static double parseFraction(String digits) {
        if (digits == null || digits.isEmpty()) return 0.0;
        double denom = Math.pow(10, digits.length());
        return Math.max(0.0, Math.min(1.0, Integer.parseInt(digits) / denom));
    }
}
