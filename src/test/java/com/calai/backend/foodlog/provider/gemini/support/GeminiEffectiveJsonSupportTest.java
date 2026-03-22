package com.calai.backend.foodlog.provider.gemini.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeminiEffectiveJsonSupportTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    @DisplayName("normalizeToEffective: 正常資料應保留 nutrients / confidence / healthScore / aiMeta")
    void normalize_ok_should_keep_nutrients() throws Exception {
        // arrange
        var raw = om.readTree("""
            {
              "foodName": "  Chicken Salad  ",
              "quantity": {
                "value": 1,
                "unit": "serving"
              },
              "nutrients": {
                "kcal": 350,
                "protein": 25,
                "fat": 18,
                "carbs": 20,
                "fiber": 6,
                "sugar": 5,
                "sodium": 450
              },
              "_reasoning": " visible ingredients matched ",
              "confidence": 0.72,
              "healthScore": 8,
              "warnings": ["LOW_CONFIDENCE"],
              "labelMeta": {
                "servingsPerContainer": 1,
                "basis": "whole_package"
              },
              "aiMeta": {
                "foodCategory": "SALAD",
                "foodSubCategory": "CHICKEN_SALAD"
              }
            }
        """);

        // act
        ObjectNode eff = GeminiEffectiveJsonSupport.normalizeToEffective(raw);

        // assert
        assertThat(eff.get("foodName").asText()).isEqualTo("Chicken Salad");

        assertThat(eff.get("quantity").get("value").asDouble()).isEqualTo(1d);
        assertThat(eff.get("quantity").get("unit").asText()).isEqualTo("SERVING");

        assertThat(eff.get("nutrients").get("kcal").asDouble()).isEqualTo(350d);
        assertThat(eff.get("nutrients").get("protein").asDouble()).isEqualTo(25d);
        assertThat(eff.get("nutrients").get("fat").asDouble()).isEqualTo(18d);
        assertThat(eff.get("nutrients").get("carbs").asDouble()).isEqualTo(20d);
        assertThat(eff.get("nutrients").get("fiber").asDouble()).isEqualTo(6d);
        assertThat(eff.get("nutrients").get("sugar").asDouble()).isEqualTo(5d);
        assertThat(eff.get("nutrients").get("sodium").asDouble()).isEqualTo(450d);

        assertThat(eff.get("_reasoning").asText()).isEqualTo("visible ingredients matched");
        assertThat(eff.get("confidence").asDouble()).isEqualTo(0.72d);
        assertThat(eff.get("healthScore").asInt()).isEqualTo(8);

        assertThat(eff.get("warnings").isArray()).isTrue();
        assertThat(eff.get("warnings")).hasSize(1);
        assertThat(eff.get("warnings").get(0).asText()).isEqualTo("LOW_CONFIDENCE");

        assertThat(eff.get("labelMeta").get("servingsPerContainer").asDouble()).isEqualTo(1d);
        assertThat(eff.get("labelMeta").get("basis").asText()).isEqualTo("WHOLE_PACKAGE");

        assertThat(eff.get("aiMeta").get("foodCategory").asText()).isEqualTo("SALAD");
        assertThat(eff.get("aiMeta").get("foodSubCategory").asText()).isEqualTo("CHICKEN_SALAD");
    }

    @Test
    @DisplayName("normalizeToEffective: 負數 nutrients 應被 sanitize 成 0.0，其他合法值保留")
    void normalize_negative_number_should_be_sanitized_to_zero() throws Exception {
        // arrange
        var raw = om.readTree("""
            {
              "foodName": "X",
              "quantity": {
                "value": 1,
                "unit": "SERVING"
              },
              "nutrients": {
                "kcal": -1,
                "protein": 1,
                "fat": 1,
                "carbs": 1,
                "fiber": -5,
                "sugar": null,
                "sodium": -10
              }
            }
        """);

        // act
        ObjectNode eff = GeminiEffectiveJsonSupport.normalizeToEffective(raw);

        // assert
        assertThat(eff.get("nutrients").get("kcal").asDouble()).isEqualTo(0.0d);
        assertThat(eff.get("nutrients").get("protein").asDouble()).isEqualTo(1.0d);
        assertThat(eff.get("nutrients").get("fat").asDouble()).isEqualTo(1.0d);
        assertThat(eff.get("nutrients").get("carbs").asDouble()).isEqualTo(1.0d);

        // 缺值 / 負值統一補 0.0
        assertThat(eff.get("nutrients").get("fiber").asDouble()).isEqualTo(0.0d);
        assertThat(eff.get("nutrients").get("sugar").asDouble()).isEqualTo(0.0d);
        assertThat(eff.get("nutrients").get("sodium").asDouble()).isEqualTo(0.0d);

        // 未提供 confidence / healthScore 時應為 null
        assertThat(eff.get("confidence").isNull()).isTrue();
        assertThat(eff.get("healthScore").isNull()).isTrue();
    }

    @Test
    @DisplayName("normalizeToEffective: 非法 quantity / confidence / healthScore 應套用保底規則")
    void normalize_invalid_quantity_and_confidence_should_use_defaults() throws Exception {
        // arrange
        var raw = om.readTree("""
            {
              "foodName": "Y",
              "quantity": {
                "value": 0,
                "unit": "not_a_real_unit"
              },
              "nutrients": {
                "kcal": 100,
                "protein": 10,
                "fat": 2,
                "carbs": 8,
                "fiber": 1,
                "sugar": 3,
                "sodium": 120
              },
              "confidence": 85,
              "healthScore": 8.5
            }
        """);

        // act
        ObjectNode eff = GeminiEffectiveJsonSupport.normalizeToEffective(raw);

        // assert
        // quantity.value <= 0 時保底 1.0
        assertThat(eff.get("quantity").get("value").asDouble()).isEqualTo(1.0d);

        // 非法 unit 由 QuantityUnit.fromRawOrDefault() 正規化成 SERVING
        assertThat(eff.get("quantity").get("unit").asText()).isEqualTo("SERVING");

        // confidence 僅接受 0.0 ~ 1.0
        assertThat(eff.get("confidence").isNull()).isTrue();

        // healthScore 僅接受 0~10 整數
        assertThat(eff.get("healthScore").isNull()).isTrue();
    }

    @Test
    @DisplayName("normalizeToEffective: nutrients 缺失時應拋 PROVIDER_BAD_RESPONSE")
    void normalize_missing_nutrients_should_throw() throws Exception {
        // arrange
        var raw = om.readTree("""
            {
              "foodName": "No Nutrients",
              "quantity": {
                "value": 1,
                "unit": "SERVING"
              }
            }
        """);

        // act + assert
        assertThatThrownBy(() -> GeminiEffectiveJsonSupport.normalizeToEffective(raw))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PROVIDER_BAD_RESPONSE");
    }

    @Test
    @DisplayName("finalizeEffective: PER_SERVING + servingsPerContainer>1 應乘成 WHOLE_PACKAGE 並四捨五入到 1 位")
    void finalize_should_scale_per_serving_to_whole_package_and_round() throws Exception {
        // arrange
        var raw = om.readTree("""
            {
              "labelMeta": {
                "servingsPerContainer": 2.5,
                "basis": "PER_SERVING"
              }
            }
        """);

        ObjectNode effective = (ObjectNode) om.readTree("""
            {
              "foodName": "Protein Drink",
              "quantity": {
                "value": 1,
                "unit": "BOTTLE"
              },
              "nutrients": {
                "kcal": 120.24,
                "protein": 10.04,
                "fat": 2.02,
                "carbs": 15.06,
                "fiber": 0.04,
                "sugar": 7.84,
                "sodium": 99.96
              },
              "labelMeta": {
                "servingsPerContainer": null,
                "basis": null
              }
            }
        """);

        // act
        GeminiEffectiveJsonSupport.finalizeEffective(raw, effective);

        // assert
        // quantity 保留 logical unit，不強制改 SERVING
        assertThat(effective.get("quantity").get("value").asDouble()).isEqualTo(1.0d);
        assertThat(effective.get("quantity").get("unit").asText()).isEqualTo("BOTTLE");

        // 乘以 2.5 後，再 round 1 位
        assertThat(effective.get("nutrients").get("kcal").asDouble()).isEqualTo(300.6d);
        assertThat(effective.get("nutrients").get("protein").asDouble()).isEqualTo(25.1d);
        assertThat(effective.get("nutrients").get("fat").asDouble()).isEqualTo(5.1d);
        assertThat(effective.get("nutrients").get("carbs").asDouble()).isEqualTo(37.7d);
        assertThat(effective.get("nutrients").get("fiber").asDouble()).isEqualTo(0.1d);
        assertThat(effective.get("nutrients").get("sugar").asDouble()).isEqualTo(19.6d);
        assertThat(effective.get("nutrients").get("sodium").asDouble()).isEqualTo(249.9d);

        assertThat(effective.get("labelMeta").get("servingsPerContainer").asDouble()).isEqualTo(2.5d);
        assertThat(effective.get("labelMeta").get("basis").asText()).isEqualTo("WHOLE_PACKAGE");
    }

    @Test
    @DisplayName("hasWarning: warnings 陣列中有指定 code 時應回 true")
    void hasWarning_should_return_true_when_code_exists() throws Exception {
        // arrange
        var raw = om.readTree("""
            {
              "warnings": ["LOW_CONFIDENCE", "UNKNOWN_FOOD"]
            }
        """);

        // act + assert
        assertThat(GeminiEffectiveJsonSupport.hasWarning(raw, "UNKNOWN_FOOD")).isTrue();
        assertThat(GeminiEffectiveJsonSupport.hasWarning(raw, "NO_FOOD_DETECTED")).isFalse();
    }
}
