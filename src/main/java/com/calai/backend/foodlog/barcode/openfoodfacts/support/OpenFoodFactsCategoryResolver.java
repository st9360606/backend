package com.calai.backend.foodlog.barcode.openfoodfacts.support;

import com.calai.backend.foodlog.barcode.openfoodfacts.mapper.OpenFoodFactsMapper.OffResult;
import com.calai.backend.foodlog.model.FoodCategory;
import com.calai.backend.foodlog.model.FoodSubCategory;

import java.util.List;
import java.util.Locale;

/**
 * Precision-first canonical category resolver for OpenFoodFacts products.
 * 原則：
 * 1. 先吃 OFF taxonomy tags（穩定度最高）
 * 2. 再 fallback productName
 * 3. productName fallback 必須保守：寧可 UNKNOWN / PACKAGED_FOOD，也不要誤分到飲料
 */
public final class OpenFoodFactsCategoryResolver {

    private OpenFoodFactsCategoryResolver() {}

    public record Resolved(FoodCategory category, FoodSubCategory subCategory) {}

    public static Resolved resolve(OffResult off) {
        if (off == null) {
            return new Resolved(FoodCategory.UNKNOWN, FoodSubCategory.UNKNOWN);
        }

        // 1) tags first
        Resolved byTags = resolveFromTags(off.categoryTags());
        if (byTags != null) {
            return byTags;
        }

        // 2) fallback by product name
        if (off.productName() != null && !off.productName().isBlank()) {
            return resolveFromName(off.productName());
        }

        // 3) 最後 fallback
        return new Resolved(FoodCategory.PACKAGED_FOOD, FoodSubCategory.UNKNOWN);
    }

