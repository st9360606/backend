package com.calai.backend.gemini;

import com.calai.backend.foodlog.provider.gemini.label.GeminiLabelFallbackSupport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiProviderClientHelperTest {

    @Test
    void extractSodiumMgFromText_should_convert_g_to_mg() {
        Double sodiumMg = GeminiLabelFallbackSupport.extractSodiumMgFromText("鈉 0.12 g");
        assertThat(sodiumMg).isEqualTo(120.0);
    }

    @Test
    void extractSodiumMgFromText_should_keep_mg() {
        Double sodiumMg = GeminiLabelFallbackSupport.extractSodiumMgFromText("Sodium 120 mg");
        assertThat(sodiumMg).isEqualTo(120.0);
    }

    @Test
    void extractSodiumMgFromText_should_support_chinese_mg() {
        Double sodiumMg = GeminiLabelFallbackSupport.extractSodiumMgFromText("鈉 120 毫克");
        assertThat(sodiumMg).isEqualTo(120.0);
    }

    @Test
    void findServingAmount_should_parse_ml() {
        GeminiLabelFallbackSupport.ParsedAmount amount =
                GeminiLabelFallbackSupport.findServingAmount("Serving size 125 ml");

        assertThat(amount).isNotNull();
        assertThat(amount.value()).isEqualTo(125.0);
        assertThat(amount.unit()).isEqualTo("ML");
    }

    @Test
    void findServingAmount_should_parse_gram() {
        GeminiLabelFallbackSupport.ParsedAmount amount =
                GeminiLabelFallbackSupport.findServingAmount("每一份量 30g");

        assertThat(amount).isNotNull();
        assertThat(amount.value()).isEqualTo(30.0);
        assertThat(amount.unit()).isEqualTo("GRAM");
    }
}