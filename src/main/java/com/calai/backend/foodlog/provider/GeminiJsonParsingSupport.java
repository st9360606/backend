package com.calai.backend.foodlog.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

public final class GeminiJsonParsingSupport {

    private final ObjectMapper om;

    public GeminiJsonParsingSupport(ObjectMapper om) {
        this.om = om;
    }

    public JsonNode tryParseJson(String rawText) {
        if (rawText == null) return null;

        String s = stripFence(rawText.trim());
        if (s.isBlank()) return null;

        s = stripBomAndNulls(s);

        // 1) 先直接 parse 原文，避免過度修補反而把原本可 parse 的 JSON 搞壞
        JsonNode direct = tryReadTree(s);
        if (direct != null) {
            return direct;
        }

        s = escapeBadCharsInsideJsonStrings(s);

        String payload = extractFirstJsonPayload(s);
        if (payload == null || payload.isBlank()) return null;

        // 2) 先直接 parse 抽出的 payload
        JsonNode payloadNode = tryReadTree(payload);
        if (payloadNode != null) {
            return payloadNode;
        }

        // 3) 基本修補
        String repaired = patchDanglingTail(payload);
        repaired = balanceJsonIfNeeded(repaired);
        repaired = removeTrailingCommas(repaired);
        repaired = patchDanglingTail(repaired);

        JsonNode repairedNode = tryReadTree(repaired);
        if (repairedNode != null) {
            return repairedNode;
        }

        // 4) 關鍵修正：一路往前裁掉壞尾巴，直到能 parse
        JsonNode dropTailNode = tryParseByDroppingBrokenTail(repaired);
        if (dropTailNode != null) {
            return dropTailNode;
        }

        return tryParseByDroppingBrokenTail(payload);
    }

