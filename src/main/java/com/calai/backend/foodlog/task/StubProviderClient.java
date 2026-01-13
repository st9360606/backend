package com.calai.backend.foodlog.task;

import com.calai.backend.foodlog.storage.StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class StubProviderClient implements ProviderClient {

    private final ObjectMapper om;

    public StubProviderClient(ObjectMapper om) {
        this.om = om;
    }

    @Override
    public ProviderResult process(String foodLogId, String imageObjectKey, StorageService storage) throws Exception {
        // ✅ Step3：至少確定能把圖讀出來
        var opened = storage.open(imageObjectKey);
        try (var in = opened.inputStream()) {
            byte[] head = in.readNBytes(16);
            if (head.length == 0) throw new IllegalStateException("EMPTY_IMAGE");
        }
        var effective = om.readTree("""
      {
        "foodName": "Stub result (read image OK)",
        "quantity": {"value": 1, "unit": "SERVING"},
        "nutrients": {"kcal": 222, "protein": 10, "fat": 8, "carbs": 28, "fiber": 3, "sugar": 5, "sodium": 300},
        "healthScore": 7,
        "confidence": 0.55
      }
    """);
        return new ProviderResult(effective, "STUB");
    }
}