    private static Resolved resolveFromTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }

        // ===== TEA =====
        if (hasAnyTag(tags,
                "teas",
                "green-teas",
                "black-teas",
                "oolong-teas",
                "jasmine-teas",
                "tea-drinks",
                "iced-teas",
                "tea-based-beverages")) {
            return new Resolved(FoodCategory.BEVERAGE, FoodSubCategory.TEA);
        }

        // ===== COFFEE =====
        if (hasAnyTag(tags,
                "coffees",
                "coffee-drinks",
                "coffee-based-beverages",
                "iced-coffees",
                "black-coffees",
                "espresso-drinks")) {
            return new Resolved(FoodCategory.BEVERAGE, FoodSubCategory.COFFEE);
        }

        // ===== SPARKLING WATER =====
        if (hasAnyTag(tags,
                "sparkling-waters",
                "carbonated-waters",
                "soda-waters")) {
            return new Resolved(FoodCategory.BEVERAGE, FoodSubCategory.SPARKLING_WATER);
        }

        // ===== WATER =====
        if (hasAnyTag(tags,
                "waters",
                "mineral-waters",
                "spring-waters",
                "drinking-waters",
                "purified-waters")) {
            return new Resolved(FoodCategory.BEVERAGE, FoodSubCategory.WATER);
        }

        // ===== BROTH =====
        if (hasAnyTag(tags,
                "broths",
                "bouillons",
                "consommes",
                "clear-soups")) {
            return new Resolved(FoodCategory.SOUP, FoodSubCategory.BROTH);
        }

        return null;
    }

    /**
     * ✅ P0-3：名稱 fallback 改保守
     * - 不再用 generic "coffee" / "tea" / "water" 單字直接判斷
     * - 先排除 candy / cookie / cake / mochi / jelly 等固體零食甜點
     * - 只接受強訊號 phrase
     */
    private static Resolved resolveFromName(String productName) {
        String name = normalize(productName);

        if (name.isBlank()) {
            return new Resolved(FoodCategory.PACKAGED_FOOD, FoodSubCategory.UNKNOWN);
        }

        // 順序：先更明確的水類，再茶/咖啡，再湯
        if (looksLikeSparklingWater(name)) {
            return new Resolved(FoodCategory.BEVERAGE, FoodSubCategory.SPARKLING_WATER);
        }

        if (looksLikePlainWater(name)) {
            return new Resolved(FoodCategory.BEVERAGE, FoodSubCategory.WATER);
        }

        if (looksLikeTeaBeverage(name)) {
            return new Resolved(FoodCategory.BEVERAGE, FoodSubCategory.TEA);
        }

        if (looksLikeCoffeeBeverage(name)) {
            return new Resolved(FoodCategory.BEVERAGE, FoodSubCategory.COFFEE);
        }

        if (looksLikeBroth(name)) {
            return new Resolved(FoodCategory.SOUP, FoodSubCategory.BROTH);
        }

        return new Resolved(FoodCategory.PACKAGED_FOOD, FoodSubCategory.UNKNOWN);
    }

    private static boolean looksLikeTeaBeverage(String name) {
        if (hasSolidSnackDessertKeyword(name)) return false;

        return containsAny(name,
                // English
                "green tea", "black tea", "oolong tea", "jasmine tea",
                "iced tea", "tea beverage", "tea drink", "bottled tea", "milk tea",

                // Traditional / Simplified Chinese
                "綠茶", "绿茶", "紅茶", "红茶", "烏龍茶", "乌龙茶",
                "茉莉花茶", "冰紅茶", "冰红茶", "奶茶", "茶飲", "茶饮",

                // Japanese
                "緑茶", "ウーロン茶", "ジャスミン茶", "アイスティー", "ミルクティー",

                // Korean
                "녹차", "홍차", "우롱차", "자스민차", "아이스티", "밀크티", "차음료",

                // EU languages
                "té verde", "te verde", "té negro", "te negro",
                "thé vert", "the vert", "thé noir", "the noir",
                "grüner tee", "gruener tee", "schwarzer tee",
                "tè verde", "te verde", "tè nero", "te nero",
                "chá verde", "cha verde", "chá preto", "cha preto"
        );
    }

    private static boolean looksLikeCoffeeBeverage(String name) {
        if (hasSolidSnackDessertKeyword(name)) return false;

        return containsAny(name,
                // English
                "black coffee", "iced coffee", "cold brew",
                "americano", "espresso",
                "coffee drink", "bottled coffee",
                "cafe latte", "coffee latte", "latte macchiato", "frappuccino",

                // Traditional / Simplified Chinese
                "黑咖啡", "美式咖啡", "濃縮咖啡", "浓缩咖啡", "冷萃咖啡",
                "咖啡飲料", "咖啡饮料", "拿鐵咖啡", "拿铁咖啡",

                // Japanese
                "ブラックコーヒー", "アメリカーノ", "エスプレッソ", "アイスコーヒー", "カフェラテ",

                // Korean
                "블랙커피", "아메리카노", "에스프레소", "아이스커피", "카페라떼", "커피음료",

                // EU languages
                "café americano", "cafe americano", "café noir", "cafe noir",
                "kaffeegetränk", "eiskaffee",
                "caffè americano", "caffe americano", "caffè latte", "caffe latte",
                "café gelado", "cafe gelado", "bebida de café", "bebida de cafe"
        );
    }

    private static boolean looksLikeSparklingWater(String name) {
        if (hasSolidSnackDessertKeyword(name)) return false;

        return containsAny(name,
                // English
                "sparkling water", "soda water", "carbonated water",

                // Traditional / Simplified Chinese
                "氣泡水", "气泡水", "蘇打水", "苏打水",

                // Japanese / Korean
                "炭酸水", "탄산수",

                // EU languages
                "agua con gas", "agua carbonatada",
                "eau gazeuse",
                "sprudelwasser",
                "acqua frizzante", "acqua gassata",
                "água com gás", "agua com gas"
        );
    }

    private static boolean looksLikePlainWater(String name) {
        if (hasSolidSnackDessertKeyword(name)) return false;
        if (looksLikeSparklingWater(name)) return false;

        return containsAny(name,
                // English
                "mineral water", "drinking water", "purified water", "spring water", "bottled water",

                // Traditional / Simplified Chinese
                "礦泉水", "矿泉水", "純水", "纯水", "飲用水", "饮用水", "天然水",

                // Japanese / Korean
                "ミネラルウォーター", "天然水", "飲料水",
                "생수", "광천수", "먹는샘물",

                // EU languages
                "agua mineral", "agua purificada", "agua potable",
                "eau minérale", "eau minerale", "eau purifiée", "eau purifiee",
                "mineralwasser", "trinkwasser",
                "acqua minerale", "acqua naturale",
                "água mineral", "agua mineral", "água purificada", "agua purificada"
        );
    }

    private static boolean looksLikeBroth(String name) {
        if (hasSoupBasePowderKeyword(name)) return false;

        return containsAny(name,
                // English
                "clear broth", "clear soup", "broth", "consommé", "consomme", "bouillon",

                // Traditional / Simplified Chinese
                "清湯", "清汤", "高湯", "高汤", "高湯底", "高汤底",

                // Japanese / Korean
                "コンソメ", "ブロス", "맑은 국물", "육수",

                // EU languages
                "caldo claro", "consomé", "consome",
                "bouillon",
                "brühe", "bruehe", "klare suppe",
                "brodo",
                "caldo"
        );
    }

    /**
     * 避免：
     * - coffee candy
     * - green tea mochi
     * - latte cookie
     * - jasmine tea jelly
     */
    private static boolean hasSolidSnackDessertKeyword(String name) {
        return containsAny(name,
                // English
                "candy", "cookie", "cookies", "biscuit", "biscuits",
                "cake", "cakes", "mochi", "jelly", "gummy",
                "cracker", "crackers", "chips", "bar", "bars",

                // Traditional / Simplified Chinese
                "糖果", "餅乾", "饼干", "蛋糕", "麻糬", "果凍", "果冻",
                "餅", "饼", "脆片", "棒",

                // Japanese
                "キャンディ", "クッキー", "ケーキ", "もち", "ゼリー", "バー",

                // Korean
                "캔디", "쿠키", "케이크", "젤리", "바"
        );
    }

    /**
     * 避免：
     * - bouillon cube
     * - soup base powder
     * - 高湯粉
     * 這些比較像湯底/調味，不要誤分類成可直接喝的 broth。
     */
    private static boolean hasSoupBasePowderKeyword(String name) {
        return containsAny(name,
                // English
                "stock cube", "bouillon cube", "soup base", "broth concentrate",
                "seasoning", "powder", "instant soup mix",

                // Traditional / Simplified Chinese
                "湯塊", "汤块", "高湯粉", "高汤粉", "濃湯粉", "浓汤粉",
                "湯底粉", "汤底粉", "調味粉", "调味粉",

                // Japanese / Korean
                "スープの素", "キューブ", "육수 분말", "스프 분말"
        );
    }

    private static boolean hasAnyTag(List<String> tags, String... expected) {
        if (tags == null || tags.isEmpty()) return false;

        for (String tag : tags) {
            if (tag == null || tag.isBlank()) continue;

            String normalizedTag = tag.trim().toLowerCase(Locale.ROOT);
            for (String e : expected) {
                if (e == null || e.isBlank()) continue;
                String expectedTag = e.trim().toLowerCase(Locale.ROOT);

                if (normalizedTag.equals(expectedTag) || normalizedTag.contains(expectedTag)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String text, String... candidates) {
        if (text == null || text.isBlank()) return false;

        for (String c : candidates) {
            if (c != null && !c.isBlank() && text.contains(c.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}