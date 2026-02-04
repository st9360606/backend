package com.calai.backend.foodlog;

import com.calai.backend.foodlog.model.ProviderRefuseReason;
import com.calai.backend.foodlog.provider.GeminiRefusalDetector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GeminiRefusalDetectorTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void detect_finishReason_safety() throws Exception {
        JsonNode resp = om.readTree("""
        { "candidates":[ { "finishReason":"SAFETY" } ] }
        """);
        assertEquals(ProviderRefuseReason.SAFETY, GeminiRefusalDetector.detectOrNull(resp));
    }

    @Test
    void detect_finishReason_recitation() throws Exception {
        JsonNode resp = om.readTree("""
        { "candidates":[ { "finishReason":"RECITATION" } ] }
        """);
        assertEquals(ProviderRefuseReason.RECITATION, GeminiRefusalDetector.detectOrNull(resp));
    }

    @Test
    void detect_blocked_safetyRatings() throws Exception {
        JsonNode resp = om.readTree("""
        { "candidates":[ { "safetyRatings":[{"category":"HARM","blocked":true}] } ] }
        """);
        assertEquals(ProviderRefuseReason.HARM_CATEGORY, GeminiRefusalDetector.detectOrNull(resp));
    }
}