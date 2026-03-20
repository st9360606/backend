package com.calai.backend.foodlog.barcode;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BarcodeNutrientsNormalizerTest {

    @Nested
    @DisplayName("fillMissingWithZero")
    class FillMissingWithZeroTests {

        @Test
        @DisplayName("effective 為 null 時應安全略過")
        void should_do_nothing_when_effective_is_null() {
            assertDoesNotThrow(() -> BarcodeNutrientsNormalizer.fillMissingWithZero(null));
        }

        @Test
        @DisplayName("缺少 nutrients 節點時應自動建立並補齊全部 0.0")
        void should_create_nutrients_and_fill_all_zero_when_missing() {
            ObjectNode effective = JsonNodeFactory.instance.objectNode();

            BarcodeNutrientsNormalizer.fillMissingWithZero(effective);

            ObjectNode nutrients = (ObjectNode) effective.get("nutrients");
            assertNotNull(nutrients);

            assertEquals(0.0, nutrients.get("kcal").asDouble());
            assertEquals(0.0, nutrients.get("protein").asDouble());
            assertEquals(0.0, nutrients.get("fat").asDouble());
            assertEquals(0.0, nutrients.get("carbs").asDouble());
            assertEquals(0.0, nutrients.get("fiber").asDouble());
            assertEquals(0.0, nutrients.get("sugar").asDouble());
            assertEquals(0.0, nutrients.get("sodium").asDouble());
        }

        @Test
        @DisplayName("既有有效數字應保留不變")
        void should_keep_valid_numeric_values() {
            ObjectNode effective = JsonNodeFactory.instance.objectNode();
            ObjectNode nutrients = effective.putObject("nutrients");
            nutrients.put("kcal", 123.4);
            nutrients.put("protein", 8.5);
            nutrients.put("fat", 9.9);
            nutrients.put("carbs", 20.0);
            nutrients.put("fiber", 2.1);
            nutrients.put("sugar", 10.2);
            nutrients.put("sodium", 300.0);

            BarcodeNutrientsNormalizer.fillMissingWithZero(effective);

            assertEquals(123.4, nutrients.get("kcal").asDouble());
            assertEquals(8.5, nutrients.get("protein").asDouble());
            assertEquals(9.9, nutrients.get("fat").asDouble());
            assertEquals(20.0, nutrients.get("carbs").asDouble());
            assertEquals(2.1, nutrients.get("fiber").asDouble());
            assertEquals(10.2, nutrients.get("sugar").asDouble());
            assertEquals(300.0, nutrients.get("sodium").asDouble());
        }

        @Test
        @DisplayName("null / 空字串 / 非數字 / 非有限數值 / 奇怪型別都應補成 0.0")
        void should_replace_invalid_values_with_zero() {
            ObjectNode effective = JsonNodeFactory.instance.objectNode();
            ObjectNode nutrients = effective.putObject("nutrients");

            nutrients.putNull("kcal");
            nutrients.put("protein", "");
            nutrients.put("fat", "   ");
            nutrients.put("carbs", "abc");
            nutrients.put("fiber", Double.POSITIVE_INFINITY);
            nutrients.put("sugar", Double.NaN);
            nutrients.putObject("sodium"); // object type

            BarcodeNutrientsNormalizer.fillMissingWithZero(effective);

            assertEquals(0.0, nutrients.get("kcal").asDouble());
            assertEquals(0.0, nutrients.get("protein").asDouble());
            assertEquals(0.0, nutrients.get("fat").asDouble());
            assertEquals(0.0, nutrients.get("carbs").asDouble());
            assertEquals(0.0, nutrients.get("fiber").asDouble());
            assertEquals(0.0, nutrients.get("sugar").asDouble());
            assertEquals(0.0, nutrients.get("sodium").asDouble());
        }

        @Test
        @DisplayName("可 parse 的文字數字應轉成數字")
        void should_parse_textual_numbers() {
            ObjectNode effective = JsonNodeFactory.instance.objectNode();
            ObjectNode nutrients = effective.putObject("nutrients");

            nutrients.put("kcal", "123.4");
            nutrients.put("protein", " 8.5 ");
            nutrients.put("fat", "0");
            nutrients.put("carbs", "20.25");
            nutrients.put("fiber", "1.0");
            nutrients.put("sugar", "9.99");
            nutrients.put("sodium", "300");

            BarcodeNutrientsNormalizer.fillMissingWithZero(effective);

            assertEquals(123.4, nutrients.get("kcal").asDouble());
            assertEquals(8.5, nutrients.get("protein").asDouble());
            assertEquals(0.0, nutrients.get("fat").asDouble());
            assertEquals(20.25, nutrients.get("carbs").asDouble());
            assertEquals(1.0, nutrients.get("fiber").asDouble());
            assertEquals(9.99, nutrients.get("sugar").asDouble());
            assertEquals(300.0, nutrients.get("sodium").asDouble());
        }
    }

    @Nested
    @DisplayName("readNumber")
    class ReadNumberTests {

        @Test
        @DisplayName("nutrients 為 null 時，zeroIfMissing=true 應回 0.0")
        void should_return_zero_when_nutrients_null_and_zero_if_missing_true() {
            Double v = BarcodeNutrientsNormalizer.readNumber(null, "kcal", true);
            assertEquals(0.0, v);
        }

        @Test
        @DisplayName("nutrients 為 null 時，zeroIfMissing=false 應回 null")
        void should_return_null_when_nutrients_null_and_zero_if_missing_false() {
            Double v = BarcodeNutrientsNormalizer.readNumber(null, "kcal", false);
            assertNull(v);
        }

        @Test
        @DisplayName("缺欄位時應依 zeroIfMissing 決定回 0.0 或 null")
        void should_handle_missing_field_by_flag() {
            ObjectNode nutrients = JsonNodeFactory.instance.objectNode();

            assertEquals(0.0, BarcodeNutrientsNormalizer.readNumber(nutrients, "kcal", true));
            assertNull(BarcodeNutrientsNormalizer.readNumber(nutrients, "kcal", false));
        }

        @Test
        @DisplayName("數字欄位應正確讀取")
        void should_read_numeric_value() {
            ObjectNode nutrients = JsonNodeFactory.instance.objectNode();
            nutrients.put("kcal", 123.4);

            assertEquals(123.4, BarcodeNutrientsNormalizer.readNumber(nutrients, "kcal", true));
            assertEquals(123.4, BarcodeNutrientsNormalizer.readNumber(nutrients, "kcal", false));
        }

        @Test
        @DisplayName("文字數字應正確 parse")
        void should_parse_textual_number() {
            ObjectNode nutrients = JsonNodeFactory.instance.objectNode();
            nutrients.put("protein", " 8.5 ");

            assertEquals(8.5, BarcodeNutrientsNormalizer.readNumber(nutrients, "protein", true));
            assertEquals(8.5, BarcodeNutrientsNormalizer.readNumber(nutrients, "protein", false));
        }

        @Test
        @DisplayName("空字串應依 zeroIfMissing 決定回 0.0 或 null")
        void should_handle_blank_text_by_flag() {
            ObjectNode nutrients = JsonNodeFactory.instance.objectNode();
            nutrients.put("fat", "   ");

            assertEquals(0.0, BarcodeNutrientsNormalizer.readNumber(nutrients, "fat", true));
            assertNull(BarcodeNutrientsNormalizer.readNumber(nutrients, "fat", false));
        }

        @Test
        @DisplayName("非數字字串應依 zeroIfMissing 決定回 0.0 或 null")
        void should_handle_invalid_text_by_flag() {
            ObjectNode nutrients = JsonNodeFactory.instance.objectNode();
            nutrients.put("carbs", "abc");

            assertEquals(0.0, BarcodeNutrientsNormalizer.readNumber(nutrients, "carbs", true));
            assertNull(BarcodeNutrientsNormalizer.readNumber(nutrients, "carbs", false));
        }

        @Test
        @DisplayName("非有限數值應依 zeroIfMissing 決定回 0.0 或 null")
        void should_handle_non_finite_number_by_flag() {
            ObjectNode nutrients = JsonNodeFactory.instance.objectNode();
            nutrients.put("fiber", Double.POSITIVE_INFINITY);

            assertEquals(0.0, BarcodeNutrientsNormalizer.readNumber(nutrients, "fiber", true));
            assertNull(BarcodeNutrientsNormalizer.readNumber(nutrients, "fiber", false));
        }

        @Test
        @DisplayName("奇怪型別應依 zeroIfMissing 決定回 0.0 或 null")
        void should_handle_weird_node_type_by_flag() {
            ObjectNode nutrients = JsonNodeFactory.instance.objectNode();
            nutrients.putObject("sodium");

            assertEquals(0.0, BarcodeNutrientsNormalizer.readNumber(nutrients, "sodium", true));
            assertNull(BarcodeNutrientsNormalizer.readNumber(nutrients, "sodium", false));
        }
    }
}
