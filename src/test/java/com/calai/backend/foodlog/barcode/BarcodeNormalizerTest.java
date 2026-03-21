package com.calai.backend.foodlog.barcode;

import com.calai.backend.foodlog.barcode.normalize.BarcodeNormalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 測試 BarcodeNormalizer.normalizeOrThrow()：
 * 1. 必填/非法輸入
 * 2. digitsOnly 擷取
 * 3. leading zeros 移除
 * 4. 依 OFF 規則補到 8 / 13 碼
 * 5. 8 / 13 / 14 碼保留
 */
class BarcodeNormalizerTest {

    @Nested
    @DisplayName("錯誤輸入")
    class InvalidInputTests {

        @Test
        @DisplayName("raw = null 時應拋 BARCODE_REQUIRED")
        void should_throw_required_when_raw_is_null() {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> BarcodeNormalizer.normalizeOrThrow(null)
            );

            assertEquals("BARCODE_REQUIRED", ex.getMessage());
        }

        @ParameterizedTest(name = "[{index}] blank input = ''{0}''")
        @CsvSource({
                "' '",
                "'   '",
                "'\t'",
                "'\n'"
        })
        @DisplayName("blank 輸入時應拋 BARCODE_REQUIRED")
        void should_throw_required_when_raw_is_blank(String raw) {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> BarcodeNormalizer.normalizeOrThrow(raw)
            );

            assertEquals("BARCODE_REQUIRED", ex.getMessage());
        }

        @ParameterizedTest(name = "[{index}] invalid input = ''{0}''")
        @CsvSource({
                "'abc'",
                "'---'",
                "'___'",
                "'中文'",
                "'@#$%'"
        })
        @DisplayName("沒有任何 digits 時應拋 BARCODE_INVALID")
        void should_throw_invalid_when_no_digits(String raw) {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> BarcodeNormalizer.normalizeOrThrow(raw)
            );

            assertEquals("BARCODE_INVALID", ex.getMessage());
        }

        @Test
        @DisplayName("digits 超過 32 碼時應拋 BARCODE_INVALID")
        void should_throw_invalid_when_digits_too_long() {
            String raw = "123456789012345678901234567890123"; // 33 digits

            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> BarcodeNormalizer.normalizeOrThrow(raw)
            );

            assertEquals("BARCODE_INVALID", ex.getMessage());
        }
    }

    @Nested
    @DisplayName("正常 normalize")
    class NormalizeTests {

        @Test
        @DisplayName("應保留 trimmed rawInput，並只抽出 digitsOnly")
        void should_keep_trimmed_raw_and_extract_digits_only() {
            BarcodeNormalizer.Norm norm = BarcodeNormalizer.normalizeOrThrow("  00-123 45-67  ");

            assertEquals("00-123 45-67", norm.rawInput());
            assertEquals("001234567", norm.digitsOnly());
            assertEquals("01234567", norm.normalized());
        }

        @ParameterizedTest(name = "[{index}] raw={0}, digitsOnly={1}, normalized={2}")
        @CsvSource({
                // <= 7 碼：補到 8
                "1,1,00000001",
                "1234567,1234567,01234567",
                "0001234,0001234,00001234",

                // 8 碼：保留
                "12345678,12345678,12345678",
                "00000001,00000001,00000001",

                // 9..12 碼：補到 13
                "123456789,123456789,0000123456789",
                "1234567890,1234567890,0001234567890",
                "123456789012,123456789012,0123456789012",

                // 13 碼：保留
                "4710008211020,4710008211020,4710008211020",

                // 14 碼：保留
                "12345678901234,12345678901234,12345678901234",

                // 有前導零時，先去零再依長度補回
                "0004710008211020,0004710008211020,4710008211020",
                "0001234567890,0001234567890,0001234567890"
        })
        @DisplayName("應依規則移除前導零並補到 8 或 13 碼")
        void should_normalize_by_off_rule(String raw, String digitsOnly, String normalized) {
            BarcodeNormalizer.Norm norm = BarcodeNormalizer.normalizeOrThrow(raw);

            assertEquals(raw, norm.rawInput());
            assertEquals(digitsOnly, norm.digitsOnly());
            assertEquals(normalized, norm.normalized());
        }

        @Test
        @DisplayName("全 0 條碼應保留 1 個 0 再補到 8 碼")
        void should_normalize_all_zero_digits_to_8_zeroes() {
            BarcodeNormalizer.Norm norm = BarcodeNormalizer.normalizeOrThrow("00000000");

            assertEquals("00000000", norm.digitsOnly());
            assertEquals("00000000", norm.normalized());
        }

        @Test
        @DisplayName("混合 scanner 雜訊時應只取 digits")
        void should_extract_digits_from_scanner_noise() {
            BarcodeNormalizer.Norm norm = BarcodeNormalizer.normalizeOrThrow("ABC-49 0210 2084178-XYZ");

            assertEquals("ABC-49 0210 2084178-XYZ", norm.rawInput());
            assertEquals("4902102084178", norm.digitsOnly());
            assertEquals("4902102084178", norm.normalized());
        }
    }
}
