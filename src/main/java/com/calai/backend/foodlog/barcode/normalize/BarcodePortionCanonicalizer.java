package com.calai.backend.foodlog.barcode.normalize;

import com.calai.backend.foodlog.barcode.openfoodfacts.mapper.OpenFoodFactsMapper.OffResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class BarcodePortionCanonicalizer {

    private BarcodePortionCanonicalizer() {
    }

    public static void canonicalize(ObjectNode eff, OffResult off, String basis) {
        if (eff == null) {
            return;
        }

        ObjectNode quantity = ensureObj(eff, "quantity");

        if ("WHOLE_PACKAGE".equalsIgnoreCase(basis)) {
            quantity.put("value", 1.0);
            quantity.put("unit", inferWholePackageUnit(off));
            return;
        }

        if ("PER_SERVING".equalsIgnoreCase(basis)) {
            quantity.put("value", 1.0);
            quantity.put("unit", "SERVING");
            return;
        }

        if ("ESTIMATED_PORTION".equalsIgnoreCase(basis)) {
            quantity.put("value", 1.0);
            quantity.put("unit", "SERVING");
            return;
        }

        // fallback
        if (quantity.get("value") == null || quantity.get("value").isNull()) {
            quantity.put("value", 1.0);
        }
        if (quantity.get("unit") == null || quantity.get("unit").isNull() || quantity.get("unit").asText("").isBlank()) {
            quantity.put("unit", "SERVING");
        }
    }

    private static String inferWholePackageUnit(OffResult off) {
        if (off == null) {
            return "PACK";
        }

        String unit = off.packageSizeUnit();
        if (unit == null || unit.isBlank()) {
            return "PACK";
        }

        String u = unit.trim().toLowerCase();

        // OFF package size 目前通常是 g / ml
        // g 代表包裝食品，ml 比較像瓶/罐飲品
        return switch (u) {
            case "ml" -> "BOTTLE";
            case "g" -> "PACK";
            default -> "PACK";
        };
    }

    private static ObjectNode ensureObj(ObjectNode root, String field) {
        JsonNode n = root.get(field);
        if (n != null && n.isObject()) {
            return (ObjectNode) n;
        }
        ObjectNode out = root.objectNode();
        root.set(field, out);
        return out;
    }
}
