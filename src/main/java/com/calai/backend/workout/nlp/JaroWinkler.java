package com.calai.backend.workout.nlp;

public final class JaroWinkler {
    private JaroWinkler() {}

    public static double similarity(String s1, String s2) {
        if (s1 == null || s2 == null) return 0d;
        if (s1.equals(s2)) return 1d;
        int len1 = s1.length(), len2 = s2.length();
        int maxDist = Math.max(0, Math.max(len1, len2)/2 - 1);

        boolean[] m1 = new boolean[len1];
        boolean[] m2 = new boolean[len2];

        int matches = 0;
        for (int i=0; i<len1; i++) {
            int start = Math.max(0, i - maxDist);
            int end = Math.min(i + maxDist + 1, len2);
            for (int j=start; j<end; j++) {
                if (!m2[j] && s1.charAt(i) == s2.charAt(j)) {
                    m1[i] = true; m2[j] = true; matches++; break;
                }
            }
        }
        if (matches == 0) return 0d;

        int t = 0; int k = 0;
        for (int i=0; i<len1; i++) if (m1[i]) {
            while (!m2[k]) k++;
            if (s1.charAt(i) != s2.charAt(k)) t++;
            k++;
        }
        double jaro = ( (matches/(double)len1) + (matches/(double)len2) + ((matches - t/2.0)/matches) ) / 3.0;

        // prefix
        int prefix = 0;
        for (int i=0; i<Math.min(4, Math.min(len1, len2)); i++) {
            if (s1.charAt(i) == s2.charAt(i)) prefix++;
            else break;
        }
        return jaro + 0.1 * prefix * (1 - jaro);
    }
}
