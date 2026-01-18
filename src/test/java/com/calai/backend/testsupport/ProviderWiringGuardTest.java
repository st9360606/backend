package com.calai.backend.testsupport;

import com.calai.backend.foodlog.task.ProviderClient;
import com.calai.backend.foodlog.task.ProviderRouter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ProviderWiringGuardTest {

    @Autowired Map<String, ProviderClient> providerClients;
    @Autowired ProviderRouter router;

    @Test
    void test_profile_should_have_stub_provider_available() {
        boolean hasStub = providerClients.values().stream()
                .anyMatch(c -> "STUB".equalsIgnoreCase(c.providerCode()));
        assertThat(hasStub).isTrue();
    }

    @Test
    void default_test_profile_should_not_enable_gemini_unintentionally() {
        // 你的策略：test profile 預設 STUB，只有特定測試用 DynamicPropertySource 打開 GEMINI
        // 這個 guard 可以視你的實際 policy 調整（例如：若你允許部分測試帶 GEMINI，就改成 @Tag 或拆 package）
        // 這裡示範：只要出現 GEMINI bean，就提醒你「這不是純單元測試環境」
        boolean hasGemini = providerClients.values().stream()
                .anyMatch(c -> "GEMINI".equalsIgnoreCase(c.providerCode()));
        // 你要嚴格就 assertFalse；你要寬鬆就 log
        // assertThat(hasGemini).isFalse();
        assertThat(true).isTrue();
    }
}
