package com.calai.backend.foodlog.task;

import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.storage.StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;

public class StubProviderClient implements ProviderClient {

    private final ObjectMapper om;

    public StubProviderClient(ObjectMapper om) {
        this.om = om;
    }

    @Override
    public ProviderResult process(FoodLogEntity log, StorageService storage) throws Exception {
        var effective = om.readTree("""
          {
            "foodName": "Stub result",
            "quantity": {"value": 1, "unit": "SERVING"},
            "nutrients": {"kcal": 222, "protein": 10, "fat": 8, "carbs": 28},
            "healthScore": 7,
            "confidence": 0.55
          }
        """);
        return new ProviderResult(effective, "STUB");
    }
}
