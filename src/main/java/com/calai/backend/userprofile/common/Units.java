package com.calai.backend.userprofile.common;

public final class Units {
    private Units() {}

    // === 標準換算常數 ===
    // 1 lb = 0.45359237 kg
    private static final double KG_PER_LB = 0.45359237d;
    private static final double LBS_PER_KG = 1.0d / KG_PER_LB;

    /**
     * 無條件捨去到指定小數位（只給「伺服器自己做單位換算」時用）
     * 例如：kg ↔ lbs、cm ↔ inch 的衍生值。
     */
    public static Double floor(Number v, int scale) {
        if (v == null) return null;
        if (scale < 0) throw new IllegalArgumentException("scale must be >= 0");
        double d = v.doubleValue();
        double factor = Math.pow(10d, scale);
        // + 1e-8: 避免 40.1 變成 40.0999999 這類浮點誤差被多捨 0.1
        return Math.floor(d * factor + 1e-8) / factor;
    }

    /**
     * 只做 min/max 夾住，不動小數位。
     * 專門用在「來自 client 的原始輸入」（例如 HealthPlanScreen 傳來的 kg）。
     */
    public static Double clamp(Number v, double min, double max) {
        if (v == null) return null;
        double d = v.doubleValue();
        if (d < min) return min;
        if (d > max) return max;
        return d;
    }

    /** lbs → kg（伺服器換算；無條件捨去到 0.1 kg） */
    public static Double lbsToKg1(Number lbs) {
        if (lbs == null) return null;
        double kg = lbs.doubleValue() * KG_PER_LB;
        return floor(kg, 1); // 0.1 kg
    }

    /** 舊名稱的相容 wrapper（若其它地方還在呼叫 lbsToKg） */
    public static Double lbsToKg(Number lbs) {
        return lbsToKg1(lbs);
    }

    /** kg → lbs（伺服器換算；無條件捨去到 0.1 lbs） */
    public static Double kgToLbs1(Number kg) {
        if (kg == null) return null;
        double lbs = kg.doubleValue() * LBS_PER_KG;
        return floor(lbs, 1); // 0.1 lbs
    }

    /** cm → (feet, inches)；保持舊行為 */
    public static short[] cmToFeetInches(Number cm) {
        if (cm == null) return null;
        double totalInches = cm.doubleValue() / 2.54d;
        int feet = (int) Math.floor(totalInches / 12.0d);
        int inches = (int) Math.round(totalInches - feet * 12.0d);
        return new short[]{ (short) feet, (short) inches };
    }

    /** feet+inches → cm（無條件捨去到 0.1 cm） */
    public static Double feetInchesToCm(Number feet, Number inches) {
        if (feet == null || inches == null) return null;
        double ft = feet.doubleValue();
        double in = inches.doubleValue();
        double totalInches = ft * 12.0d + in;
        double cm = totalInches * 2.54d;
        return floor(cm, 1); // 0.1 cm
    }
}
