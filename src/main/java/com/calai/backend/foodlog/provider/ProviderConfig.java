package com.calai.backend.foodlog.provider;

import com.calai.backend.foodlog.config.AiModelRouter;
import com.calai.backend.foodlog.config.AiModelTiersProperties;
import com.calai.backend.foodlog.model.ModelMode;
import com.calai.backend.foodlog.quota.model.ModelTier;
import com.calai.backend.foodlog.task.ProviderClient;
import com.calai.backend.foodlog.task.ProviderTelemetry;
import com.calai.backend.foodlog.task.StubProviderClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties({GeminiProperties.class, AiModelTiersProperties.class})
public class ProviderConfig {

    @Bean
    public ProviderClient stubProviderClient(ObjectMapper om) {
        return new StubProviderClient(om);
    }

    @Bean
    public AiModelRouter aiModelRouter(AiModelTiersProperties props) {
        return new AiModelRouter(props);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.provider.gemini", name = "enabled", havingValue = "true")
    public RestClient geminiRestClient(GeminiProperties props) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout((int) props.getConnectTimeout().toMillis());
        f.setReadTimeout((int) props.getReadTimeout().toMillis());

        return RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(f)
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.provider.gemini", name = "enabled", havingValue = "true")
    public ProviderClient geminiProviderClient(
            RestClient geminiRestClient,
            GeminiProperties props,
            ObjectMapper om,
            ProviderTelemetry telemetry,
            AiModelRouter modelRouter
    ) {
        // ✅ Fail-fast：啟動時就抓到 key 缺失
        String k = props.getApiKey();
        if (k == null || k.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY_MISSING");
        }

        // ✅ baseUrl 也必須存在
        if (props.getBaseUrl() == null || props.getBaseUrl().isBlank()) {
            throw new IllegalStateException("GEMINI_BASE_URL_MISSING");
        }

        // ✅ Step 2：不再檢查 props.model（避免寫死模型）
        // 改成檢查 HIGH/LOW + VISION 是否都已配置，且 provider 必須是 GEMINI
        assertGeminiTierConfigured(modelRouter, ModelTier.MODEL_TIER_HIGH, ModelMode.VISION);
        assertGeminiTierConfigured(modelRouter, ModelTier.MODEL_TIER_LOW,  ModelMode.VISION);

        return new GeminiProviderClient(geminiRestClient, props, om, telemetry, modelRouter);
    }

    private static void assertGeminiTierConfigured(AiModelRouter router, ModelTier tier, ModelMode mode) {
        AiModelRouter.Resolved r = router.resolveOrThrow(tier, mode);

        if (r.provider() == null || r.provider().isBlank()) {
            throw new IllegalStateException("MODEL_TIER_PROVIDER_MISSING: " + tier + "/" + mode);
        }
        if (!"GEMINI".equalsIgnoreCase(r.provider())) {
            throw new IllegalStateException("MODEL_TIER_PROVIDER_MISMATCH: " + tier + "/" + mode
                                            + " expected=GEMINI actual=" + r.provider());
        }
        if (r.modelId() == null || r.modelId().isBlank()) {
            throw new IllegalStateException("MODEL_TIER_MODELID_MISSING: " + tier + "/" + mode);
        }
    }
}