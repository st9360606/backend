package com.caloshape.backend.foodlog.provider.spi;

import com.caloshape.backend.foodlog.entity.FoodLogEntity;
import com.caloshape.backend.foodlog.storage.StorageService;
import com.fasterxml.jackson.databind.JsonNode;

public interface ProviderClient {

    /** 例如 "STUB" / "GEMINI" */
    String providerCode();

    ProviderResult process(FoodLogEntity log, StorageService storage) throws Exception;

    record ProviderResult(JsonNode effective, String provider) {}
}
