package com.calai.backend.foodlog.provider.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.provider.gemini")
public class GeminiProperties {

    /** 開關：避免 dev 沒 key 就掛（預設 false） */
    private boolean enabled = false;

    /** 預設 Gemini API base url */
    private String baseUrl = "https://generativelanguage.googleapis.com";

    /** 例如 gemini-3-flash-preview */
    private String model = "gemini-3-flash-preview";

    /** 用環境變數帶入：GEMINI_API_KEY */
    private String apiKey;

    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(30);

    /** 成本守門：輸出 token 上限（MVP 保守） */
    private int maxOutputTokens = 512;

    /** 低溫度降低亂講 */
    private double temperature = 0.2;

    // ===== getters/setters =====
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }

    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }

    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }

    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }
}
