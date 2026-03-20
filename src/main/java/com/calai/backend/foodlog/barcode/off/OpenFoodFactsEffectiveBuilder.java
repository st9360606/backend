package com.calai.backend.foodlog.barcode.off;

import com.calai.backend.foodlog.barcode.mapper.OpenFoodFactsMapper.OffResult;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class OpenFoodFactsEffectiveBuilder {

    private OpenFoodFactsEffectiveBuilder() {}

    /**
     * 任一營養欄位非 null 就算有「任何營養」。
     * 用途：
     * - debug / telemetry / 後續更細分 fallback 判斷
     * 不建議直接拿來決定 BARCODE / AUTO_BARCODE / NAME_SEARCH 成功。
     */
    public static boolean hasAnyNutrition(OffResult off) {
        if (off == null) return false;

        return off.kcalPer100g() != null
               || off.proteinPer100g() != null
               || off.fatPer100g() != null
               || off.carbsPer100g() != null
               || off.fiberPer100g() != null
               || off.sugarPer100g() != null
               || off.sodiumMgPer100g() != null
               || off.kcalPerServing() != null
               || off.proteinPerServing() != null
               || off.fatPerServing() != null
               || off.carbsPerServing() != null
               || off.fiberPerServing() != null
               || off.sugarPerServing() != null
               || off.sodiumMgPerServing() != null;
    }

    /**
     * ✅ P0-1：真正可用營養（usable nutrition）
     * 規則：
     * - 至少 kcal / protein / fat / carbs 任一非 null
     * - 不能只靠 fiber / sugar / sodium 撐成功
     * 這個方法會被：
     * - BARCODE success
     * - AUTO_BARCODE
     * - NAME_SEARCH rescue
     * 直接使用，因此這裡要嚴格。
     */
    public static boolean hasUsableNutrition(OffResult off) {
        return hasCoreNutrition(off);
    }

    /**
     * 核心營養資料判定：
     * - kcal / protein / fat / carbs 任一非 null 即算有核心營養
     * - 適合用來決定 confidence / warning / rescue acceptability
     */
    public static boolean hasCoreNutrition(OffResult off) {
        if (off == null) return false;

        return off.kcalPer100g() != null
               || off.proteinPer100g() != null
               || off.fatPer100g() != null
               || off.carbsPer100g() != null
               || off.kcalPerServing() != null
               || off.proteinPerServing() != null
               || off.fatPerServing() != null
               || off.carbsPerServing() != null;
    }

    public static String applyPortion(ObjectNode eff, OffResult off, boolean allowWholePackageScale) {
        if (eff == null) {
            throw new IllegalArgumentException("effective object is required");
        }
        if (off == null) {
            throw new IllegalArgumentException("off result is required");
        }

        boolean hasPkg = off.packageSizeValue() != null
                         && off.packageSizeValue() > 0
                         && off.packageSizeUnit() != null
                         && !off.packageSizeUnit().isBlank();

        boolean hasAnyPer100 = off.kcalPer100g() != null
                               || off.proteinPer100g() != null
                               || off.fatPer100g() != null
                               || off.carbsPer100g() != null
                               || off.fiberPer100g() != null
                               || off.sugarPer100g() != null
                               || off.sodiumMgPer100g() != null;

        // ✅ 只有在呼叫端明確允許時，才把 per100 乘成 whole package
        // 避免 NAME_SEARCH 命中同名不同包裝時，把數值算錯成整包
        if (allowWholePackageScale && hasPkg && hasAnyPer100) {
            double pkg = off.packageSizeValue();

            ObjectNode qty = eff.putObject("quantity");
            qty.put("value", 1.0);
            qty.put("unit", "SERVING");

            double factor = pkg / 100.0;

            ObjectNode n = eff.putObject("nutrients");
            putNumOrNull(n, "kcal", mul(off.kcalPer100g(), factor));
            putNumOrNull(n, "protein", mul(off.proteinPer100g(), factor));
            putNumOrNull(n, "fat", mul(off.fatPer100g(), factor));
            putNumOrNull(n, "carbs", mul(off.carbsPer100g(), factor));
            putNumOrNull(n, "fiber", mul(off.fiberPer100g(), factor));
            putNumOrNull(n, "sugar", mul(off.sugarPer100g(), factor));
            putNumOrNull(n, "sodium", mul(off.sodiumMgPer100g(), factor));

            return "WHOLE_PACKAGE";
        }

        boolean hasAnyServing = off.kcalPerServing() != null
                                || off.proteinPerServing() != null
                                || off.fatPerServing() != null
                                || off.carbsPerServing() != null
                                || off.fiberPerServing() != null
                                || off.sugarPerServing() != null
                                || off.sodiumMgPerServing() != null;

        if (hasAnyServing) {
            ObjectNode qty = eff.putObject("quantity");
            qty.put("value", 1.0);
            qty.put("unit", "SERVING");

            ObjectNode n = eff.putObject("nutrients");
            putNumOrNull(n, "kcal", off.kcalPerServing());
            putNumOrNull(n, "protein", off.proteinPerServing());
            putNumOrNull(n, "fat", off.fatPerServing());
            putNumOrNull(n, "carbs", off.carbsPerServing());
            putNumOrNull(n, "fiber", off.fiberPerServing());
            putNumOrNull(n, "sugar", off.sugarPerServing());
            putNumOrNull(n, "sodium", off.sodiumMgPerServing());

            return "PER_SERVING";
        }

        ObjectNode qty = eff.putObject("quantity");
        qty.put("value", 100.0);

        String per100Unit = "GRAM";
        if (off.packageSizeUnit() != null && off.packageSizeUnit().equalsIgnoreCase("ml")) {
            per100Unit = "ML";
        }
        qty.put("unit", per100Unit);

        ObjectNode n = eff.putObject("nutrients");
        putNumOrNull(n, "kcal", off.kcalPer100g());
        putNumOrNull(n, "protein", off.proteinPer100g());
        putNumOrNull(n, "fat", off.fatPer100g());
        putNumOrNull(n, "carbs", off.carbsPer100g());
        putNumOrNull(n, "fiber", off.fiberPer100g());
        putNumOrNull(n, "sugar", off.sugarPer100g());
        putNumOrNull(n, "sodium", off.sodiumMgPer100g());

        return "PER_100";
    }

    private static Double mul(Double value, double factor) {
        return value == null ? null : value * factor;
    }

    private static void putNumOrNull(ObjectNode obj, String field, Double value) {
        if (value == null || !Double.isFinite(value)) {
            obj.putNull(field);
        } else {
            obj.put(field, value);
        }
    }
}
