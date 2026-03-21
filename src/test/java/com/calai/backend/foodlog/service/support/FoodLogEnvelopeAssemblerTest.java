package com.calai.backend.foodlog.service.support;

import com.calai.backend.foodlog.dto.FoodLogEnvelope;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.mapper.ClientActionMapper;
import com.calai.backend.foodlog.model.FoodLogStatus;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 先補最小測試，確保抽出 assembler 後核心行為沒有變。
 */
class FoodLogEnvelopeAssemblerTest {

    private final Clock clock = Clock.fixed(
            Instant.parse("2026-03-21T08:00:00Z"),
            ZoneOffset.UTC
    );

    private final ClientActionMapper clientActionMapper = new ClientActionMapper();

    private final FoodLogEnvelopeAssembler assembler =
            new FoodLogEnvelopeAssembler(clientActionMapper, clock);

    @Test
    void should_fill_barcode_missing_nutrients_with_zero() {
        FoodLogEntity e = new FoodLogEntity();
        e.setId("log-1");
        e.setMethod("BARCODE");
        e.setProvider("OPENFOODFACTS");
        e.setStatus(FoodLogStatus.DRAFT);
        e.setDegradeLevel("DG-0");

        ObjectNode effective = JsonNodeFactory.instance.objectNode();
        effective.put("foodName", "Test Product");

        ObjectNode quantity = effective.putObject("quantity");
        quantity.put("value", 100.0);
        quantity.put("unit", "GRAM");

        // 不放 nutrients，驗證 BARCODE view 是否仍補 0
        e.setEffective(effective);

        FoodLogEnvelope out = assembler.assemble(e, null, "req-1");

        assertNotNull(out);
        assertNotNull(out.nutritionResult());
        assertNotNull(out.nutritionResult().nutrients());

        assertEquals(0.0, out.nutritionResult().nutrients().kcal());
        assertEquals(0.0, out.nutritionResult().nutrients().protein());
        assertEquals(0.0, out.nutritionResult().nutrients().fat());
        assertEquals(0.0, out.nutritionResult().nutrients().carbs());
        assertEquals(0.0, out.nutritionResult().nutrients().fiber());
        assertEquals(0.0, out.nutritionResult().nutrients().sugar());
        assertEquals(0.0, out.nutritionResult().nutrients().sodium());
    }

    @Test
    void should_fallback_retry_after_to_20_when_provider_rate_limited() {
        FoodLogEntity e = new FoodLogEntity();
        e.setId("log-2");
        e.setMethod("PHOTO");
        e.setProvider("GEMINI");
        e.setStatus(FoodLogStatus.FAILED);
        e.setDegradeLevel("DG-0");
        e.setLastErrorCode("PROVIDER_RATE_LIMITED");
        e.setLastErrorMessage("provider limited");

        FoodLogEnvelope out = assembler.assemble(e, null, "req-2");

        assertNotNull(out);
        assertNotNull(out.error());
        assertEquals("PROVIDER_RATE_LIMITED", out.error().errorCode());
        assertEquals(20, out.error().retryAfterSec());
    }
}
