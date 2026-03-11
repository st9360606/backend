package com.calai.backend.foodlog.provider.prompt;

import com.calai.backend.foodlog.unit.FoodLogWarning;
import com.calai.backend.foodlog.unit.NutritionBasis;
import com.calai.backend.foodlog.unit.QuantityUnit;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
public final class GeminiPromptFactory {

    private static final String ALLOWED_WARNINGS = FoodLogWarning.promptCsv();
    private static final String ALLOWED_UNITS = String.join("|", QuantityUnit.promptValues());
    private static final String ALLOWED_BASES = NutritionBasis.promptValues().stream()
            .map(v -> "\"" + v + "\"")
            .collect(Collectors.joining(" | "));

    private static final String USER_PROMPT_MAIN = """
            You are a highly advanced multilingual food nutrition extraction engine.
            Analyze the image and return ONLY ONE minified JSON object.
            No markdown. No extra text. No explanations. No extra keys.
            
            GLOBAL ADAPTABILITY:
            - Handle international packaging. Convert kJ to kcal (kJ / 4.184).
            
            FOOD NAME RULES:
            - Do NOT force translation of foodName.
            - For packaged/branded foods, preserve the original visible product name from the package whenever possible.
            - Do NOT translate brand names.
            - Do NOT mix original product text with your own translated category words.
            - Do NOT append guessed generic category words such as cookies, biscuit, snack, chocolate, drink, beverage, candy, cake, noodle, bread, chips, 餅乾, 巧克力, 飲料, 糖果, 蛋糕, 麵, 麵包, 洋芋片 unless they are clearly visible on the package.
            - For non-packaged cooked meals, you may return a short descriptive food name in the most natural language supported by the image context.
            
            FOCUS RULE:
            - Analyze ONLY ONE dominant edible subject (centered or most prominent).
            - If multiple items exist, focus on the main dish and include "MIXED_MEAL" in warnings.
            
            HEALTH EVALUATION (1-10):
            - Assign a healthScore from 1 to 10 based on visual composition and estimated macros.
            - 9-10: Whole foods, fresh vegetables, lean proteins, unprocessed.
            - 5-8: Balanced cooked meals, moderate carbs/fats.
            - 1-4: Highly processed, deep-fried, high sugar, or high sodium junk food.
            
            CORE LOGIC:
            1. PACKAGED FOOD: Provide nutrition for the WHOLE PACKAGE.
               - Set quantity: {"value": 1.0, "unit": "PACK"}. Basis: "WHOLE_PACKAGE".
            2. BOTTLED/CANNED BEVERAGE: Provide nutrition for the ENTIRE BOTTLE/CAN.
               - Set quantity: {"value": 1.0, "unit": "BOTTLE" | "CAN"}. Basis: "WHOLE_PACKAGE".
            3. COOKED MEAL / SINGLE ITEMS: Estimate portion size or count.
               - For countable items (e.g. nuggets, fruit), use "PIECE" and the count.
               - For dishes, use "SERVING".
               - Set labelMeta.basis: "ESTIMATED_PORTION".
            
            NUTRIENT HANDLING:
            - Default missing or invisible nutrients to 0.0. Do NOT use null.
            - Logic Check: Sugar <= Carbs, Fiber <= Carbs. Protein + Fat + Carbs <= Total Weight.
            
            WARNING CODE RULES:
            [%s]
            
            REQUIRED JSON FORMAT:
            {
              "foodName": "string|null",
              "quantity": { "value": number, "unit": "%s" },
              "nutrients": { "kcal": number, "protein": number, "fat": number, "carbs": number, "fiber": number, "sugar": number, "sodium": number },
              "confidence": number, (Range: 0.0 to 1.0)
              "healthScore": number, (Range: 1 to 10)
              "warnings": string[],
              "labelMeta": { "servingsPerContainer": number|null, "basis": %s }
            }
            - If "NO_FOOD_DETECTED" is triggered, set foodName to null, nutrients to 0.0, healthScore to 0, and confidence to 0.0.
            """.formatted(ALLOWED_WARNINGS, ALLOWED_UNITS, ALLOWED_BASES);

    private static final String USER_PROMPT_LABEL_MAIN = """
            You are a specialized engine for extracting Nutrition Facts from PRINTED nutrition tables in any language (Japanese, Korean, English, Chinese, etc.).
            Return ONLY ONE MINIFIED JSON object. No markdown. No extra text.
            
            CORE LOGIC: CALCULATE FOR THE WHOLE CONTAINER
            1. Your primary goal is to return nutrition for the ENTIRE container (Whole Package/Bottle/Can).
            2. CALCULATION:
               - If "Per 100g/ml": Search for total net weight/volume on the package. Total = (Values_per_100 * Total_Weight / 100).
               - If "Per Serving": Search for "Servings Per Container". Total = (Values_per_serving * Total_Servings).
            3. FALLBACK: If total weight/servings are truly missing, return nutrition for exactly ONE serving and set basis to "PER_SERVING".
            
            HEALTH EVALUATION (1-10):
            - Assign a healthScore from 1 to 10 based ONLY on the extracted macro profile per 100g/ml or per serving.
            - 9-10: Excellent profile (High protein/fiber, very low sugar/sodium, no trans fat).
            - 5-8: Average profile (Moderate macros, typical daily consumption).
            - 1-4: Poor profile (High added sugar, high sodium, high saturated/trans fats).
            
            DATA CONVERSIONS:
            - Energy: kJ to kcal (kcal = kJ / 4.184).
            - Sodium: If ONLY "Salt" is provided, calculate Sodium (mg) = (salt_g * 393.4). If "Sodium (mg)" is explicitly printed, use that value directly.
            - Default: 0.0 for any missing nutrient. Do NOT use null.
            - Names: Preserve the original visible product name from the package whenever possible. Do NOT force translation. Do NOT translate brand names. No weights/units in foodName.
            
            QUANTITY & UNIT RULES:
            - "value": Always 1.0 (representing the whole entity).
            - "unit": MUST be strictly one of [%s]. No "g", "ml", "oz".
              - Use BOTTLE/CAN for beverages.
              - Use PACK for bags/boxes.
              - Use SERVING only if container type is unknown.
            
            SANITY CHECKS:
            - Fiber <= Carbs. Sugar <= Carbs. Confidence should be low if the image is blurry or numbers are unreadable.
            
            REQUIRED JSON FORMAT:
            {
              "foodName": "string",
              "quantity": { "value": 1.0, "unit": "PACK|BOTTLE|CAN|PIECE|SERVING" },
              "nutrients": { "kcal": number, "protein": number, "fat": number, "carbs": number, "fiber": number, "sugar": number, "sodium": number },
              "confidence": number, (Range: 0.0 to 1.0)
              "healthScore": number, (Range: 1 to 10)
              "warnings": string[],
              "labelMeta": { "servingsPerContainer": number|null, "basis": %s }
            }
            - "warnings": Use strings from this set: [%s].
            """.formatted(ALLOWED_UNITS, ALLOWED_BASES, ALLOWED_WARNINGS);

    public String mainPrompt(boolean isLabel) {
        return isLabel ? USER_PROMPT_LABEL_MAIN : USER_PROMPT_MAIN;
    }
}
