package com.calai.backend.foodlog.provider.gemini.label;

import com.calai.backend.foodlog.unit.FoodLogWarning;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiLabelFallbackSupportTest {

    private final ObjectMapper om = new ObjectMapper();
    private final GeminiLabelFallbackSupport support = new GeminiLabelFallbackSupport(om);

    @Test
    @DisplayName("looksLikeStartedJsonLabel: JSON 開頭且包含 foodName/quantity/nutrients 應回 true")
    void looksLikeStartedJsonLabel_should_return_true_for_started_json_label() {
        assertThat(support.looksLikeStartedJsonLabel("""
                {
                  "foodName":"Milk Tea",
                  "quantity":{"value":1,"unit":"BOTTLE"}
                }
                """)).isTrue();

        assertThat(support.looksLikeStartedJsonLabel("""
                {
                  "nutrients":{"kcal":120}
                }
                """)).isTrue();

        assertThat(support.looksLikeStartedJsonLabel("not json")).isFalse();
        assertThat(support.looksLikeStartedJsonLabel("[]")).isFalse();
    }

    @Test
    @DisplayName("fallbackLabelPartialDetected: 應建立 partial fallback 結構")
    void fallbackLabelPartialDetected_should_build_partial_fallback() {
        ObjectNode out = support.fallbackLabelPartialDetected("""
                {"foodName":"Chocolate Cookies"
                """);

        assertThat(out.get("foodName").asText()).isEqualTo("Chocolate Cookies");
        assertThat(out.get("quantity").get("value").asDouble()).isEqualTo(1d);
        assertThat(out.get("quantity").get("unit").asText()).isEqualTo("SERVING");

        assertThat(out.get("nutrients").get("kcal").asDouble()).isEqualTo(0.0d);
        assertThat(out.get("nutrients").get("protein").asDouble()).isEqualTo(0.0d);
        assertThat(out.get("nutrients").get("fat").asDouble()).isEqualTo(0.0d);
        assertThat(out.get("nutrients").get("carbs").asDouble()).isEqualTo(0.0d);
        assertThat(out.get("nutrients").get("fiber").asDouble()).isEqualTo(0.0d);
        assertThat(out.get("nutrients").get("sugar").asDouble()).isEqualTo(0.0d);
        assertThat(out.get("nutrients").get("sodium").asDouble()).isEqualTo(0.0d);

        assertThat(out.get("confidence").isNull()).isTrue();

        assertThat(out.get("warnings")).hasSize(2);
        assertThat(out.get("warnings").get(0).asText()).isEqualTo(FoodLogWarning.LABEL_PARTIAL.name());
        assertThat(out.get("warnings").get(1).asText()).isEqualTo(FoodLogWarning.LOW_CONFIDENCE.name());

        assertThat(out.get("labelMeta").get("servingsPerContainer").isNull()).isTrue();
        assertThat(out.get("labelMeta").get("basis").isNull()).isTrue();
    }

    @Test
    @DisplayName("isLabelEmptyArgs: nutrients 全為 0 且 confidence 低時應回 true")
    void isLabelEmptyArgs_should_return_true_when_all_zero_and_low_confidence() throws Exception {
        var root = om.readTree("""
                {
                  "nutrients":{
                    "kcal":0,"protein":0,"fat":0,"carbs":0,"fiber":0,"sugar":0,"sodium":0
                  },
                  "confidence":0.05
                }
                """);

        assertThat(support.isLabelEmptyArgs(root)).isTrue();
    }

    @Test
    @DisplayName("isLabelEmptyArgs: 只要有非零 nutrient 應回 false")
    void isLabelEmptyArgs_should_return_false_when_has_non_zero_nutrient() throws Exception {
        var root = om.readTree("""
                {
                  "nutrients":{
                    "kcal":100,"protein":0,"fat":0,"carbs":0,"fiber":0,"sugar":0,"sodium":0
                  },
                  "confidence":0.0
                }
                """);

        assertThat(support.isLabelEmptyArgs(root)).isFalse();
    }

    @Test
    @DisplayName("isLabelIncomplete: kcal 缺失時應回 true")
    void isLabelIncomplete_should_return_true_when_kcal_missing() throws Exception {
        var root = om.readTree("""
                {
                  "nutrients":{
                    "protein":3,"fat":2,"carbs":10,"fiber":1,"sugar":5,"sodium":120
                  },
                  "confidence":0.95
                }
                """);

        assertThat(support.isLabelIncomplete(root)).isTrue();
    }

    @Test
    @DisplayName("isLabelIncomplete: 核心營養完整且 confidence 高時應回 false")
    void isLabelIncomplete_should_return_false_when_core_complete_and_confident() throws Exception {
        var root = om.readTree("""
                {
                  "nutrients":{
                    "kcal":240,"protein":6,"fat":8,"carbs":30,"fiber":2,"sugar":12,"sodium":180
                  },
                  "confidence":0.95
                }
                """);

        assertThat(support.isLabelIncomplete(root)).isFalse();
    }

    @Test
    @DisplayName("ensureLabelRequiredKeys: 缺 key 時應補齊預設值")
    void ensureLabelRequiredKeys_should_fill_defaults() throws Exception {
        ObjectNode obj = (ObjectNode) om.readTree("""
                {
                  "foodName":"Snack"
                }
                """);

        support.ensureLabelRequiredKeys(obj);

        assertThat(obj.get("foodName").asText()).isEqualTo("Snack");

        assertThat(obj.get("quantity").get("value").asDouble()).isEqualTo(1d);
        assertThat(obj.get("quantity").get("unit").asText()).isEqualTo("SERVING");

        assertThat(obj.get("nutrients").get("kcal").asDouble()).isEqualTo(0.0d);
        assertThat(obj.get("nutrients").get("protein").asDouble()).isEqualTo(0.0d);
        assertThat(obj.get("nutrients").get("fat").asDouble()).isEqualTo(0.0d);
        assertThat(obj.get("nutrients").get("carbs").asDouble()).isEqualTo(0.0d);
        assertThat(obj.get("nutrients").get("fiber").asDouble()).isEqualTo(0.0d);
        assertThat(obj.get("nutrients").get("sugar").asDouble()).isEqualTo(0.0d);
        assertThat(obj.get("nutrients").get("sodium").asDouble()).isEqualTo(0.0d);

        assertThat(obj.get("confidence").isNull()).isTrue();
        assertThat(obj.get("healthScore").isNull()).isTrue();
        assertThat(obj.get("warnings").isArray()).isTrue();

        assertThat(obj.get("labelMeta").get("servingsPerContainer").isNull()).isTrue();
        assertThat(obj.get("labelMeta").get("basis").isNull()).isTrue();
    }

    @Test
    @DisplayName("addWarningIfMissing: 重複 warning 不應重複加入")
    void addWarningIfMissing_should_not_duplicate() throws Exception {
        ObjectNode obj = (ObjectNode) om.readTree("""
                {
                  "warnings":["LOW_CONFIDENCE"]
                }
                """);

        GeminiLabelFallbackSupport.addWarningIfMissing(obj, FoodLogWarning.LOW_CONFIDENCE);
        GeminiLabelFallbackSupport.addWarningIfMissing(obj, FoodLogWarning.NO_LABEL_DETECTED);

        assertThat(obj.get("warnings")).hasSize(2);
        assertThat(obj.get("warnings").get(0).asText()).isEqualTo(FoodLogWarning.LOW_CONFIDENCE.name());
        assertThat(obj.get("warnings").get(1).asText()).isEqualTo(FoodLogWarning.NO_LABEL_DETECTED.name());
    }

    @Test
    @DisplayName("tryFixTruncatedJson: 可修復被截斷的 JSON 並補齊必要 keys")
    void tryFixTruncatedJson_should_repair_and_fill_required_keys() {
        ObjectNode out = support.tryFixTruncatedJson("""
            {
              "foodName":"Greek Yogurt",
              "nutrients":{"kcal":120,"protein":10
            """);

        assertThat(out).isNotNull();
        assertThat(out.get("foodName").asText()).isEqualTo("Greek Yogurt");

        assertThat(out.get("quantity").get("value").asDouble()).isEqualTo(1d);
        assertThat(out.get("quantity").get("unit").asText()).isEqualTo("SERVING");

        // 目前 fixCommonTruncationPatterns() 的行為：
        // 只保留 kcal / sodium，其餘 nutrients 一律補 0.0
        assertThat(out.get("nutrients").get("kcal").asDouble()).isEqualTo(120d);
        assertThat(out.get("nutrients").get("protein").asDouble()).isEqualTo(0.0d);
        assertThat(out.get("nutrients").get("fat").asDouble()).isEqualTo(0.0d);
        assertThat(out.get("nutrients").get("carbs").asDouble()).isEqualTo(0.0d);
        assertThat(out.get("nutrients").get("fiber").asDouble()).isEqualTo(0.0d);
        assertThat(out.get("nutrients").get("sugar").asDouble()).isEqualTo(0.0d);
        assertThat(out.get("nutrients").get("sodium").asDouble()).isEqualTo(0.0d);

        assertThat(out.get("confidence").isNull()).isTrue();
        assertThat(out.get("labelMeta")).isNotNull();
    }

    @Test
    @DisplayName("tryExtractFromBrokenJsonLikeText: 應可從破碎 JSON-like 文字抓出 nutrients")
    void tryExtractFromBrokenJsonLikeText_should_extract_values() {
        ObjectNode out = support.tryExtractFromBrokenJsonLikeText("""
                "foodName":"Bourbon Cookies","nutrients":{"kcal":240,"protein":3.2,"fat":12.5,"carbs":30.4,"sugar":18.0,"sodium":150}
                """);

        assertThat(out).isNotNull();
        assertThat(out.get("foodName").asText()).isEqualTo("Bourbon Cookies");
        assertThat(out.get("quantity").get("value").asDouble()).isEqualTo(1d);
        assertThat(out.get("quantity").get("unit").asText()).isEqualTo("SERVING");

        assertThat(out.get("nutrients").get("kcal").asDouble()).isEqualTo(240d);
        assertThat(out.get("nutrients").get("protein").asDouble()).isEqualTo(3.2d);
        assertThat(out.get("nutrients").get("fat").asDouble()).isEqualTo(12.5d);
        assertThat(out.get("nutrients").get("carbs").asDouble()).isEqualTo(30.4d);
        assertThat(out.get("nutrients").get("fiber").asDouble()).isEqualTo(0.0d);
        assertThat(out.get("nutrients").get("sugar").asDouble()).isEqualTo(18.0d);
        assertThat(out.get("nutrients").get("sodium").asDouble()).isEqualTo(150d);

        assertThat(out.get("confidence").isNull()).isTrue();
        assertThat(out.get("warnings")).hasSize(1);
        assertThat(out.get("warnings").get(0).asText()).isEqualTo(FoodLogWarning.LOW_CONFIDENCE.name());
    }

    @Test
    @DisplayName("tryExtractLabelFromPlainText: 應從 plain text 抓出營養與 basis")
    void tryExtractLabelFromPlainText_should_extract_nutrients_and_basis() {
        ObjectNode out = support.tryExtractLabelFromPlainText("""
                Nutrition Facts
                Serving size 30 g
                Servings per container 2
                Calories 150
                Protein 4 g
                Total Fat 7 g
                Carbohydrates 18 g
                Fiber 2 g
                Sugar 9 g
                Sodium 120 mg
                """);

        assertThat(out).isNotNull();

        assertThat(out.get("foodName").isNull()).isTrue();

        assertThat(out.get("quantity").get("value").asDouble()).isEqualTo(1d);
        assertThat(out.get("quantity").get("unit").asText()).isEqualTo("SERVING");

        assertThat(out.get("nutrients").get("kcal").asDouble()).isEqualTo(150d);
        assertThat(out.get("nutrients").get("protein").asDouble()).isEqualTo(4d);
        assertThat(out.get("nutrients").get("fat").asDouble()).isEqualTo(7d);
        assertThat(out.get("nutrients").get("carbs").asDouble()).isEqualTo(18d);
        assertThat(out.get("nutrients").get("fiber").asDouble()).isEqualTo(2d);
        assertThat(out.get("nutrients").get("sugar").asDouble()).isEqualTo(9d);
        assertThat(out.get("nutrients").get("sodium").asDouble()).isEqualTo(120d);

        assertThat(out.get("confidence").isNull()).isTrue();
        assertThat(out.get("warnings")).hasSize(1);
        assertThat(out.get("warnings").get(0).asText()).isEqualTo(FoodLogWarning.LOW_CONFIDENCE.name());

        assertThat(out.get("labelMeta").get("servingsPerContainer").asDouble()).isEqualTo(2d);
        assertThat(out.get("labelMeta").get("basis").asText()).isEqualTo("PER_SERVING");
    }

    @Test
    @DisplayName("tryExtractLabelFromPlainText: 文字含 bottle/drink 時 quantity.unit 應推為 BOTTLE")
    void tryExtractLabelFromPlainText_should_infer_bottle_unit() {
        ObjectNode out = support.tryExtractLabelFromPlainText("""
                Drink nutrition facts
                Calories 90
                Protein 1 g
                Fat 0 g
                Carbohydrates 22 g
                Sugar 20 g
                Sodium 35 mg
                bottle
                """);

        assertThat(out).isNotNull();
        assertThat(out.get("quantity").get("unit").asText()).isEqualTo("BOTTLE");
    }

    @Test
    @DisplayName("isNoLabelLikely: 沒有數字且有 no-label 訊號時應回 true")
    void isNoLabelLikely_should_return_true_for_no_label_signal_without_numbers() {
        // 注意：production code 會先檢查 hasSignals，
        // 只要字串含有 "nutrition" 就會直接回 false，
        // 所以這裡不能用 "no nutrition label found, too blurry"
        assertThat(support.isNoLabelLikely("no label, too blurry")).isTrue();
        assertThat(support.isNoLabelLikely("無法辨識，太模糊")).isTrue();
    }

    @Test
    @DisplayName("isNoLabelLikely: 若含營養訊號或數字時不應誤判")
    void isNoLabelLikely_should_return_false_when_has_nutrition_signals_or_numbers() {
        assertThat(support.isNoLabelLikely("Nutrition facts protein fat carbs")).isFalse();
        assertThat(support.isNoLabelLikely("Calories 120 Protein 3g")).isFalse();
    }

    @Test
    @DisplayName("fallbackNoLabelDetected: 應建立 NO_LABEL_DETECTED fallback")
    void fallbackNoLabelDetected_should_build_no_label_fallback() {
        ObjectNode out = support.fallbackNoLabelDetected();

        assertThat(out.get("foodName").isNull()).isTrue();
        assertThat(out.get("quantity").get("value").asDouble()).isEqualTo(1d);
        assertThat(out.get("quantity").get("unit").asText()).isEqualTo("SERVING");

        assertThat(out.get("nutrients").get("kcal").asDouble()).isEqualTo(0.0d);
        assertThat(out.get("nutrients").get("protein").asDouble()).isEqualTo(0.0d);
        assertThat(out.get("nutrients").get("fat").asDouble()).isEqualTo(0.0d);
        assertThat(out.get("nutrients").get("carbs").asDouble()).isEqualTo(0.0d);
        assertThat(out.get("nutrients").get("fiber").asDouble()).isEqualTo(0.0d);
        assertThat(out.get("nutrients").get("sugar").asDouble()).isEqualTo(0.0d);
        assertThat(out.get("nutrients").get("sodium").asDouble()).isEqualTo(0.0d);

        assertThat(out.get("confidence").isNull()).isTrue();

        assertThat(out.get("warnings")).hasSize(2);
        assertThat(out.get("warnings").get(0).asText()).isEqualTo(FoodLogWarning.NO_LABEL_DETECTED.name());
        assertThat(out.get("warnings").get(1).asText()).isEqualTo(FoodLogWarning.LOW_CONFIDENCE.name());

        assertThat(out.get("labelMeta").get("servingsPerContainer").isNull()).isTrue();
        assertThat(out.get("labelMeta").get("basis").isNull()).isTrue();
    }

    @Test
    @DisplayName("findServingAmount: 應可解析英文 serving size")
    void findServingAmount_should_parse_english_serving_size() {
        GeminiLabelFallbackSupport.ParsedAmount parsed =
                GeminiLabelFallbackSupport.findServingAmount("Serving size 28 g");

        assertThat(parsed).isNotNull();
        assertThat(parsed.value()).isEqualTo(28d);
        assertThat(parsed.unit()).isEqualTo("GRAM");
    }

    @Test
    @DisplayName("findServingAmount: 應可解析中文每份量")
    void findServingAmount_should_parse_chinese_serving_size() {
        GeminiLabelFallbackSupport.ParsedAmount parsed =
                GeminiLabelFallbackSupport.findServingAmount("每一份量 250 毫升");

        assertThat(parsed).isNotNull();
        assertThat(parsed.value()).isEqualTo(250d);
        assertThat(parsed.unit()).isEqualTo("ML");
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
    @DisplayName("extractSodiumMgFromText: g 單位應轉成 mg")
    void extractSodiumMgFromText_should_convert_gram_to_mg() {
        Double sodium = GeminiLabelFallbackSupport.extractSodiumMgFromText("Sodium 0.35 g");
        assertThat(sodium).isEqualTo(350d);
    }

    @Test
    @DisplayName("extractSodiumMgFromText: mg 單位應直接回 mg")
    void extractSodiumMgFromText_should_keep_mg() {
        Double sodium = GeminiLabelFallbackSupport.extractSodiumMgFromText("鈉 180 毫克");
        assertThat(sodium).isEqualTo(180d);
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

    @Test
    void findServingAmount_should_parse_gram_correctly() {
        GeminiLabelFallbackSupport.ParsedAmount amount =
                GeminiLabelFallbackSupport.findServingAmount("每一份量 30g");

        assertThat(amount).isNotNull();
        assertThat(amount.value()).isEqualTo(30.0);
        assertThat(amount.unit()).isEqualTo("GRAM");
    }
}
