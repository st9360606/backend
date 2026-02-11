package com.calai.backend.foodlog.provider.config;

import com.calai.backend.foodlog.config.AiModelRouter;
import com.calai.backend.foodlog.config.AiModelTiersProperties;
import com.calai.backend.foodlog.model.ModelMode;
import com.calai.backend.foodlog.provider.GeminiProviderClient;
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
    public AiModelRouter aiModelRouter(AiModelTiersProperties props) {
        return new AiModelRouter(props);
    }

    /**
     * ✅ 只有 gemini enabled=false 才提供 stub，避免 ProviderClient 變成兩個 Bean 造成注入衝突
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.provider.gemini", name = "enabled", havingValue = "false", matchIfMissing = true)
    public ProviderClient stubProviderClient(ObjectMapper om) {
        return new StubProviderClient(om);
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
        // ✅ Fail-fast：啟動就抓到 key 缺失
        String k = props.getApiKey();
        if (k == null || k.isBlank()) throw new IllegalStateException("GEMINI_API_KEY_MISSING");
        if (props.getBaseUrl() == null || props.getBaseUrl().isBlank()) throw new IllegalStateException("GEMINI_BASE_URL_MISSING");

        // ✅ 四種組合都要有：HIGH/LOW × VISION/TEXT
        assertGeminiTierConfigured(modelRouter, ModelTier.MODEL_TIER_HIGH, ModelMode.VISION);
        assertGeminiTierConfigured(modelRouter, ModelTier.MODEL_TIER_LOW,  ModelMode.VISION);
        assertGeminiTierConfigured(modelRouter, ModelTier.MODEL_TIER_HIGH, ModelMode.TEXT);
        assertGeminiTierConfigured(modelRouter, ModelTier.MODEL_TIER_LOW,  ModelMode.TEXT);

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
