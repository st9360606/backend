package com.calai.backend.foodlog.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiNutritionQualityGateTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void isAcceptablePhoto_should_accept_zero_calorie_whole_package_drink() {
        ObjectNode root = om.createObjectNode();
        root.put("foodName", "Coca-Cola Zero");

        ObjectNode quantity = root.putObject("quantity");
        quantity.put("value", 1.0);
        quantity.put("unit", "SERVING");

        ObjectNode nutrients = root.putObject("nutrients");
        nutrients.put("kcal", 0.0);
        nutrients.put("protein", 0.0);
        nutrients.put("fat", 0.0);
        nutrients.put("carbs", 0.0);
        nutrients.putNull("fiber");
        nutrients.putNull("sugar");
        nutrients.putNull("sodium");

        root.put("confidence", 0.95);
        root.putArray("warnings");

        ObjectNode labelMeta = root.putObject("labelMeta");
        labelMeta.put("servingsPerContainer", 1.0);
        labelMeta.put("basis", "WHOLE_PACKAGE");

        assertThat(GeminiNutritionQualityGate.isAcceptablePhoto(root)).isTrue();
    }

    @Test
    void isAcceptablePhoto_should_reject_packaged_result_if_not_whole_package() {
        ObjectNode root = om.createObjectNode();
        root.put("foodName", "Bourbon Chocoliere");

        ObjectNode quantity = root.putObject("quantity");
        quantity.put("value", 1.0);
        quantity.put("unit", "SERVING");

        ObjectNode nutrients = root.putObject("nutrients");
        nutrients.put("kcal", 100.0);
        nutrients.put("protein", 2.0);
        nutrients.put("fat", 3.0);
        nutrients.put("carbs", 10.0);
        nutrients.put("fiber", 1.0);
        nutrients.put("sugar", 5.0);
        nutrients.put("sodium", 80.0);

        root.put("confidence", 0.9);
        root.putArray("warnings");

        ObjectNode labelMeta = root.putObject("labelMeta");
        labelMeta.put("servingsPerContainer", 5.0);
        labelMeta.put("basis", "PER_SERVING");

        assertThat(GeminiNutritionQualityGate.isAcceptablePhoto(root)).isFalse();
    }
}
