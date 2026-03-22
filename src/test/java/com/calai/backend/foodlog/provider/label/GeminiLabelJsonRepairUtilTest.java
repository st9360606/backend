package com.calai.backend.foodlog.provider.gemini.label;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiLabelJsonRepairUtilTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    @DisplayName("repairOrExtract: rawText 為 null 或 blank 時應回 null")
    void repairOrExtract_should_return_null_for_null_or_blank() {
        assertThat(GeminiLabelJsonRepairUtil.repairOrExtract(om, null, true)).isNull();
        assertThat(GeminiLabelJsonRepairUtil.repairOrExtract(om, "", true)).isNull();
        assertThat(GeminiLabelJsonRepairUtil.repairOrExtract(om, "   ", false)).isNull();
    }

    @Test
    @DisplayName("repairOrExtract: 應可從前後雜訊中擷取第一段完整 JSON")
    void repairOrExtract_should_extract_first_json_payload_from_noise() {
        ObjectNode out = GeminiLabelJsonRepairUtil.repairOrExtract(
                om,
                """
                some prefix text
                {"foodName":"Milk Tea","quantity":{"value":1,"unit":"BOTTLE"},"nutrients":{"kcal":120}}
                some suffix text
                """,
                true
        );

        assertThat(out).isNotNull();
        assertThat(out.get("foodName").asText()).isEqualTo("Milk Tea");
        assertThat(out.get("quantity").get("value").asDouble()).isEqualTo(1d);
        assertThat(out.get("quantity").get("unit").asText()).isEqualTo("BOTTLE");
        assertThat(out.get("nutrients").get("kcal").asDouble()).isEqualTo(120d);

        // parse 成功就直接回原物件，不會自動補 warnings / labelMeta
        assertThat(out.get("warnings")).isNull();
        assertThat(out.get("labelMeta")).isNull();
    }

    @Test
    @DisplayName("repairOrExtract: unit 尾巴卡在冒號時應補成 SERVING")
    void repairOrExtract_should_fill_unit_with_serving_when_tail_ends_with_colon() {
        ObjectNode out = GeminiLabelJsonRepairUtil.repairOrExtract(
                om,
                """
                {"quantity":{"value":1,"unit":
                """,
                true
        );

        assertThat(out).isNotNull();
        assertThat(out.get("quantity").get("value").asDouble()).isEqualTo(1d);
        assertThat(out.get("quantity").get("unit").asText()).isEqualTo("SERVING");
    }

    @Test
    @DisplayName("repairOrExtract: basis 尾巴卡在冒號時應補成 PER_SERVING")
    void repairOrExtract_should_fill_basis_with_per_serving_when_tail_ends_with_colon() {
        ObjectNode out = GeminiLabelJsonRepairUtil.repairOrExtract(
                om,
                """
                {"labelMeta":{"basis":
                """,
                true
        );

        assertThat(out).isNotNull();
        assertThat(out.get("labelMeta").get("basis").asText()).isEqualTo("PER_SERVING");
    }

    @Test
    @DisplayName("repairOrExtract: number 尾巴卡在 140. 時應修成 140.0")
    void repairOrExtract_should_fix_dangling_number_dot() {
        ObjectNode out = GeminiLabelJsonRepairUtil.repairOrExtract(
                om,
                """
                {"nutrients":{"kcal":140.
                """,
                true
        );

        assertThat(out).isNotNull();
        assertThat(out.get("nutrients").get("kcal").asDouble()).isEqualTo(140.0d);
    }

    @Test
    @DisplayName("repairOrExtract: 尾巴卡在半截 key 時應移除半截尾巴並保留前面已完成欄位")
    void repairOrExtract_should_remove_dangling_half_key_tail() {
        ObjectNode out = GeminiLabelJsonRepairUtil.repairOrExtract(
                om,
                """
                {"foodName":"Cookie","confidence":0.95,"wa
                """,
                true
        );

        assertThat(out).isNotNull();
        assertThat(out.get("foodName").asText()).isEqualTo("Cookie");
        assertThat(out.get("confidence").asDouble()).isEqualTo(0.95d);
    }

    @Test
    @DisplayName("repairOrExtract: parse 失敗時應從 broken JSON-like 文字抽出欄位（strict=true）")
    void repairOrExtract_should_extract_from_broken_json_like_text_in_strict_mode() {
        ObjectNode out = GeminiLabelJsonRepairUtil.repairOrExtract(
                om,
                """
                "foodName":"Bourbon Cookies",
                "value":1,
                "unit":"PACK",
                "kcal":240,
                "protein":3.2,
                "fat":12.5,
                "carbs":30.4,
                "sugar":18.0,
                "sodium":150,
                "confidence":0.82
                """,
                true
        );

        assertThat(out).isNotNull();

        assertThat(out.get("foodName").asText()).isEqualTo("Bourbon Cookies");

        assertThat(out.get("quantity").get("value").asDouble()).isEqualTo(1d);
        assertThat(out.get("quantity").get("unit").asText()).isEqualTo("PACK");

        assertThat(out.get("nutrients").get("kcal").asDouble()).isEqualTo(240d);
        assertThat(out.get("nutrients").get("protein").asDouble()).isEqualTo(3.2d);
        assertThat(out.get("nutrients").get("fat").asDouble()).isEqualTo(12.5d);
        assertThat(out.get("nutrients").get("carbs").asDouble()).isEqualTo(30.4d);
        assertThat(out.get("nutrients").get("fiber").isNull()).isTrue();
        assertThat(out.get("nutrients").get("sugar").asDouble()).isEqualTo(18.0d);
        assertThat(out.get("nutrients").get("sodium").asDouble()).isEqualTo(150d);

        assertThat(out.get("confidence").asDouble()).isEqualTo(0.82d);

        // strict=true 時會補 warnings / labelMeta
        assertThat(out.get("warnings")).isNotNull();
        assertThat(out.get("warnings").isArray()).isTrue();
        assertThat(out.get("warnings")).hasSize(0);

        assertThat(out.get("labelMeta")).isNotNull();
        assertThat(out.get("labelMeta").get("servingsPerContainer").isNull()).isTrue();
        assertThat(out.get("labelMeta").get("basis").isNull()).isTrue();
    }

    @Test
    @DisplayName("repairOrExtract: parse 失敗時 strict=false 不應自動補 warnings / labelMeta")
    void repairOrExtract_should_extract_from_broken_json_like_text_without_strict_extras() {
        ObjectNode out = GeminiLabelJsonRepairUtil.repairOrExtract(
                om,
                """
                "foodName":"Greek Yogurt",
                "kcal":120,
                "protein":10,
                "confidence":0.91
                """,
                false
        );

        assertThat(out).isNotNull();
        assertThat(out.get("foodName").asText()).isEqualTo("Greek Yogurt");
        assertThat(out.get("nutrients").get("kcal").asDouble()).isEqualTo(120d);
        assertThat(out.get("nutrients").get("protein").asDouble()).isEqualTo(10d);
        assertThat(out.get("confidence").asDouble()).isEqualTo(0.91d);

        assertThat(out.get("warnings")).isNull();
        assertThat(out.get("labelMeta")).isNull();
    }

    @Test
    @DisplayName("repairOrExtract: broken JSON-like 缺 quantity 時 quantity.value / unit 應為 null")
    void repairOrExtract_should_set_null_quantity_fields_when_missing_in_broken_text() {
        ObjectNode out = GeminiLabelJsonRepairUtil.repairOrExtract(
                om,
                """
                "foodName":"Tea Egg",
                "kcal":80,
                "protein":6.5
                """,
                true
        );

        assertThat(out).isNotNull();
        assertThat(out.get("foodName").asText()).isEqualTo("Tea Egg");
        assertThat(out.get("quantity").get("value").isNull()).isTrue();
        assertThat(out.get("quantity").get("unit").isNull()).isTrue();
        assertThat(out.get("nutrients").get("kcal").asDouble()).isEqualTo(80d);
        assertThat(out.get("nutrients").get("protein").asDouble()).isEqualTo(6.5d);
    }
}