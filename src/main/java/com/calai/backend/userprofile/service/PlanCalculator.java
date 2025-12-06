package com.calai.backend.userprofile.service;

public class PlanCalculator {

    public enum Gender { Male, Female }
    public enum BmiClass { Underweight, Normal, Overweight, Obesity }

    public record MacroSplit(float proteinPct, float fatPct, float carbPct) {
        public MacroSplit normalized() {
            float sum = proteinPct + fatPct + carbPct;
            if (sum < 0.0001f) sum = 0.0001f;
            return new MacroSplit(proteinPct / sum, fatPct / sum, carbPct / sum);
        }
    }

    public record Inputs(
            Gender gender,
            int age,
            float heightCm,
            float weightKg,
            int workoutsPerWeek
    ) {}

    public record MacroPlan(
            int kcal,
            int carbsGrams,
            int proteinGrams,
            int fatGrams,
            double bmi,      // round1 後
            BmiClass bmiClass
    ) {}

    private static int bucketWorkouts(int v) {
        if (v <= 0) return 0;
        if (v <= 2) return 2;
        if (v <= 4) return 4;
        if (v <= 6) return 6;
        return 7;
    }

    /** Kotlin HealthCalc.bmr */
    public static double bmr(Inputs in) {
        double s = (in.gender == Gender.Male) ? 5.0 : -161.0;
        return 10.0 * in.weightKg
                + 6.25 * in.heightCm
                - 5.0 * in.age
                + s;
    }

    /** Kotlin HealthCalc.activityFactor */
    public static double activityFactor(int workoutsPerWeek) {
        return switch (bucketWorkouts(workoutsPerWeek)) {
            case 0 -> 1.20;
            case 2 -> 1.375;
            case 4 -> 1.55;
            case 6 -> 1.725;
            default -> 1.90;
        };
    }

    /** Kotlin HealthCalc.tdee */
    public static double tdee(Inputs in) {
        return bmr(in) * activityFactor(in.workoutsPerWeek);
    }

    /** Kotlin HealthCalc.splitForGoalKey（以你現在的實作為準） */
    public static MacroSplit splitForGoalKey(String goalKey) {
        String k = (goalKey == null) ? "" : goalKey.trim().toUpperCase();
        return switch (k) {
            case "LOSE" -> new MacroSplit(0.30f, 0.25f, 0.45f);
            case "MAINTAIN" -> new MacroSplit(0.25f, 0.30f, 0.45f);
            case "GAIN" -> new MacroSplit(0.30f, 0.25f, 0.45f);
            case "HEALTHY_EATING" -> new MacroSplit(0.20f, 0.30f, 0.50f);
            default -> new MacroSplit(0.25f, 0.30f, 0.45f);
        };
    }

    /** Kotlin HealthCalc.macroPlanBySplit */
    public static MacroPlan macroPlanBySplit(Inputs in, MacroSplit split, Integer goalKcal) {
        int kcal = Math.max(1000, (int) Math.round(goalKcal != null ? goalKcal : tdee(in)));
        MacroSplit norm = split.normalized();

        int proteinG = Math.round((kcal * norm.proteinPct) / 4.0f);
        int fatG = Math.round((kcal * norm.fatPct) / 9.0f);
        int carbsG = Math.round((kcal * norm.carbPct) / 4.0f);

        double bmiVal = bmi(in.weightKg, in.heightCm);
        BmiClass bmiClass = classifyBmi(bmiVal);

        return new MacroPlan(
                kcal,
                carbsG,
                proteinG,
                fatG,
                round1(bmiVal),
                bmiClass
        );
    }

    /** Kotlin HealthCalc.bmi（含最小高度保護） */
    public static double bmi(double weightKg, double heightCm) {
        double safeCm = Math.max(0.001, heightCm);
        double m = safeCm / 100.0;
        return weightKg / (m * m);
    }

    /** Kotlin HealthCalc.classifyBmi */
    public static BmiClass classifyBmi(double bmi) {
        if (bmi < 18.5) return BmiClass.Underweight;
        if (bmi < 25.0) return BmiClass.Normal;
        if (bmi < 30.0) return BmiClass.Overweight;
        return BmiClass.Obesity;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
