package com.calai.backend.foodlog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "app.ai")
public class AiModelTiersProperties {

    /**
     * YAML 結構（建議）：
     * app.ai.modelTiers.MODEL_TIER_HIGH.VISION.provider/modelId
     * app.ai.modelTiers.MODEL_TIER_LOW.VISION.provider/modelId
     */
    private Map<String, Map<String, TierSpec>> modelTiers = new HashMap<>();

    public Map<String, Map<String, TierSpec>> getModelTiers() { return modelTiers; }
    public void setModelTiers(Map<String, Map<String, TierSpec>> modelTiers) { this.modelTiers = modelTiers; }

    public static class TierSpec {
        /** 例如 GEMINI / OPENAI（先只用 GEMINI） */
        private String provider;
        /** 例如 gemini-3-flash / gemini-2.5-flash-lite */
        private String modelId;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }

        public String getModelId() { return modelId; }
        public void setModelId(String modelId) { this.modelId = modelId; }
    }
}