    private JsonNode tryReadTree(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return om.readTree(s);
        } catch (Exception ignore) {
            return null;
        }
    }

    private JsonNode tryParseByDroppingBrokenTail(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }

        String candidate = s;

        // 最多往前裁 16 次，避免無限 loop
        for (int i = 0; i < 16; i++) {
            String repaired = patchDanglingTail(candidate);
            repaired = balanceJsonIfNeeded(repaired);
            repaired = removeTrailingCommas(repaired);
            repaired = patchDanglingTail(repaired);

            JsonNode node = tryReadTree(repaired);
            if (node != null) {
                return node;
            }

            int cut = findLastCommaOutsideString(candidate);
            if (cut < 0) {
                break;
            }

            candidate = rtrim(candidate.substring(0, cut));
            if (candidate.isBlank()) {
                break;
            }
        }

        return null;
    }

    private static int findLastCommaOutsideString(String s) {
        if (s == null || s.isBlank()) {
            return -1;
        }
        boolean inString = false;
        boolean escaped = false;
        int lastComma = -1;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (inString) {
                if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
                continue;
            }
            if (ch == ',') {
                lastComma = i;
            }
        }
        return lastComma;
    }

    public boolean isJsonTruncated(String text) {
        if (text == null || text.isBlank()) return false;

        String trimmed = text.trim();
        if (!trimmed.startsWith("{")) return false;

        int balance = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (inString) {
                if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }

            if (ch == '"') {
                inString = true;
            } else if (ch == '{') {
                balance++;
            } else if (ch == '}') {
                balance--;
            }
        }

        return balance > 0 || !trimmed.endsWith("}");
    }

    private static String patchDanglingTail(String s) {
        if (s == null) return null;
        String t = rtrim(s);

        if (t.endsWith(":")) {
            String key = extractLastJsonKeyBeforeColon(t);
            if (key != null) {
                String lower = key.toLowerCase(Locale.ROOT);
                if (lower.equals("unit")) {
                    t = t + "\"SERVING\"";
                } else if (lower.equals("basis")) {
                    t = t + "\"PER_SERVING\"";
                } else {
                    t = t + "null";
                }
            } else {
                t = t + "null";
            }
        }
        return t;
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

    private static String stripFence(String s) {
        if (s == null) return "";
        if (!s.contains("```")) return s.trim();

        int first = s.indexOf("```");
        int firstNl = s.indexOf('\n', first);
        if (firstNl > 0) s = s.substring(firstNl + 1);
        int lastFence = s.lastIndexOf("```");
        if (lastFence >= 0) s = s.substring(0, lastFence);
        return s.trim();
    }

    private static String extractFirstJsonPayload(String s) {
        if (s == null) return null;

        int iObj = s.indexOf('{');
        int iArr = s.indexOf('[');

        int start;
        if (iObj < 0 && iArr < 0) return null;
        if (iObj < 0) start = iArr;
        else if (iArr < 0) start = iObj;
        else start = Math.min(iObj, iArr);

        boolean inString = false;
        boolean escaped = false;

        int brace = 0;
        int bracket = 0;

        for (int i = start; i < s.length(); i++) {
            char ch = s.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }

            if (inString) {
                if (ch == '\\') {
                    escaped = true;
                    continue;
                }
                if (ch == '"') {
                    inString = false;
                }
                continue;
            } else {
                if (ch == '"') {
                    inString = true;
                    continue;
                }
                if (ch == '{') brace++;
                else if (ch == '}') brace--;
                else if (ch == '[') bracket++;
                else if (ch == ']') bracket--;

                if (brace == 0 && bracket == 0) {
                    return s.substring(start, i + 1).trim();
                }
            }
        }

        return s.substring(start).trim();
    }

    private static String balanceJsonIfNeeded(String s) {
        if (s == null || s.isBlank()) return s;

        boolean inString = false;
        boolean escaped = false;
        Deque<Character> stack = new ArrayDeque<>();

        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }

            if (inString) {
                if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }

            if (ch == '"') {
                inString = true;
                continue;
            }

            if (ch == '{' || ch == '[') {
                stack.push(ch);
            } else if (ch == '}' || ch == ']') {
                if (!stack.isEmpty()) {
                    char top = stack.peek();
                    if ((top == '{' && ch == '}') || (top == '[' && ch == ']')) {
                        stack.pop();
                    }
                }
            }
        }

        StringBuilder out = new StringBuilder(s);

        // ✅ 新增：若 JSON 在字串中途被截斷，先補上結尾雙引號
        if (inString) {
            out.append('"');
        }

        while (!stack.isEmpty()) {
            char open = stack.pop();
            out.append(open == '{' ? '}' : ']');
        }

        return out.toString();
    }

    private static String stripBomAndNulls(String s) {
        if (s == null) return "";
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') s = s.substring(1);
        return s.replace("\u0000", "");
    }

    private static String escapeBadCharsInsideJsonStrings(String s) {
        if (s == null || s.isEmpty()) return s;

        StringBuilder out = new StringBuilder(s.length() + 16);
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);

            if (!inString) {
                if (ch == '"') inString = true;
                if (ch == '\u0000') continue;
                if (ch < 0x20 && ch != '\n' && ch != '\r' && ch != '\t') continue;
                out.append(ch);
                continue;
            }

            if (escaped) {
                out.append(ch);
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                out.append(ch);
                escaped = true;
                continue;
            }
            if (ch == '"') {
                out.append(ch);
                inString = false;
                continue;
            }
            if (ch == '\r' || ch == '\n') {
                out.append("\\n");
                continue;
            }
            if (ch < 0x20) {
                out.append(' ');
                continue;
            }
            out.append(ch);
        }

        return out.toString();
    }

    private static String removeTrailingCommas(String s) {
        if (s == null || s.isEmpty()) return s;

        StringBuilder out = new StringBuilder(s.length());
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);

            if (escaped) {
                out.append(ch);
                escaped = false;
                continue;
            }
            if (inString) {
                out.append(ch);
                if (ch == '\\') escaped = true;
                else if (ch == '"') inString = false;
                continue;
            }
            if (ch == '"') {
                inString = true;
                out.append(ch);
                continue;
            }
            if (ch == ',') {
                int j = i + 1;
                while (j < s.length() && Character.isWhitespace(s.charAt(j))) j++;
                if (j < s.length()) {
                    char nx = s.charAt(j);
                    if (nx == '}' || nx == ']') continue;
                }
            }
            out.append(ch);
        }

        return out.toString();
    }
}
