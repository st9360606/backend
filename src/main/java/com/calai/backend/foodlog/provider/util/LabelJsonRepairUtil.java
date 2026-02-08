package com.calai.backend.foodlog.provider.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 專門處理 Gemini 在 LABEL 場景常見的「JSON 被截斷」：
 * 1) 尾巴卡在 ,"
 * 2) 尾巴卡在 :
 * 3) number 卡在 140.（JSON 不合法）
 * 4) 就算 JSON 無法 parse，也要能從破碎字串抽出 nutrients/quantity/confidence
 */
public final class LabelJsonRepairUtil {

    private LabelJsonRepairUtil() {}

    // ---- public API ----

    public static ObjectNode repairOrExtract(ObjectMapper om, String rawText, boolean labelStrict) {
        if (rawText == null || rawText.isBlank()) return null;

        // 先嘗試：修到可以 parse 成 JSON
        JsonNode parsed = tryParseJson(om, rawText);
        if (parsed != null && parsed.isObject()) return (ObjectNode) parsed;

        // 不可 parse：從破碎 JSON-like 字串抽值（餅乾那張可直接救回蛋白/脂肪/碳水/糖/鈉）
        return extractFromBrokenJsonLikeText(om, rawText, labelStrict);
    }

    /** 判斷是否「太短/早斷」：像藍莓那張只到 kcal 就斷掉，通常需要重打 vision。 */
    public static boolean looksLikeEarlyTruncation(String rawText) {
        if (rawText == null) return false;
        String t = rawText.trim();

        // 很短、或 nutrients 裡只看到 kcal 看不到 carbs/sugar/fiber/protein 等
        boolean tooShort = t.length() < 140;
        boolean hasKcal = t.contains("\"kcal\"");
        boolean missingMost = !(t.contains("\"carbs\"") || t.contains("\"sugar\"") || t.contains("\"fiber\"")
                                || t.contains("\"protein\"") || t.contains("\"fat\""));

        // ends with digit dot = 140. 也是典型「中途斷」
        boolean endsWithDanglingNumberDot = t.matches(".*\\d+\\.$");

        return (hasKcal && missingMost) || tooShort || endsWithDanglingNumberDot;
    }

    // ---- internals: parse/repair ----

    private static JsonNode tryParseJson(ObjectMapper om, String raw) {
        String payload = extractFirstJsonPayload(raw);
        if (payload == null) return null;

        payload = sanitizeDanglingTail(payload);
        payload = fixDanglingNumberDot(payload);
        payload = balanceJsonIfNeeded(payload);
        payload = removeTrailingCommas(payload);
        payload = sanitizeDanglingTail(payload); // 再跑一次比較保險

        try {
            return om.readTree(payload);
        } catch (Exception ignore) {
            return null;
        }
    }

    /**
     * 把尾巴卡在： ,"
     * 或 , "warn
     * 這種「key 寫到一半」直接截掉最後一段，避免餅乾那張永遠 parse 失敗。
     */
    private static String sanitizeDanglingTail(String s) {
        if (s == null) return null;
        String t = rtrim(s);

        // case 1: endsWith ":"（你原本就有）
        if (t.endsWith(":")) {
            String key = extractLastJsonKeyBeforeColon(t);
            if (key != null) {
                String lower = key.toLowerCase(Locale.ROOT);
                if (lower.equals("unit")) return t + "\"SERVING\"";
                if (lower.equals("basis")) return t + "\"PER_SERVING\"";
            }
            return t + "null";
        }

        // case 2: endsWith ,"
        // 例： ... "confidence":0.95,"   或 ... ,"warn
        // 把最後一個逗號後面全部砍掉
        t = t.replaceFirst(",\\s*\"[^\\\"]*$", "");
        // 若最後變成逗號結尾，也順便移除
        t = t.replaceFirst(",\\s*$", "");

        return t;
    }

    /** 把 JSON 不合法的 140. 修成 140.0 */
    private static String fixDanglingNumberDot(String s) {
        if (s == null) return null;
        // 只針對「結尾」的 140.，不要亂動中間正常的小數
        return s.replaceFirst("(\\d+)\\.$", "$1.0");
    }

    private static String removeTrailingCommas(String s) {
        if (s == null) return null;
        String t = s;
        t = t.replaceAll(",\\s*([}\\]])", "$1");
        t = t.replaceAll(",\\s*$", "");
        return t;
    }

