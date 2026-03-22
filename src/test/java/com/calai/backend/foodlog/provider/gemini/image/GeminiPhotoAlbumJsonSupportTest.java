package com.calai.backend.foodlog.provider.gemini.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeminiPhotoAlbumJsonSupportTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    @DisplayName("normalizeToEffective: 應正規化基本欄位與 nutrients")
    void normalizeToEffective_should_normalize_basic_fields() throws Exception {
        ObjectNode raw = (ObjectNode) om.readTree("""
                {
                  "foodName": "  Iced Latte  ",
                  "quantity": {
                    "value": 0,
                    "unit": "bottle"
                  },
                  "nutrients": {
                    "kcal": "418.4 kJ",
                    "protein": "2500 mg",
                    "fat": 3.25,
                    "carbs": "12.5 g",
                    "fiber": null,
                    "sugar": -1,
                    "sodium": "0.12 g"
                  },
                  "confidence": 0.88,
                  "labelMeta": {
                    "servingsPerContainer": 1,
                    "basis": "estimated_portion"
                  }
                }
                """);

        ObjectNode out = GeminiPhotoAlbumJsonSupport.normalizeToEffective(raw);

        assertThat(out.get("foodName").asText()).isEqualTo("Iced Latte");

        // value <= 0 會被保底成 1
        assertThat(out.get("quantity").get("value").asDouble()).isEqualTo(1.0d);
        assertThat(out.get("quantity").get("unit").asText()).isEqualTo("BOTTLE");

        // 418.4 kJ -> 100 kcal
        assertThat(out.get("nutrients").get("kcal").asDouble()).isCloseTo(100.0d, within(1e-9));
        // 2500 mg -> 2.5 g
        assertThat(out.get("nutrients").get("protein").asDouble()).isEqualTo(2.5d);
        assertThat(out.get("nutrients").get("fat").asDouble()).isEqualTo(3.25d);
        assertThat(out.get("nutrients").get("carbs").asDouble()).isEqualTo(12.5d);
        // 缺值 / 負值會補 0.0
        assertThat(out.get("nutrients").get("fiber").asDouble()).isEqualTo(0.0d);
        assertThat(out.get("nutrients").get("sugar").asDouble()).isEqualTo(0.0d);
        // 0.12 g sodium -> 120 mg
        assertThat(out.get("nutrients").get("sodium").asDouble()).isEqualTo(120.0d);

        assertThat(out.get("confidence").asDouble()).isEqualTo(0.88d);

        assertThat(out.get("labelMeta").get("servingsPerContainer").asDouble()).isEqualTo(1.0d);
        assertThat(out.get("labelMeta").get("basis").asText()).isEqualTo("ESTIMATED_PORTION");
    }

    @Test
    @DisplayName("normalizeToEffective: nutrients 缺失時應拋例外")
    void normalizeToEffective_should_throw_when_nutrients_missing() throws Exception {
        ObjectNode raw = (ObjectNode) om.readTree("""
                {
                  "foodName": "Americano",
                  "quantity": {
                    "value": 1,
                    "unit": "BOTTLE"
                  }
                }
                """);

        assertThatThrownBy(() -> GeminiPhotoAlbumJsonSupport.normalizeToEffective(raw))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PROVIDER_BAD_RESPONSE");
    }

    @Test
    @DisplayName("finalizeEffective: 應修正 quantity / confidence / warnings / labelMeta 並 round nutrients")
    void finalizeEffective_should_fix_and_round_fields() throws Exception {
        ObjectNode effective = (ObjectNode) om.readTree("""
                {
                  "foodName": "Protein Shake",
                  "quantity": {
                    "value": 0,
                    "unit": "bottle"
                  },
                  "nutrients": {
                    "kcal": 100.04,
                    "protein": 25.05,
                    "fat": 2.24,
                    "carbs": 3.26,
                    "fiber": 0.04,
                    "sugar": 0.05,
                    "sodium": 119.96
                  },
                  "confidence": 85,
                  "labelMeta": {
                    "servingsPerContainer": 0,
                    "basis": "weird_basis"
                  }
                }
                """);

        GeminiPhotoAlbumJsonSupport.finalizeEffective(effective);

        // quantity 修正
        assertThat(effective.get("quantity").get("value").asDouble()).isEqualTo(1.0d);
        assertThat(effective.get("quantity").get("unit").asText()).isEqualTo("BOTTLE");

        // nutrients round 到 1 位
        assertThat(effective.get("nutrients").get("kcal").asDouble()).isEqualTo(100.0d);
        assertThat(effective.get("nutrients").get("protein").asDouble()).isEqualTo(25.1d);
        assertThat(effective.get("nutrients").get("fat").asDouble()).isEqualTo(2.2d);
        assertThat(effective.get("nutrients").get("carbs").asDouble()).isEqualTo(3.3d);
        // < 0.05 會變 0.0
        assertThat(effective.get("nutrients").get("fiber").asDouble()).isEqualTo(0.0d);
        assertThat(effective.get("nutrients").get("sugar").asDouble()).isEqualTo(0.1d);
        assertThat(effective.get("nutrients").get("sodium").asDouble()).isEqualTo(120.0d);

        // confidence 只接受 0.0 ~ 1.0，85 會被清成 null
        assertThat(effective.get("confidence").isNull()).isTrue();

        // warnings 不存在時會補空 array
        assertThat(effective.get("warnings")).isNotNull();
        assertThat(effective.get("warnings").isArray()).isTrue();
        assertThat(effective.get("warnings")).hasSize(0);

        // labelMeta 正規化
        assertThat(effective.get("labelMeta").get("servingsPerContainer").isNull()).isTrue();
        assertThat(effective.get("labelMeta").get("basis").isNull()).isTrue();
    }

    @Test
    @DisplayName("finalizeEffective: 合法 confidence 與 labelMeta 應保留")
    void finalizeEffective_should_keep_valid_confidence_and_label_meta() throws Exception {
        ObjectNode effective = (ObjectNode) om.readTree("""
                {
                  "quantity": {
                    "value": 1,
                    "unit": "pack"
                  },
                  "nutrients": {
                    "kcal": 240.0,
                    "protein": 4.0,
                    "fat": 8.0,
                    "carbs": 30.0,
                    "fiber": 2.0,
                    "sugar": 12.0,
                    "sodium": 180.0
                  },
                  "confidence": 0.67,
                  "warnings": [],
                  "labelMeta": {
                    "servingsPerContainer": 2,
                    "basis": "per_serving"
                  }
                }
                """);

        GeminiPhotoAlbumJsonSupport.finalizeEffective(effective);

        assertThat(effective.get("quantity").get("value").asDouble()).isEqualTo(1.0d);
        assertThat(effective.get("quantity").get("unit").asText()).isEqualTo("PACK");

        assertThat(effective.get("confidence").asDouble()).isEqualTo(0.67d);
        assertThat(effective.get("labelMeta").get("servingsPerContainer").asDouble()).isEqualTo(2.0d);
        assertThat(effective.get("labelMeta").get("basis").asText()).isEqualTo("PER_SERVING");
    }

    @Test
    @DisplayName("isWholeContainerLike: PACK / BOTTLE / CAN 應回 true")
    void isWholeContainerLike_should_return_true_for_container_units() throws Exception {
        JsonNode pack = om.readTree("""
                {
                  "quantity": { "unit": "PACK" }
                }
                """);
        JsonNode bottle = om.readTree("""
                {
                  "quantity": { "unit": "BOTTLE" }
                }
                """);
        JsonNode can = om.readTree("""
                {
                  "quantity": { "unit": "CAN" }
                }
                """);

        assertThat(GeminiPhotoAlbumJsonSupport.isWholeContainerLike(pack)).isTrue();
        assertThat(GeminiPhotoAlbumJsonSupport.isWholeContainerLike(bottle)).isTrue();
        assertThat(GeminiPhotoAlbumJsonSupport.isWholeContainerLike(can)).isTrue();
    }

    @Test
    @DisplayName("isWholeContainerLike: labelMeta basis 或 servingsPerContainer 命中時應回 true")
    void isWholeContainerLike_should_return_true_for_basis_or_servings() throws Exception {
        JsonNode basisWholePackage = om.readTree("""
                {
                  "quantity": { "unit": "SERVING" },
                  "labelMeta": { "basis": "WHOLE_PACKAGE" }
                }
                """);

        JsonNode basisPerServing = om.readTree("""
                {
                  "quantity": { "unit": "SERVING" },
                  "labelMeta": { "basis": "PER_SERVING" }
                }
                """);

        JsonNode servingsPositive = om.readTree("""
                {
                  "quantity": { "unit": "SERVING" },
                  "labelMeta": { "servingsPerContainer": 2 }
                }
                """);

        assertThat(GeminiPhotoAlbumJsonSupport.isWholeContainerLike(basisWholePackage)).isTrue();
        assertThat(GeminiPhotoAlbumJsonSupport.isWholeContainerLike(basisPerServing)).isTrue();
        assertThat(GeminiPhotoAlbumJsonSupport.isWholeContainerLike(servingsPositive)).isTrue();
    }

    @Test
    @DisplayName("isWholeContainerLike: 純 SERVING 且沒有 basis / servingsPerContainer 時應回 false")
    void isWholeContainerLike_should_return_false_for_plain_serving() throws Exception {
        JsonNode root = om.readTree("""
                {
                  "quantity": { "unit": "SERVING" },
                  "labelMeta": { "servingsPerContainer": null, "basis": null }
                }
                """);

        assertThat(GeminiPhotoAlbumJsonSupport.isWholeContainerLike(root)).isFalse();
    }

    @Test
    @DisplayName("allCoreQuartetZero: kcal/protein/fat/carbs 全 0 或 null 時應回 true")
    void allCoreQuartetZero_should_return_true_when_core_all_zero_or_null() throws Exception {
        JsonNode root = om.readTree("""
                {
                  "nutrients": {
                    "kcal": 0,
                    "protein": null,
                    "fat": 0.0,
                    "carbs": "0",
                    "fiber": 10,
                    "sugar": 5,
                    "sodium": 100
                  }
                }
                """);

        assertThat(GeminiPhotoAlbumJsonSupport.allCoreQuartetZero(root)).isTrue();
    }

    @Test
    @DisplayName("allCoreQuartetZero: 任一核心營養非 0 時應回 false")
    void allCoreQuartetZero_should_return_false_when_any_core_is_non_zero() throws Exception {
        JsonNode root = om.readTree("""
                {
                  "nutrients": {
                    "kcal": 0,
                    "protein": 1.5,
                    "fat": 0,
                    "carbs": 0
                  }
                }
                """);

        assertThat(GeminiPhotoAlbumJsonSupport.allCoreQuartetZero(root)).isFalse();
    }

    @Test
    @DisplayName("allCoreQuartetZero: root 或 nutrients 無效時應回 true")
    void allCoreQuartetZero_should_return_true_for_invalid_root_or_missing_nutrients() throws Exception {
        assertThat(GeminiPhotoAlbumJsonSupport.allCoreQuartetZero(null)).isTrue();
        assertThat(GeminiPhotoAlbumJsonSupport.allCoreQuartetZero(om.readTree("{}"))).isTrue();
        assertThat(GeminiPhotoAlbumJsonSupport.allCoreQuartetZero(om.readTree("[]"))).isTrue();
    }
}
