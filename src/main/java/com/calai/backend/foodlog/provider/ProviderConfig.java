package com.calai.backend.foodlog.provider;

import com.calai.backend.foodlog.service.HealthScoreService;
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

    /** ✅ STUB 永遠存在（Router 保底、dev/test 必備） */
    @Bean
    public ProviderClient stubProviderClient(ObjectMapper om) {
        return new StubProviderClient(om);
    }

    /** ✅ GEMINI RestClient（只在 enabled=true 才建立） */
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

    /** ✅ GEMINI ProviderClient */
    @Bean
    @ConditionalOnProperty(prefix = "app.provider.gemini", name = "enabled", havingValue = "true")
    public ProviderClient geminiProviderClient(
            RestClient geminiRestClient,
            GeminiProperties props,
            HealthScoreService healthScoreService,
            ObjectMapper om
    ) {
        return new GeminiProviderClient(geminiRestClient, props, healthScoreService, om);
    }

    /** ✅ 只有 LOGMEAL 時才建立 RestClient（避免切 GEMINI 後還吃 logmeal 設定） */
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

    /** ✅ 只有 LOGMEAL 時才建立 provider */
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