    private static String balanceJsonIfNeeded(String s) {
        if (s == null) return null;
        int openObj = 0, closeObj = 0, openArr = 0, closeArr = 0;
        boolean inStr = false, esc = false;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (esc) { esc = false; continue; }
                if (c == '\\') { esc = true; continue; }
                if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') { inStr = true; continue; }
            if (c == '{') openObj++;
            else if (c == '}') closeObj++;
            else if (c == '[') openArr++;
            else if (c == ']') closeArr++;
        }

        StringBuilder sb = new StringBuilder(s);
        int needArr = Math.max(0, openArr - closeArr);
        int needObj = Math.max(0, openObj - closeObj);

        for (int i = 0; i < needArr; i++) sb.append(']');
        for (int i = 0; i < needObj; i++) sb.append('}');
        return sb.toString();
    }

    /**
     * 嘗試從文字中擷取第一個 JSON {...}（避免前後有雜訊）
     */
    private static String extractFirstJsonPayload(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        if (start < 0) return null;

        boolean inStr = false, esc = false;
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inStr) {
                if (esc) { esc = false; continue; }
                if (c == '\\') { esc = true; continue; }
                if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') { inStr = true; continue; }
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return text.substring(start, i + 1);
            }
        }
        // 找不到完整結尾也先回傳（交給 balanceJsonIfNeeded + sanitize）
        return text.substring(start);
    }

    private static String rtrim(String s) {
        int i = s.length();
        while (i > 0 && Character.isWhitespace(s.charAt(i - 1))) i--;
        return s.substring(0, i);
    }

    private static String extractLastJsonKeyBeforeColon(String t) {
        int colon = t.length() - 1;
        int q2 = t.lastIndexOf('"', colon - 1);
        if (q2 < 0) return null;
        int q1 = t.lastIndexOf('"', q2 - 1);
        if (q1 < 0) return null;
        return t.substring(q1 + 1, q2);
    }

    // ---- internals: extract values from broken JSON-like ----

    private static ObjectNode extractFromBrokenJsonLikeText(ObjectMapper om, String text, boolean labelStrict) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();

        // foodName
        String foodName = extractJsonStringValue(text, "foodName");
        out.put("foodName", foodName);

        // quantity
        ObjectNode qty = out.putObject("quantity");
        Double qv = extractJsonNumberValue(text, "value"); // quantity.value
        String unit = extractJsonStringValue(text, "unit");
        if (qv == null) qty.putNull("value"); else qty.put("value", qv);
        if (unit == null) qty.putNull("unit"); else qty.put("unit", unit);

        // nutrients（✅把你原本只救 kcal/sodium 擴充成 7 大營養素）
        ObjectNode n = out.putObject("nutrients");
        putNumOrNull(n, "kcal",   extractJsonNumberValue(text, "kcal"));
        putNumOrNull(n, "protein",extractJsonNumberValue(text, "protein"));
        putNumOrNull(n, "fat",    extractJsonNumberValue(text, "fat"));
        putNumOrNull(n, "carbs",  extractJsonNumberValue(text, "carbs"));
        putNumOrNull(n, "fiber",  extractJsonNumberValue(text, "fiber"));
        putNumOrNull(n, "sugar",  extractJsonNumberValue(text, "sugar"));
        putNumOrNull(n, "sodium", extractJsonNumberValue(text, "sodium"));

        // confidence
        Double conf = extractJsonNumberValue(text, "confidence");
        if (conf == null) out.put("confidence", 0.25);
        else out.put("confidence", conf);

        // labelStrict：補齊 warnings/labelMeta，避免 normalize 階段因缺 key 再爆
        if (labelStrict) {
            if (!out.has("warnings")) out.set("warnings", JsonNodeFactory.instance.arrayNode());
            if (!out.has("labelMeta")) {
                ObjectNode lm = out.putObject("labelMeta");
                lm.putNull("servingsPerContainer");
                lm.putNull("basis");
            }
        }

        return out;
    }

    private static void putNumOrNull(ObjectNode obj, String key, Double val) {
        if (val == null) obj.putNull(key);
        else obj.put(key, val);
    }

    private static String extractJsonStringValue(String jsonLikeText, String fieldName) {
        if (jsonLikeText == null) return null;
        String pattern = "\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*\"([^\"]*)\"";
        Matcher m = Pattern.compile(pattern).matcher(jsonLikeText);
        if (m.find()) return m.group(1);
        if (jsonLikeText.contains("\"" + fieldName + "\":null")) return null;
        return null;
    }

    private static Double extractJsonNumberValue(String jsonLikeText, String fieldName) {
        if (jsonLikeText == null) return null;
        String pattern = "\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)";
        Matcher m = Pattern.compile(pattern).matcher(jsonLikeText);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group(1));
            } catch (Exception ignore) {
                return null;
            }
        }
        return null;
    }
}
