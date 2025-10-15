package com.calai.backend.userprofile.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Units {
    private Units() {}

    /** feet+inches → cm（允許 Short/Integer/BigDecimal…皆可） */
    public static Double feetInchesToCm(Number feet, Number inches) {
        if (feet == null || inches == null) return null;
        double ft = feet.doubleValue();
        double in = inches.doubleValue();
        double totalInches = ft * 12.0 + in;
        return round(totalInches * 2.54, 2);
    }

    /** lbs → kg（允許 Short/Integer/BigDecimal…皆可） */
    public static Double lbsToKg(Number lbs) {
        if (lbs == null) return null;
        double kg = lbs.doubleValue() / 2.2d;
        return round(kg, 2);
    }

    /** kg → 四捨五入的整數 lbs（資料庫如果要存整數 lbs，很好用） */
    public static BigDecimal kgToLbsInt(Number kg) {
        if (kg == null) return null;
        double lbs = kg.doubleValue() * 2.2d;
        return BigDecimal.valueOf(lbs).setScale(0, RoundingMode.HALF_UP);
    }

    /** cm → (feet, inches)；回傳 short[2] = {feet, inches} */
    public static short[] cmToFeetInches(Number cm) {
        if (cm == null) return null;
        double totalInches = cm.doubleValue() / 2.54d;
        int feet = (int) Math.floor(totalInches / 12.0d);
        int inches = (int) Math.round(totalInches - feet * 12.0d); // 四捨五入到整吋
        return new short[]{ (short) feet, (short) inches };
    }

    private static Double round(double v, int scale) {
        return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }
}
