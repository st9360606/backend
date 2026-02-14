package com.calai.backend.foodlog;

import com.calai.backend.foodlog.dto.FoodLogEnvelope;
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
                "MODEL_TIER_HIGH", // ✅ tierUsed（新欄位）
                false,             // ✅ fromCache（新欄位）
                new FoodLogEnvelope.NutritionResult(
                        "Unknown food",
                        new FoodLogEnvelope.Quantity(1.0, "SERVING"),
                        new FoodLogEnvelope.Nutrients(120.0, 5.0, 4.0, 16.0, 2.0, 6.0, 180.0),
                        6,                 // healthScore
                        0.2,               // confidence
                        List.of("UNKNOWN_FOOD", "LOW_CONFIDENCE"), // ✅ warnings
                        "UNKNOWN_FOOD",    // ✅ degradedReason
                        new FoodLogEnvelope.Source("ALBUM", "STUB")
                ),
                null,
                null,
                new FoodLogEnvelope.Trace("rid-1")
        );

        String json = om.writeValueAsString(env);

        assertThat(json).contains("\"foodLogId\"");
        assertThat(json).contains("\"status\"");
        assertThat(json).contains("\"nutritionResult\"");
        assertThat(json).contains("\"trace\"");

        // ✅ v1.2：新欄位硬驗收（避免未來有人改壞）
        assertThat(json).contains("\"tierUsed\"");
        assertThat(json).contains("\"fromCache\"");

        // ✅ 既有欄位也硬驗收
        assertThat(json).contains("\"warnings\"");
        assertThat(json).contains("\"degradedReason\"");
    }
}
