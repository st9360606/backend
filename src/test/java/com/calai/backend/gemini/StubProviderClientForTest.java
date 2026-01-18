package com.calai.backend.gemini;

import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.storage.StorageService;
import com.calai.backend.foodlog.task.ProviderClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * ✅ 注意：這支「不要」註冊成 Spring Bean
 * 你專案內已經有另一個 providerCode() == "STUB" 的 ProviderClient bean，
 * 若再加 @Component 會造成 ProviderRouter 啟動時丟出：
 *   DUPLICATE_PROVIDER_CODE: STUB
 *
 * 這支保留給「純單元測試手動 new」使用即可。
 */
public class StubProviderClientForTest implements ProviderClient {

    private static final ObjectMapper OM = new ObjectMapper();

    @Override
    public String providerCode() {
        return "STUB";
    }

    @Override
    public ProviderResult process(FoodLogEntity log, StorageService storage) throws Exception {
        ObjectNode eff = (ObjectNode) OM.readTree("""
          {
            "foodName":"Stub Food",
            "quantity":{"value":1,"unit":"SERVING"},
            "nutrients":{"kcal":100,"protein":5,"fat":3,"carbs":12,"fiber":2,"sugar":1,"sodium":200},
            "confidence":0.5
          }
        """);
        return new ProviderResult(eff, "STUB");
    }
}
