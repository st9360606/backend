package com.calai.backend.workout.nlp;

import java.text.Normalizer;
import java.text.Normalizer.Form;
import java.util.Locale;

/**
 * 安全正規化流程：
 * 1) NFKC
 * 2) 僅對「拉丁字母」去重音（保留韓/泰/天城文等腳本的結合符號）
 * 3) 去常見標點（保留語內空格與全部字母/數字）
 * 4) 映射阿拉伯/天城文數字到 ASCII
 * 5) toLowerCase + 壓縮空白
 */
public final class TextNorm {
    private TextNorm() {}

    public static String normalize(String s) {
        if (s == null || s.isEmpty()) return "";
        // 1) NFKC
        String nfkc = Normalizer.normalize(s, Form.NFKC);

        // 2) 僅對「拉丁字母」去重音（其他腳本保留結合符號）
        StringBuilder sb = new StringBuilder(nfkc.length());
        boolean prevBaseIsLatin = false;
        for (int i = 0; i < nfkc.length(); ) {
            final int cp = nfkc.codePointAt(i);
            final int type = Character.getType(cp);
            if (type == Character.NON_SPACING_MARK
                    || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK) {
                // 只在上一個基底字元為「拉丁」時才丟棄（越南文等拉丁變音 → 無重音）
                if (!prevBaseIsLatin) {
                    sb.appendCodePoint(cp); // 非拉丁腳本：保留（例如 थाई/देवनागरी）
                }
            } else {
                sb.appendCodePoint(cp);
                prevBaseIsLatin = isLatin(cp);
            }
            i += Character.charCount(cp);
        }
        String t = sb.toString();

        // 3) 移除常見標點/符號（保留語內空格）
        t = t.replaceAll("[\\p{Punct}，。！？・·、؛،﹑]", " ");

        // 4) 阿拉伯-印度與天城文數字 → 拉丁
        t = mapEasternDigitsToLatin(t);

        // 5) 小寫 + 壓縮空白
        return t.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static boolean isLatin(int codePoint) {
        Character.UnicodeBlock b = Character.UnicodeBlock.of(codePoint);
        return b == Character.UnicodeBlock.BASIC_LATIN
                || b == Character.UnicodeBlock.LATIN_1_SUPPLEMENT
                || b == Character.UnicodeBlock.LATIN_EXTENDED_A
                || b == Character.UnicodeBlock.LATIN_EXTENDED_B
                || b == Character.UnicodeBlock.LATIN_EXTENDED_ADDITIONAL
                || b == Character.UnicodeBlock.LATIN_EXTENDED_C
                || b == Character.UnicodeBlock.LATIN_EXTENDED_D
                || b == Character.UnicodeBlock.LATIN_EXTENDED_E;
    }

    /** 映射阿拉伯-印度(0660..0669)與天城文(0966..096F)數字到 ASCII '0'..'9' */
    private static String mapEasternDigitsToLatin(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x0660 && c <= 0x0669) { // Arabic-Indic
                out.append((char) ('0' + (c - 0x0660)));
            } else if (c >= 0x0966 && c <= 0x096F) { // Devanagari
                out.append((char) ('0' + (c - 0x0966)));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
