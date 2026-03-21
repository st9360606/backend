package com.calai.backend.foodlog.provider.label;

import com.calai.backend.foodlog.provider.gemini.label.GeminiLabelFallbackSupport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiLabelFallbackSupportParsingTest {

    @Test
    void findServingAmount_should_parse_gram_correctly() {
        GeminiLabelFallbackSupport.ParsedAmount amount =
                GeminiLabelFallbackSupport.findServingAmount("每一份量 30g");

        assertThat(amount).isNotNull();
        assertThat(amount.value()).isEqualTo(30.0);
        assertThat(amount.unit()).isEqualTo("GRAM");
    }

    @Test
    void findServingAmount_should_parse_ml_correctly() {
        GeminiLabelFallbackSupport.ParsedAmount amount =
                GeminiLabelFallbackSupport.findServingAmount("Serving size 125 ml");

        assertThat(amount).isNotNull();
        assertThat(amount.value()).isEqualTo(125.0);
        assertThat(amount.unit()).isEqualTo("ML");
    }

    @Test
    void extractSodiumMgFromText_should_convert_g_to_mg() {
        Double sodium = GeminiLabelFallbackSupport.extractSodiumMgFromText("鈉 0.12 g");

        assertThat(sodium).isEqualTo(120.0);
    }

    @Test
    void extractSodiumMgFromText_should_keep_mg_as_is() {
        Double sodium = GeminiLabelFallbackSupport.extractSodiumMgFromText("Sodium 180 mg");

        assertThat(sodium).isEqualTo(180.0);
    }
}