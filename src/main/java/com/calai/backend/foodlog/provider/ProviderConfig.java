package com.calai.backend.foodlog.provider;

import com.calai.backend.foodlog.service.LogMealTokenService;
import com.calai.backend.foodlog.task.ProviderClient;
import com.calai.backend.foodlog.task.StubProviderClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties({LogMealProperties.class, GeminiProperties.class})
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
            ObjectMapper om
    ) {
        return new GeminiProviderClient(geminiRestClient, props, om);
    }

    // ---- LOGMEAL (先留到 Step 5 再移除) ----
    @Bean
    @ConditionalOnProperty(name = "app.foodlog.provider", havingValue = "LOGMEAL")
    public RestClient logMealRestClient(LogMealProperties props) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout((int) props.connectTimeoutOrDefault().toMillis());
        f.setReadTimeout((int) props.readTimeoutOrDefault().toMillis());

        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(f)
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "app.foodlog.provider", havingValue = "LOGMEAL")
    public ProviderClient logMealProviderClient(
            RestClient logMealRestClient,
            LogMealProperties props,
            LogMealTokenService tokenService
    ) {
        return new LogMealProviderClient(logMealRestClient, props, tokenService);
    }
}
