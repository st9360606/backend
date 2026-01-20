package com.calai.backend.foodlog.provider;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * ✅ 啟動自檢（不打外網）：
 * - enabled=true 且 apiKey 存在 -> UP
 * - enabled=true 但 apiKey 不存在 -> DOWN
 * - enabled=false -> UP (因為你可能有意關閉 provider)
 *
 * 用途：K8s/Cloud Run readiness check
 */
@Component
@ConditionalOnProperty(prefix = "app.provider.gemini", name = "enabled", havingValue = "true")
public class GeminiConfigHealthIndicator implements HealthIndicator {

    private final GeminiProperties props;

    public GeminiConfigHealthIndicator(GeminiProperties props) {
        this.props = props;
    }

    @Override
    public Health health() {
        String apiKey = props.getApiKey();
        String baseUrl = props.getBaseUrl();
        String model = props.getModel();

        boolean hasKey = apiKey != null && !apiKey.isBlank();
        boolean baseOk = baseUrl != null && !baseUrl.isBlank() && baseUrl.toLowerCase(Locale.ROOT).startsWith("https://");
        boolean modelOk = model != null && !model.isBlank();

        Health.Builder b;

        // ✅ 只要 enabled=true 但缺 key -> DOWN
        if (!hasKey) {
            b = Health.down().withDetail("reason", "GEMINI_API_KEY_MISSING");
        } else if (!baseOk) {
            b = Health.down().withDetail("reason", "GEMINI_BASE_URL_INVALID");
        } else if (!modelOk) {
            b = Health.down().withDetail("reason", "GEMINI_MODEL_MISSING");
        } else {
            b = Health.up();
        }

        // ✅ 不要輸出 apiKey
        return b.withDetail("provider", "GEMINI")
                .withDetail("enabled", true)
                .withDetail("baseUrl", safe(baseUrl))
                .withDetail("model", safe(model))
                .build();
    }

    private static String safe(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
