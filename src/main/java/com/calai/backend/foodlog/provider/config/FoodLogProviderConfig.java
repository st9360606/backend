package com.calai.backend.foodlog.provider.config;

import com.calai.backend.foodlog.provider.gemini.spi.GeminiModeProcessor;
import com.calai.backend.foodlog.provider.gemini.config.GeminiProperties;
import com.calai.backend.foodlog.provider.routing.AiModelTierRouter;
import com.calai.backend.foodlog.model.ModelMode;
import com.calai.backend.foodlog.provider.gemini.GeminiProviderClient;
import com.calai.backend.foodlog.quota.model.ModelTier;
import com.calai.backend.foodlog.provider.spi.ProviderClient;
import com.calai.backend.foodlog.provider.stub.StubProviderClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties({GeminiProperties.class, AiModelTiersProperties.class})
public class FoodLogProviderConfig {

    @Bean
    public AiModelTierRouter aiModelRouter(AiModelTiersProperties props) {
        return new AiModelTierRouter(props);
    }

    /**
     * ✅ Stub 只允許 local / test 使用
     * ✅ 必須明確設定 app.provider.gemini.enabled=false 才會建立
     * ✅ 不再允許 matchIfMissing=true，避免漏配時默默走 stub
     */
    @Bean
    @Profile({"local", "test"})
    @ConditionalOnProperty(
            prefix = "app.provider.gemini",
            name = "enabled",
            havingValue = "false",
            matchIfMissing = false
    )
    public ProviderClient stubProviderClient(ObjectMapper om) {
        return new StubProviderClient(om);
    }

    @Bean("geminiRestClient")
    @ConditionalOnProperty(
            prefix = "app.provider.gemini",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = false
    )
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
    @ConditionalOnProperty(
            prefix = "app.provider.gemini",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = false
    )
    public ProviderClient geminiProviderClient(
            GeminiProperties props,
            AiModelTierRouter modelRouter,
            java.util.List<GeminiModeProcessor> processors
    ) {
        // ✅ Fail-fast：啟動就抓到 key/base-url 缺失
        String k = props.getApiKey();
        if (k == null || k.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY_MISSING");
        }
        if (props.getBaseUrl() == null || props.getBaseUrl().isBlank()) {
            throw new IllegalStateException("GEMINI_BASE_URL_MISSING");
        }

        // ✅ HIGH/LOW × VISION/TEXT 都要完整
        assertGeminiTierConfigured(modelRouter, ModelTier.MODEL_TIER_HIGH, ModelMode.VISION);
        assertGeminiTierConfigured(modelRouter, ModelTier.MODEL_TIER_LOW, ModelMode.VISION);
        assertGeminiTierConfigured(modelRouter, ModelTier.MODEL_TIER_HIGH, ModelMode.TEXT);
        assertGeminiTierConfigured(modelRouter, ModelTier.MODEL_TIER_LOW, ModelMode.TEXT);

        if (processors == null || processors.isEmpty()) {
            throw new IllegalStateException("GEMINI_MODE_PROCESSORS_MISSING");
        }

        return new GeminiProviderClient(processors);
    }

    private static void assertGeminiTierConfigured(AiModelTierRouter router, ModelTier tier, ModelMode mode) {
        AiModelTierRouter.Resolved r = router.resolveOrThrow(tier, mode);

        if (r.provider() == null || r.provider().isBlank()) {
            throw new IllegalStateException("MODEL_TIER_PROVIDER_MISSING: " + tier + "/" + mode);
        }
        if (!"GEMINI".equalsIgnoreCase(r.provider())) {
            throw new IllegalStateException(
                    "MODEL_TIER_PROVIDER_MISMATCH: " + tier + "/" + mode
                    + " expected=GEMINI actual=" + r.provider()
            );
        }
        if (r.modelId() == null || r.modelId().isBlank()) {
            throw new IllegalStateException("MODEL_TIER_MODELID_MISSING: " + tier + "/" + mode);
        }
    }
}
