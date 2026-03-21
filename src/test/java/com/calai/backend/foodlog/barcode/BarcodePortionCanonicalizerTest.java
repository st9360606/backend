package com.calai.backend.foodlog.barcode;

import com.calai.backend.foodlog.barcode.openfoodfacts.mapper.OpenFoodFactsMapper.OffResult;
import com.calai.backend.foodlog.barcode.normalize.BarcodePortionCanonicalizer;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BarcodePortionCanonicalizerTest {

    @Test
    @DisplayName("eff 為 null 時應安全略過")
    void should_do_nothing_when_eff_is_null() {
        assertDoesNotThrow(() ->
                BarcodePortionCanonicalizer.canonicalize(null, sampleOff("g"), "WHOLE_PACKAGE")
        );
    }

    @Test
    @DisplayName("WHOLE_PACKAGE + g 應設為 1 PACK")
    void should_set_pack_for_whole_package_when_unit_is_g() {
        ObjectNode eff = JsonNodeFactory.instance.objectNode();

        BarcodePortionCanonicalizer.canonicalize(eff, sampleOff("g"), "WHOLE_PACKAGE");

        ObjectNode quantity = (ObjectNode) eff.get("quantity");
        assertNotNull(quantity);
        assertEquals(1.0, quantity.get("value").asDouble());
        assertEquals("PACK", quantity.get("unit").asText());
    }

    @Test
    @DisplayName("WHOLE_PACKAGE + ml 應設為 1 BOTTLE")
    void should_set_bottle_for_whole_package_when_unit_is_ml() {
        ObjectNode eff = JsonNodeFactory.instance.objectNode();

        BarcodePortionCanonicalizer.canonicalize(eff, sampleOff("ml"), "WHOLE_PACKAGE");

        ObjectNode quantity = (ObjectNode) eff.get("quantity");
        assertNotNull(quantity);
        assertEquals(1.0, quantity.get("value").asDouble());
        assertEquals("BOTTLE", quantity.get("unit").asText());
    }

    @Test
    @DisplayName("WHOLE_PACKAGE + null unit 應 fallback 為 1 PACK")
    void should_fallback_to_pack_for_whole_package_when_unit_is_null() {
        ObjectNode eff = JsonNodeFactory.instance.objectNode();

        BarcodePortionCanonicalizer.canonicalize(eff, sampleOff(null), "WHOLE_PACKAGE");

        ObjectNode quantity = (ObjectNode) eff.get("quantity");
        assertNotNull(quantity);
        assertEquals(1.0, quantity.get("value").asDouble());
        assertEquals("PACK", quantity.get("unit").asText());
    }

    @Test
    @DisplayName("WHOLE_PACKAGE + unknown unit 應 fallback 為 1 PACK")
    void should_fallback_to_pack_for_whole_package_when_unit_unknown() {
        ObjectNode eff = JsonNodeFactory.instance.objectNode();

        BarcodePortionCanonicalizer.canonicalize(eff, sampleOff("kg"), "WHOLE_PACKAGE");

        ObjectNode quantity = (ObjectNode) eff.get("quantity");
        assertNotNull(quantity);
        assertEquals(1.0, quantity.get("value").asDouble());
        assertEquals("PACK", quantity.get("unit").asText());
    }

    @Test
    @DisplayName("PER_SERVING 應設為 1 SERVING")
    void should_set_serving_for_per_serving() {
        ObjectNode eff = JsonNodeFactory.instance.objectNode();

        BarcodePortionCanonicalizer.canonicalize(eff, sampleOff("g"), "PER_SERVING");

        ObjectNode quantity = (ObjectNode) eff.get("quantity");
        assertNotNull(quantity);
        assertEquals(1.0, quantity.get("value").asDouble());
        assertEquals("SERVING", quantity.get("unit").asText());
    }

    @Test
    @DisplayName("ESTIMATED_PORTION 應設為 1 SERVING")
    void should_set_serving_for_estimated_portion() {
        ObjectNode eff = JsonNodeFactory.instance.objectNode();

        BarcodePortionCanonicalizer.canonicalize(eff, sampleOff("g"), "ESTIMATED_PORTION");

        ObjectNode quantity = (ObjectNode) eff.get("quantity");
        assertNotNull(quantity);
        assertEquals(1.0, quantity.get("value").asDouble());
        assertEquals("SERVING", quantity.get("unit").asText());
    }

    @Test
    @DisplayName("fallback：若 quantity 不存在，應補 1 SERVING")
    void should_fill_default_quantity_when_fallback_and_quantity_missing() {
        ObjectNode eff = JsonNodeFactory.instance.objectNode();

        BarcodePortionCanonicalizer.canonicalize(eff, sampleOff("g"), "UNKNOWN_BASIS");

        ObjectNode quantity = (ObjectNode) eff.get("quantity");
        assertNotNull(quantity);
        assertEquals(1.0, quantity.get("value").asDouble());
        assertEquals("SERVING", quantity.get("unit").asText());
    }

    @Test
    @DisplayName("fallback：若 quantity 已有 value 與 unit，應保留原值")
    void should_keep_existing_quantity_when_fallback_and_both_present() {
        ObjectNode eff = JsonNodeFactory.instance.objectNode();
        ObjectNode quantity = eff.putObject("quantity");
        quantity.put("value", 2.5);
        quantity.put("unit", "PACK");

        BarcodePortionCanonicalizer.canonicalize(eff, sampleOff("g"), "UNKNOWN_BASIS");

        assertEquals(2.5, quantity.get("value").asDouble());
        assertEquals("PACK", quantity.get("unit").asText());
    }

    @Test
    @DisplayName("fallback：若缺 value，應補 1.0")
    void should_fill_default_value_when_missing_in_fallback() {
        ObjectNode eff = JsonNodeFactory.instance.objectNode();
        ObjectNode quantity = eff.putObject("quantity");
        quantity.put("unit", "BOTTLE");

        BarcodePortionCanonicalizer.canonicalize(eff, sampleOff("ml"), "UNKNOWN_BASIS");

        assertEquals(1.0, quantity.get("value").asDouble());
        assertEquals("BOTTLE", quantity.get("unit").asText());
    }

    @Test
    @DisplayName("fallback：若缺 unit，應補 SERVING")
    void should_fill_default_unit_when_missing_in_fallback() {
        ObjectNode eff = JsonNodeFactory.instance.objectNode();
        ObjectNode quantity = eff.putObject("quantity");
        quantity.put("value", 3.0);

        BarcodePortionCanonicalizer.canonicalize(eff, sampleOff("g"), "UNKNOWN_BASIS");

        assertEquals(3.0, quantity.get("value").asDouble());
        assertEquals("SERVING", quantity.get("unit").asText());
    }

    @Test
    @DisplayName("fallback：若 unit 是空字串，應補 SERVING")
    void should_fill_default_unit_when_blank_in_fallback() {
        ObjectNode eff = JsonNodeFactory.instance.objectNode();
        ObjectNode quantity = eff.putObject("quantity");
        quantity.put("value", 3.0);
        quantity.put("unit", "   ");

        BarcodePortionCanonicalizer.canonicalize(eff, sampleOff("g"), "UNKNOWN_BASIS");

        assertEquals(3.0, quantity.get("value").asDouble());
        assertEquals("SERVING", quantity.get("unit").asText());
    }

    private static OffResult sampleOff(String packageUnit) {
        return new OffResult(
                "Sample Product",

                500.0,
                8.0,
                24.0,
                60.0,
                3.0,
                20.0,
                450.0,

                250.0,
                4.0,
                12.0,
                30.0,
                1.5,
                10.0,
                225.0,

                100.0,
                packageUnit,
                List.of("snacks")
        );
    }
}
