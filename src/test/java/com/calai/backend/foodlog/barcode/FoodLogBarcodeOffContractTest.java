package com.calai.backend.foodlog.barcode;

import com.calai.backend.Integration_testing.config.TestAuthConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@Import(TestAuthConfig.class)
public class FoodLogBarcodeOffContractTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    // ✅ 改 mock 這個：FoodLogService 實際依賴的是 offLookup
    @MockitoBean OpenFoodFactsLookupService offLookup;

    @Test
    void barcode_found_should_return_draft_with_nutrients() throws Exception {
        String bc = "1234567890123";

        String offJson = """
        {
          "status": 1,
          "product": {
            "product_name": "Test Cookies",
            "nutriments": {
              "energy-kcal_100g": 520,
              "proteins_100g": 8,
              "fat_100g": 30,
              "carbohydrates_100g": 55
            }
          }
        }
        """;

        JsonNode root = om.readTree(offJson);

        // ✅ preferredLangTag 在你的 controller 可能是 null，所以用 any()
        when(offLookup.getProduct(eq(bc), any())).thenReturn(root);

        String body = """
        { "barcode": "%s" }
        """.formatted(bc);

        String resp = mvc.perform(post("/api/v1/food-logs/barcode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = om.readTree(resp);
        assertThat(json.path("status").asText()).isEqualTo("DRAFT");
        assertThat(json.path("nutritionResult").path("nutrients").path("kcal").asDouble()).isEqualTo(520.0);
        assertThat(json.path("nutritionResult").path("source").path("method").asText()).isEqualTo("BARCODE");

        verify(offLookup, times(1)).getProduct(eq(bc), any());
    }

    @Test
    void barcode_not_found_should_return_failed_and_try_label() throws Exception {
        String bc = "0000000000000";

        ObjectNode notFound = om.createObjectNode();
        notFound.put("status", 0);

        when(offLookup.getProduct(eq(bc), any())).thenReturn(notFound);

        String body = """
    { "barcode": "%s" }
    """.formatted(bc);

        String resp = mvc.perform(post("/api/v1/food-logs/barcode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = om.readTree(resp);
        assertThat(json.path("status").asText()).isEqualTo("FAILED");
        assertThat(json.path("error").path("errorCode").asText()).isEqualTo("BARCODE_NOT_FOUND");
        assertThat(json.path("error").path("clientAction").asText()).isEqualTo("TRY_LABEL");

        verify(offLookup, times(1)).getProduct(eq(bc), any());
    }
}