package com.calai.backend.workout.nlp;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多語時長解析：支援 1:30 / 1h30 / 1.5h / 1,5 h / X 個半小時 / X and a half hours / X hora y media / X hora e meia
 */
public final class DurationParser {
    private DurationParser() {}

    /* ========= 正規化後的單位 ========= */
    // 長字優先：分鐘/分钟 放在「分」之前；補上「印地文：मिनट」
    private static final String MIN_UNITS_NORM =
            "(?:min|mins|minute|minutes|minuto|minutos|minuta|minuty|minut|minuten|minuut|minuter|"
                    +  "dakika|мин|минута|минуты|минут|دقيقة|دقائق|דקות|मिनट|phut|นาที|menit|minit|"
                    +  "分鐘|分钟|分|분)";

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
                    +  "half\\s+(?:an\\s+)?hour\\b"
                    + "|半\\s*(?:個|个)?\\s*(?:小時|小时|鐘頭|鐘|時間)"
                    + "|demi\\s*-?\\s*heure(?:s)?\\b"
                    + "|halbe\\s*stunde\\b"
                    + "|mezz'?\\s*ora\\b|mezza\\s*ora\\b"
                    + "|half\\s*uur\\b"
                    + "|p(?:oł|ol)\\s*godzin(?:a|y|e)?\\.?\\b|p(?:oł|ol)\\s*godz\\.?\\b"
                    + "|yarim\\s*saat\\b"
                    + "|полчаса\\b|пол\\s*часа\\b"
                    + "|نصف\\s*ساعة"
                    + "|חצי\\s*שעה"
                    + "|nua\\s*gio\\b|nửa\\s*giờ\\b"
                    + "|ครึ่ง\\s*ชั่วโมง"
                    + "|setengah\\s*jam\\b"
                    + "|半時間\\b|반\\s*시간\\b"
                    + ")",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS
    );

    // ========= 複合句型 =========
    private static final Pattern P_EN_X_AND_HALF_HOURS =
            Pattern.compile("(\\d{1,3})\\s*and\\s*a\\s*half\\s*(?:h|hr|hrs|hour|hours)",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern P_EN_X_HOUR_AND_HALF =
            Pattern.compile("(\\d{1,3})\\s*(?:h|hr|hrs|hour|hours)\\s*and\\s*a\\s*half",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern P_ZH_X_HALF_HOURS =
            Pattern.compile("(\\d{1,3})\\s*(?:個|个)?\\s*半\\s*(?:小時|小时|鐘頭|鐘|時間)",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern P_ES_X_HORA_Y_MEDIA =
            Pattern.compile("(\\d{1,3})\\s*hora(?:s)?\\s*y\\s*media",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern P_PT_X_HORA_E_MEIA =
            Pattern.compile("(\\d{1,3})\\s*hora(?:s)?\\s*e\\s*meia",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    // ========= 冒號 & 小數小時 =========
    private static final Pattern P_COLON = Pattern.compile("(\\d{1,3})\\s*[:：]\\s*(\\d{1,2})");
    private static final String HR_UNITS_RAW =
            "(?:h|hr|hrs|hour|hours|hora|horas|ore|uur|std|stunden|godz|saat|час|часы|ساعة|ساعات|שעה|"
                    +  "giờ|gio|ชั่วโมง|jam|小时|小時|時間|시간)";
    private static final Pattern P_HR_DECIMAL =
            Pattern.compile("(\\d{1,3})\\s*[\\.,]\\s*(\\d{1,3})\\s*" + HR_UNITS_RAW,
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    // 泰文半小時（raw）避免 normalize 影響
    private static final Pattern P_THAI_HALF_RAW =
            Pattern.compile("ครึ่ง\\s*ชั่วโมง", Pattern.UNICODE_CHARACTER_CLASS);

    /** 解析分鐘數；無法解析回 null */
    public static Integer parseMinutes(String text) {
        if (text == null) return null;

        String pre = cropAfterFirstExtraIfColon(text);
        String s = pre
                .replaceAll("(\\d{1,3})\\s*[:：]\\s*(\\d{1,2})", "$1 h $2 min")
                .replaceAll("(\\d{1,3})\\s*h\\s*(\\d{1,2})", "$1 h $2 min");
        s = expandFractionalHours(s);

        final boolean thaiHalfRaw = P_THAI_HALF_RAW.matcher(pre).find();
        String norm = TextNorm.normalize(s);

        int total = 0;
        boolean matchedComplexHalf = false;

        ConsumeResult r1 = consumeAndAddHoursPlusHalf(norm, P_EN_X_AND_HALF_HOURS);
        norm = r1.text; total += r1.minutes; matchedComplexHalf |= r1.matched;

        ConsumeResult r2 = consumeAndAddHoursPlusHalf(norm, P_ZH_X_HALF_HOURS);
        norm = r2.text; total += r2.minutes; matchedComplexHalf |= r2.matched;

        Matcher hr = P_HR.matcher(norm);
        while (hr.find()) total += Integer.parseInt(hr.group(1)) * 60;

        Matcher mn = P_MIN.matcher(norm);
        while (mn.find()) total += Integer.parseInt(mn.group(1));

        Matcher enHalf2 = P_EN_X_HOUR_AND_HALF.matcher(norm);
        while (enHalf2.find()) { total += 30; matchedComplexHalf = true; }

        Matcher esHalf = P_ES_X_HORA_Y_MEDIA.matcher(norm);
        while (esHalf.find()) { total += 30; matchedComplexHalf = true; }

        Matcher ptHalf = P_PT_X_HORA_E_MEIA.matcher(norm);
        while (ptHalf.find()) { total += 30; matchedComplexHalf = true; }

        if (!matchedComplexHalf && (thaiHalfRaw || P_HALF_ALONE.matcher(norm).find())) {
            total += 30;
        }
        return total > 0 ? total : null;
    }

    private static ConsumeResult consumeAndAddHoursPlusHalf(String src, Pattern pattern) {
        Matcher m = pattern.matcher(src);
        StringBuffer sb = new StringBuffer();
        int add = 0; boolean matched = false;
        while (m.find()) {
            int h = Integer.parseInt(m.group(1));
            add += h * 60 + 30; matched = true;
            m.appendReplacement(sb, " ");
        }
        m.appendTail(sb);
        return new ConsumeResult(sb.toString(), add, matched);
    }

    private static final class ConsumeResult {
        final String text; final int minutes; final boolean matched;
        ConsumeResult(String t, int m, boolean k) { text = t; minutes = m; matched = k; }
    }

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
        int plus = s.indexOf('+', from), minus = s.indexOf('-', from);
        if (plus < 0) return minus;
        if (minus < 0) return plus;
        return Math.min(plus, minus);
    }

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

    private static double parseFraction(String digits) {
        if (digits == null || digits.isEmpty()) return 0.0;
        double denom = Math.pow(10, digits.length());
        return Math.max(0.0, Math.min(1.0, Integer.parseInt(digits) / denom));
    }
}
