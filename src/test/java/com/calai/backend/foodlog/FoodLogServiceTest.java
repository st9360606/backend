package com.calai.backend.foodlog.service;

import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.model.FoodLogStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FoodLogServiceTest {

    @Test
    void resetForRetry_should_clear_effective_and_errors() {
        FoodLogEntity log = new FoodLogEntity();

        ObjectMapper om = new ObjectMapper();
        ObjectNode effective = om.createObjectNode();
        effective.put("foodName", "Old Result");

        log.setStatus(FoodLogStatus.FAILED);
        log.setEffective(effective);
        log.setLastErrorCode("LOW_CONFIDENCE");
        log.setLastErrorMessage("old message");

        FoodLogService.resetForRetry(log);

        assertEquals(FoodLogStatus.PENDING, log.getStatus());
        assertNull(log.getEffective());
        assertNull(log.getLastErrorCode());
        assertNull(log.getLastErrorMessage());
    }
}