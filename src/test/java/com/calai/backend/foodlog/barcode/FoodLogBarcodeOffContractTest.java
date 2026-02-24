package com.calai.backend.foodlog.barcode;

import com.calai.backend.Integration_testing.config.TestAuthConfig;
import com.calai.backend.foodlog.barcode.BarcodeLookupService.LookupResult;
import com.calai.backend.foodlog.barcode.mapper.OpenFoodFactsMapper.OffResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
class FoodLogBarcodeOffContractTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @MockitoBean BarcodeLookupService barcodeLookupService;

    @Test
    void barcode_found_should_return_draft_with_nutrients() throws Exception {
        String bc = "1234567890123";

        // ✅ 只需要提供 FoodLogService 會用到的欄位（per100g）
        OffResult off = new OffResult(
                "Test Cookies",

                // per 100g/ml
                520.0,  // kcalPer100g
                8.0,    // proteinPer100g
                30.0,   // fatPer100g
                55.0,   // carbsPer100g
                null,   // fiberPer100g
                null,   // sugarPer100g
                null,   // sodiumMgPer100g

                // per serving
                null, null, null, null, null, null, null,

                // package size
                null, null
        );

        when(barcodeLookupService.lookupOff(eq(bc), any()))
                .thenReturn(new LookupResult(
                        bc,                     // barcodeRaw
                        bc,                     // barcodeNorm
                        true,                   // found
                        false,                  // fromCache
                        "OPENFOODFACTS",        // provider
                        off                     // off
                ));

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

        verify(barcodeLookupService, times(1)).lookupOff(eq(bc), any());
    }

    @Test
    void barcode_not_found_should_return_failed_and_try_label() throws Exception {
        String bc = "0000000000000";

        when(barcodeLookupService.lookupOff(eq(bc), any()))
                .thenReturn(new LookupResult(
                        bc,
                        bc,
                        false,                  // found = false
                        false,
                        "OPENFOODFACTS",
                        null                    // off = null
                ));

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

        verify(barcodeLookupService, times(1)).lookupOff(eq(bc), any());
    }
}