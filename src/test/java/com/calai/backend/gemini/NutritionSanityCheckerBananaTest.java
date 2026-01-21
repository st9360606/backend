package com.calai.backend.gemini;

import com.calai.backend.foodlog.task.NutritionSanityChecker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NutritionSanityCheckerTest {

    private final ObjectMapper om = new ObjectMapper();
    private final NutritionSanityChecker checker = new NutritionSanityChecker();

    @Test
    void banana_should_NOT_add_sodium_warnings() throws Exception {
        // 你提供的 banana（3 servings / sodium 3mg）
        ObjectNode eff = (ObjectNode) om.readTree("""
        {
          "foodName": "Bananas",
          "quantity": {"unit":"SERVING","value":3.0},
          "nutrients": {
            "kcal": 315.0,
            "protein": 3.9,
            "fat": 1.2,
            "carbs": 81.0,
            "fiber": 9.3,
            "sugar": 42.0,
            "sodium": 3.0
          },
          "confidence": 0.95
        }
        """);

        checker.apply(eff);

        List<String> warnings = warningsOf(eff);

        // ✅ 不該出現這兩個（你已移除 sodium 規則）
        assertThat(warnings).doesNotContain("SODIUM_OUTLIER", "SODIUM_UNIT_SUSPECT");
    }

    @Test
    void unit_unknown_should_add_UNIT_UNKNOWN_even_if_nutrients_all_null() throws Exception {
        ObjectNode eff = (ObjectNode) om.readTree("""
        {
          "quantity": {"unit":"杯","value":1},
          "nutrients": {
            "kcal": null, "protein": null, "fat": null, "carbs": null,
            "fiber": null, "sugar": null, "sodium": null
          }
        }
        """);

        checker.apply(eff);

        assertThat(warningsOf(eff)).contains("UNIT_UNKNOWN");
    }

    @Test
    void quantity_outlier_should_add_QUANTITY_OUTLIER() throws Exception {
        ObjectNode eff = (ObjectNode) om.readTree("""
        {
          "quantity": {"unit":"GRAM","value":5000},
          "nutrients": {
            "kcal": 100.0, "protein": 10.0, "fat": 1.0, "carbs": 10.0,
            "fiber": 1.0, "sugar": 1.0, "sodium": 10.0
          }
        }
        """);

        checker.apply(eff);

        assertThat(warningsOf(eff)).contains("QUANTITY_OUTLIER");
    }

    @Test
    void kcal_outlier_should_add_KCAL_OUTLIER() throws Exception {
        ObjectNode eff = (ObjectNode) om.readTree("""
        {
          "quantity": {"unit":"SERVING","value":1},
          "nutrients": {
            "kcal": 3000.0, "protein": 10.0, "fat": 10.0, "carbs": 10.0,
            "fiber": 1.0, "sugar": 1.0, "sodium": 10.0
          }
        }
        """);

        checker.apply(eff);

        assertThat(warningsOf(eff)).contains("KCAL_OUTLIER");
    }

    @Test
    void macro_outlier_should_add_MACRO_OUTLIER() throws Exception {
        ObjectNode eff = (ObjectNode) om.readTree("""
        {
          "quantity": {"unit":"SERVING","value":1},
          "nutrients": {
            "kcal": 100.0, "protein": 10.0, "fat": 10.0, "carbs": 10.0,
            "fiber": 150.0, "sugar": 1.0, "sodium": 10.0
          }
        }
        """);

        checker.apply(eff);

        assertThat(warningsOf(eff)).contains("MACRO_OUTLIER");
    }

    @Test
    void kcal_macro_mismatch_should_add_MACRO_OUTLIER() throws Exception {
        // kcal=600，但三大算出約 170 => diff=430 & ratio>0.3 會觸發你目前的 mismatch 規則（MACRO_OUTLIER）
        ObjectNode eff = (ObjectNode) om.readTree("""
        {
          "quantity": {"unit":"SERVING","value":1},
          "nutrients": {
            "kcal": 600.0, "protein": 10.0, "fat": 10.0, "carbs": 10.0,
            "fiber": 1.0, "sugar": 1.0, "sodium": 10.0
          }
        }
        """);

        checker.apply(eff);

        assertThat(warningsOf(eff)).contains("MACRO_OUTLIER");
    }

    // =========================
    // helpers
    // =========================

    private static List<String> warningsOf(ObjectNode eff) {
        ArrayNode arr = (eff != null && eff.has("warnings") && eff.get("warnings").isArray())
                ? (ArrayNode) eff.get("warnings")
                : null;

        if (arr == null || arr.isEmpty()) return List.of();

        List<String> out = new ArrayList<>(arr.size());
        arr.forEach(n -> {
            if (n != null && !n.isNull()) out.add(n.asText());
        });
        return out;
    }
}
