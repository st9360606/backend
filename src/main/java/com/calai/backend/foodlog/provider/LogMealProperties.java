package com.calai.backend.foodlog.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.provider.logmeal")
public record LogMealProperties(
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        String authorizationPrefix
) {
    public Duration connectTimeoutOrDefault() {
        return connectTimeout != null ? connectTimeout : Duration.ofSeconds(5);
    }

    public Duration readTimeoutOrDefault() {
        return readTimeout != null ? readTimeout : Duration.ofSeconds(25);
    }

    public String authorizationPrefixOrDefault() {
        // 有些服務要 "Bearer"，有些是直接 token；留給設定決定
        return authorizationPrefix;
    }
}
