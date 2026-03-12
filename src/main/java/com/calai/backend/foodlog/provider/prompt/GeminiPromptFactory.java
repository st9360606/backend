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
            - For alcoholic beverages, estimate calories based on standard ABV if nutrition facts are missing.

            FOOD NAME RULES:
            - Do NOT force translation of foodName. Keep it concise (max 100 chars).
            - For packaged/branded foods, preserve the original visible product name. Do NOT translate brands.

            FOCUS RULE:
            - Analyze ONLY ONE dominant edible subject. Ignore background clutter.
            - If food is partially consumed, estimate only the REMAINING portion.
            - If multiple items exist, focus on the main dish and include "MIXED_MEAL" in warnings.

            HEALTH EVALUATION (1-10):
            - Assign a healthScore (1-10): 9-10 (Whole foods), 5-8 (Balanced), 1-4 (Highly processed/Junk).

            ESTIMATION LOGIC (CRITICAL):
            1. CLASSIFY FIRST:
               First classify the subject into exactly one type:
               (A) BRANDED_PACKAGED_PRODUCT
               (B) BOTTLED_OR_CANNED_BEVERAGE
               (C) COOKED_MEAL_OR_SINGLE_ITEM
            
            2. RELIABLE NUMERIC ANCHORS ONLY:
               For (A) and (B), calculate whole-package / whole-container nutrition ONLY when reliable numeric anchors are visually readable, such as:
               - net weight or net volume,
               - nutrition facts per 100g / 100ml,
               - nutrition facts per serving + servings per container,
               - nutrition facts per X g / X ml / X units.
            
            3. DO NOT INVENT PACKAGE SIZE:
               Do NOT infer total net weight, total volume, total servings, or total unit count from brand familiarity, standard retail sizes, package appearance, or approximate dimensions alone.
            
            4. CALCULATION CHAIN:
               Determine the reference basis first -> determine the total quantity second -> then calculate total nutrients.
               Allowed order:
               - per 100g + total grams
               - per 100ml + total ml
               - per serving + total servings
               - per X g/ml/units + total quantity
            
            5. MATH RULES:
               - Total Nutrients = (Value_per_100g * Total_Weight_g / 100)
               - Total Nutrients = (Value_per_100ml * Total_Volume_ml / 100)
               - Total Nutrients = (Value_per_serving * Total_Servings)
               - Total Nutrients = (Value_per_X * Total_Quantity / X)
            
            6. UNIT-SCALE RULE:
               If nutrition is given per X pack / bag / piece / unit, treat it as:
               Total Nutrients = (Value_per_X_units * Total_Units / X)
            
            7. WHEN DATA IS INSUFFICIENT:
               If the product name is visible but reliable numeric anchors are missing or unreadable:
               - preserve the visible product name,
               - add "MISSING_NUTRITION_FACTS" if nutrition numbers are not readable,
               - add "PACKAGE_SIZE_UNVERIFIED" if total size or quantity is not readable,
               - do NOT upscale uncertain estimates into exact whole-package totals.
            
            8. VISUAL ESTIMATION BOUNDARY (STATE-AWARE):
               - ESTIMATE BY PREPARATION: Always evaluate food based on its visual state, not its raw ingredients. If food appears roasted, fried, seasoned, or glazed, you MUST include estimated hidden calories, sodium, and sugars from standard culinary fats, salts, and syrups/glazes.
               - GLAZE & SAUCE RECOGNITION (Universal Sugar Logic): If the food surface shows a "Glossy," "Sticky," or "Caramelized" sheen (e.g., BBQ, Teriyaki, Glazed meats), you MUST incorporate a "Sugar Offset" (minimum 2.0-8.0g per serving) even if the base ingredient is a protein.
               - CATEGORY-SPECIFIC OVERRIDE: If the dish is identified as "Sweet and Sour" (糖醋) or "Honey-Glazed" (蜜汁), increase the Sugar Offset minimum to 15.0g.
               - CULINARY PENALTY: For any cooked protein (Type C), incorporate a "Culinary Penalty" for sodium (minimum 400-800mg per serving) and fats/oils inherent to that cooking style (e.g., frying oil, basting butter).
               - MULTI-METHOD LOGIC: If multiple preparation methods are visible (e.g., fried then glazed), apply the stricter (higher) nutrient penalty for all categories.
               - WHOLE FOODS / RAW: Use "NATURAL-BASE" profiles ONLY if the item is clearly raw or unprocessed (e.g., a whole fruit, raw cucumber).
               - EDIBLE PORTION: Calculate weight for EDIBLE PORTION ONLY (e.g., for Roast Duck or Steak, exclude bone weight but include skin and rendered fat).
            
            9. SKEPTICISM:
               Be skeptical of marketing slogans and front-of-package claims. Prefer printed nutrition tables and explicit numeric quantities over promotional text.
            
            10. SODIUM MATH:
               If only "Salt" (g) is visible, calculate Sodium (mg) = Salt_g * 393.4.
               If "Sodium" is explicitly printed in mg, use the printed sodium value directly.
            
            11. OUTPUT DISCIPLINE:
               Round all nutrient values to ONE decimal place.
            
            12. Provide your brief thought process in "_reasoning" (Max 20 words).

            CORE LOGIC:
            1. PACKAGED FOOD (Bags/Boxes):
               - ALWAYS calculate for the ENTIRE package content.
               - Set quantity: {"value": 1.0, "unit": "PACK"}.
               - Set labelMeta: {"servingsPerContainer": 1.0, "basis": "WHOLE_PACKAGE"}.
            2. BOTTLED/CANNED BEVERAGE:
               - ALWAYS calculate for the ENTIRE bottle/can.
               - Set quantity: {"value": 1.0, "unit": "BOTTLE" or "CAN"}.
               - Set labelMeta: {"servingsPerContainer": 1.0, "basis": "WHOLE_PACKAGE"}.
            3. COOKED MEAL / LOOSE ITEMS:
               - For single dishes (e.g. Steak): Use unit "SERVING", basis "ESTIMATED_PORTION".
               - For countable items (e.g. 3 Cookies): Use unit "PIECE", value 3.0, basis "ESTIMATED_PORTION".

            NUTRIENT HANDLING:
            - Default missing nutrients to 0.0. Do NOT use null.
            - Logic Check: Sugar <= Carbs, Fiber <= Carbs. Protein + Fat + Carbs <= Total Weight.

            WARNING CODE RULES:
            [%s]

            REQUIRED JSON FORMAT:
            {
              "foodName": "string|null",
              "quantity": { "value": number, "unit": "%s" },
              "nutrients": { "kcal": number, "protein": number, "fat": number, "carbs": number, "fiber": number, "sugar": number, "sodium": number },
              "_reasoning": "string",
              "confidence": number,
              "healthScore": number,
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
