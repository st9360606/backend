package com.calai.backend.foodlog.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FoodLogEnvelopeJsonTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void envelope_serialization_contains_expected_fields() throws Exception {
        FoodLogEnvelope env = new FoodLogEnvelope(
                "id-1",
                "DRAFT",
                "DG-0",
                "MODEL_TIER_HIGH",
                false,
                new FoodLogEnvelope.NutritionResult(
                        "Unknown food",
                        new FoodLogEnvelope.Quantity(1.0, "SERVING"),
                        new FoodLogEnvelope.Nutrients(
                                120.0,
                                5.0,
                                4.0,
                                16.0,
                                2.0,
                                6.0,
                                180.0
                        ),
                        6,
                        0.2,
                        List.of("UNKNOWN_FOOD", "LOW_CONFIDENCE"),
                        "UNKNOWN_FOOD",
                        null, // foodCategory
                        null, // foodSubCategory
                        "model could not confidently identify the food", // _reasoning
                        null, // labelMeta
                        null, // aiMeta
                        new FoodLogEnvelope.Source(
                                "ALBUM",
                                "GEMINI",
                                "GEMINI"
                        )
                ),
                null, // task
                null, // error
                null, // hints
                new FoodLogEnvelope.Trace("rid-1")
        );

        String json = om.writeValueAsString(env);

        assertThat(json).contains("\"foodLogId\"");
        assertThat(json).contains("\"status\"");
        assertThat(json).contains("\"nutritionResult\"");
        assertThat(json).contains("\"trace\"");

        // v1.2 / v1.3 契約欄位
        assertThat(json).contains("\"tierUsed\"");
        assertThat(json).contains("\"fromCache\"");
        assertThat(json).contains("\"warnings\"");
        assertThat(json).contains("\"degradedReason\"");

        // Source 新欄位驗收
        assertThat(json).contains("\"source\"");
        assertThat(json).contains("\"resolvedBy\"");

        // 新增欄位驗收
        assertThat(json).contains("\"_reasoning\"");
    }
}
