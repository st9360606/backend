package com.calai.backend.users.profile.common;

public final class NutritionTargets {
    private NutritionTargets() {}

    // 纖維：USDA 常見建議值（你指定）
    public static final int DEFAULT_FIBER_G = 35;

    // 鈉：USDA 成人上限（你指定）
    public static final int DEFAULT_SODIUM_MG = 2300;

    /**
     * WHO free sugars 上限（一般建議 <10% energy）
     * Sugar_G = TDEE_kcal × 0.10 ÷ 4
     * ✅ 用 floor：避免因四捨五入導致 >10%
     */
    public static int SugarMaxG10(int kcal) {
        int safe = Math.max(0, kcal);
        return (int) Math.floor(safe * 0.10d / 4.0d);
    }

    // （可選）<5% 額外益處：如果未來你要做進階顯示
    public static int freeSugarMaxG5(int kcal) {
        int safe = Math.max(0, kcal);
        return (int) Math.floor(safe * 0.05d / 4.0d);
    }
}
