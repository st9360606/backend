package com.calai.backend.foodlog.task;

import com.calai.backend.foodlog.entity.FoodLogTaskEntity;
import com.fasterxml.jackson.databind.JsonNode;

public interface ProviderClient {
    ProviderResult process(FoodLogTaskEntity task) throws Exception;

    record ProviderResult(JsonNode effective, String provider) {}
}
