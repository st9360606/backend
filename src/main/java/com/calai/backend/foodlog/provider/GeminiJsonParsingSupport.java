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
        s = escapeBadCharsInsideJsonStrings(s);

        String payload = extractFirstJsonPayload(s);
        if (payload == null || payload.isBlank()) return null;

        payload = patchDanglingTail(payload);
        payload = balanceJsonIfNeeded(payload);
        payload = removeTrailingCommas(payload);
        payload = patchDanglingTail(payload);

        try {
            return om.readTree(payload);
        } catch (Exception first) {
            String fixed = balanceJsonIfNeeded(payload);
            fixed = removeTrailingCommas(fixed);
            fixed = patchDanglingTail(fixed);
            try {
                return om.readTree(fixed);
            } catch (Exception second) {
                return null;
            }
        }
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
                if (ch == '\\') escaped = true;
                else if (ch == '"') inString = false;
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

        if (stack.isEmpty()) return s;

        StringBuilder out = new StringBuilder(s);
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
