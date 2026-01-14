package com.calai.backend.foodlog;

import com.calai.backend.foodlog.provider.LogMealProperties;
import com.calai.backend.foodlog.provider.LogMealProviderClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class LogMealProviderClientMappingTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void mapToEffective_shouldExtractFoodNameAndMacros_whenFieldsExist() throws Exception {
        String intakeJson = """
        {
          "dishes":[{"name":"Chicken salad"}],
          "nutrition":{"kcal":350,"protein":30,"fat":15,"carbs":20}
        }
        """;

        var props = new LogMealProperties(
                "https://api.logmeal.com",
                Duration.ofSeconds(5),
                Duration.ofSeconds(25),
                null
        );

        // ✅ 若你的 LogMealProviderClient 建構子是 (RestClient, LogMealProperties, LogMealTokenService)
        var client = new LogMealProviderClient(null, props, null);

        var intake = om.readTree(intakeJson);

        // 透過反射測 private method（短期可接受）
        var m = LogMealProviderClient.class.getDeclaredMethod("mapToEffective", com.fasterxml.jackson.databind.JsonNode.class);
        m.setAccessible(true);
        var effective = (com.fasterxml.jackson.databind.node.ObjectNode) m.invoke(client, intake);

        assertEquals("Chicken salad", effective.get("foodName").asText());
        var n = effective.get("nutrients");
        assertEquals(350d, n.get("kcal").asDouble());
        assertEquals(30d, n.get("protein").asDouble());
        assertEquals(15d, n.get("fat").asDouble());
        assertEquals(20d, n.get("carbs").asDouble());
    }
}
