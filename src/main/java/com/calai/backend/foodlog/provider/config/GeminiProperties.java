package com.calai.backend.foodlog.provider.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "app.provider.gemini")
public class GeminiProperties {

    /** 開關：避免 dev 沒 key 就掛（預設 false） */
    private boolean enabled = false;

    /** 預設 Gemini API base url */
    private String baseUrl = "https://generativelanguage.googleapis.com";

    /** 預設 model（保底用；實際 PHOTO/TEXT 仍以 AiModelRouter 為主） */
    private String model = "gemini-3.1-flash-lite-preview";

    /** 用環境變數帶入：GEMINI_API_KEY */
    private String apiKey;

    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(30);

    /**
     * 是否讓 LABEL 走 function calling。
     * false = LABEL JSON mode
     * true  = LABEL function calling
     */
    private boolean labelUseFunctionCalling = false;

    /**
     * PHOTO / ALBUM request 參數
     */
    private RequestTuning photoAlbum = new RequestTuning(1536, 0.0);

    /**
     * LABEL JSON mode 參數
     */
    private RequestTuning labelJson = new RequestTuning(2048, 0.0);

    /**
     * TEXT ONLY request 參數
     * - strictRequireCore: useStrictJsonSchema=true 且 requireCoreNutrition=true
     * - strictDefault:     useStrictJsonSchema=true 且 requireCoreNutrition=false
     * - looseRequireCore:  useStrictJsonSchema=false 且 requireCoreNutrition=true
     * - looseDefault:      useStrictJsonSchema=false 且 requireCoreNutrition=false
     */
    private TextOnlyTuning textOnly = new TextOnlyTuning();

    @Data
    public static class RequestTuning {
        /** 每次 request 的輸出 token 上限 */
        private int maxOutputTokens = 1024;

        /** 生成溫度 */
        private double temperature = 0.0;

        public RequestTuning(int maxOutputTokens, double temperature) {
            this.maxOutputTokens = maxOutputTokens;
            this.temperature = temperature;
        }

    }

    @Data
    public static class TextOnlyTuning {
        private RequestTuning strictRequireCore = new RequestTuning(1024, 0.0);
        private RequestTuning strictDefault = new RequestTuning(768, 0.0);
        private RequestTuning looseRequireCore = new RequestTuning(1536, 0.0);
        private RequestTuning looseDefault = new RequestTuning(768, 0.0);

    }
}
