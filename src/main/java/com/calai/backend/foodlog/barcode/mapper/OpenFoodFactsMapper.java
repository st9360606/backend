package com.calai.backend.foodlog.barcode.mapper;

import com.fasterxml.jackson.databind.JsonNode;

public final class OpenFoodFactsMapper {

    private OpenFoodFactsMapper() {}

    public record OffResult(
            String productName,
            Double kcalPer100g,
            Double proteinPer100g,
            Double fatPer100g,
            Double carbsPer100g
    ) {}

    public static OffResult map(JsonNode root) {
        if (root == null || root.isNull()) return null;
        int status = root.path("status").asInt(0);
        if (status != 1) return null;

        JsonNode product = root.path("product");
        JsonNode nutr = product.path("nutriments");

        Double kcal = numberOrNull(nutr, "energy-kcal_100g");
        Double p = numberOrNull(nutr, "proteins_100g");
        Double f = numberOrNull(nutr, "fat_100g");
        Double c = numberOrNull(nutr, "carbohydrates_100g");

        String name = product.path("product_name").asText(null);
        if (kcal == null && p == null && f == null && c == null) return null;

        return new OffResult(name, kcal, p, f, c);
    }

    private static Double numberOrNull(JsonNode node, String key) {
        JsonNode v = node.path(key);
        if (v.isMissingNode() || v.isNull()) return null;
        if (v.isNumber()) return v.asDouble();
        try { return Double.parseDouble(v.asText()); } catch (Exception e) { return null; }
    }
}
