package com.calai.backend.workout.nlp;

import java.text.Normalizer;
import java.util.Locale;

/**
 * NFKC 正規化 → 去重音 → 去常見標點 → 小寫 → 壓縮空白
 * 並將阿拉伯-印度數字 (U+0660..U+0669) 與天城文數字 (U+0966..U+096F) 轉為 ASCII 0..9。
 * 注意：使用 Java 17 相容實作，不使用 replaceAll 的 lambda 版本。
 */
public final class TextNorm {
    private TextNorm() {}

    /** NFKC → 去重音 → 去標點 → 阿拉伯/天城數字轉拉丁 → 小寫 → 壓縮空白 */
    public static String normalize(String s) {
        if (s == null) return "";
        String t = Normalizer.normalize(s, Normalizer.Form.NFKC);

        // 去重音（NFD + 移除所有 Mn 類別）
        t = Normalizer.normalize(t, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");

        // 移除常見標點（保留語內空格）
        t = t.replaceAll("[\\p{Punct}，。！？・·、؛،﹑]", " ");

        // 阿拉伯/天城數字 → 拉丁
        t = mapEasternDigitsToLatin(t);

        // 小寫 + 壓縮空白
        t = t.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        return t;
    }

    private static String mapEasternDigitsToLatin(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // Arabic-Indic 0660-0669
            if (c >= 0x0660 && c <= 0x0669) {
                sb.append((char) ('0' + (c - 0x0660)));
            }
            // Devanagari 0966-096F
            else if (c >= 0x0966 && c <= 0x096F) {
                sb.append((char) ('0' + (c - 0x0966)));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
