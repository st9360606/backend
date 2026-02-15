package com.calai.backend.foodlog;

import com.calai.backend.foodlog.dto.FoodLogErrorResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import static org.assertj.core.api.Assertions.assertThat;

@JsonTest
class FoodLogErrorResponseJsonTest {

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void should_serialize_errorCode_and_code_alias() {
        FoodLogErrorResponse r = new FoodLogErrorResponse(
                "COOLDOWN_ACTIVE",
                "COOLDOWN_ACTIVE",
                "req-123",
                "RETRY_LATER",
                10,
                "2026-02-15T00:00:10Z",
                10, // ✅ 原本 10L -> 改成 10 (Integer)
                1,
                "OVER_QUOTA",
                "MODEL_TIER_LOW"
        );

        JsonNode n = objectMapper.valueToTree(r);

        assertThat(n.get("errorCode").asText()).isEqualTo("COOLDOWN_ACTIVE");
        assertThat(n.get("code").asText()).isEqualTo("COOLDOWN_ACTIVE");
    }
}
