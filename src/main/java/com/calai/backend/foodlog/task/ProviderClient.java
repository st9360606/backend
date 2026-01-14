package com.calai.backend.foodlog.task;

import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.storage.StorageService;
import com.fasterxml.jackson.databind.JsonNode;

public interface ProviderClient {

    ProviderResult process(FoodLogEntity log, StorageService storage) throws Exception;

    record ProviderResult(JsonNode effective, String provider) {}
}
