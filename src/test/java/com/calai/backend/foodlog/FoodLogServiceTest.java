package com.calai.backend.foodlog.service;

import com.calai.backend.foodlog.entity.FoodLogEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FoodLogServiceTest {

    @Test
    void resolveTierUsedDisplay_barcode_should_return_BARCODE() {
        FoodLogEntity log = new FoodLogEntity();
        log.setMethod("BARCODE");
        log.setDegradeLevel("DG-0");

        assertEquals("BARCODE", FoodLogService.resolveTierUsedDisplay(log));
    }

    @Test
    void resolveTierUsedDisplay_dg0_should_return_MODEL_TIER_HIGH() {
        FoodLogEntity log = new FoodLogEntity();
        log.setMethod("PHOTO");
        log.setDegradeLevel("DG-0");

        assertEquals("MODEL_TIER_HIGH", FoodLogService.resolveTierUsedDisplay(log));
    }

    @Test
    void resolveTierUsedDisplay_null_entity_should_return_null() {
        assertNull(FoodLogService.resolveTierUsedDisplay(null));
    }
}
