package com.calai.backend.foodlog.provider;

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
@EnableConfigurationProperties({GeminiProperties.class})
public class ProviderConfig {

    @Bean
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
            ProviderTelemetry telemetry
    ) {
        // ✅ Fail-fast：啟動時就抓到 key 缺失
        String k = props.getApiKey();
        if (k == null || k.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY_MISSING");
        }

        // 也檢查 baseUrl/model 不是空，避免配置錯
        if (props.getBaseUrl() == null || props.getBaseUrl().isBlank()) {
            throw new IllegalStateException("GEMINI_BASE_URL_MISSING");
        }
        if (props.getModel() == null || props.getModel().isBlank()) {
            throw new IllegalStateException("GEMINI_MODEL_MISSING");
        }

        return new GeminiProviderClient(geminiRestClient, props, om, telemetry);
    }

}
