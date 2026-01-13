package com.calai.backend.foodlog.task;

import com.calai.backend.foodlog.entity.FoodLogTaskEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class StubProviderClient implements ProviderClient {

    private final ObjectMapper om;

    public StubProviderClient(ObjectMapper om) {
        this.om = om;
    }

    @Override
    public ProviderResult process(FoodLogTaskEntity task) throws Exception {
        // TODO 你之後換 LogMeal/Gemini，就改這裡
        var effective = om.readTree("""
          {
            "foodName": "Stub result",
            "quantity": {"value": 1, "unit": "SERVING"},
            "nutrients": {"kcal": 222, "protein": 10, "fat": 8, "carbs": 28, "fiber": 3, "sugar": 5, "sodium": 300},
            "healthScore": 7,
            "confidence": 0.55
          }
        """);
        return new ProviderResult(effective, "STUB");
    }
}
