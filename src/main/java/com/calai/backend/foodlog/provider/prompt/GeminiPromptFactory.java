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
            You are a specialized OCR extraction engine for PRINTED nutrition tables in any language
            (English, Traditional Chinese, Simplified Chinese, Japanese, Korean, etc.).
            Return ONLY ONE MINIFIED JSON object. No markdown. No extra text.
            
            PRIMARY GOAL
            Extract the visible nutrition numbers from the clearest nutrition table in the provided images.
            Do NOT estimate or infer whole-package totals unless explicit package-total text is visible.
            
            STRICT BASIS SELECTION ORDER
            1. WHOLE CONTAINER is allowed ONLY when explicit package-total text is visible, such as:
               - EN: "Net Weight", "Net Vol", "Contents", "Servings Per Container"
               - TW/CN: "淨重", "內容量", "本包裝含", "每包裝含"
               - JP: "内容量", "1包装", "1袋"
               - KR: "총 내용량", "1봉지당"
               - Similar logic applies to other languages.
            2. If explicit package-total text is NOT visible, then:
               - Prefer a clearly visible "per 100 g" / "per 100g" / "per 100 ml" / "per 100ml" column.
               - If no per-100 column exists, then use a clearly visible "per serving" column.
            3. Never infer total package size, multiplier, serving count, or total nutrition from:
               - hidden or cropped text
               - column ratios
               - package shape
               - brand knowledge
               - typical market size
               - nearby unrelated numbers
            
            IMPORTANT NON-TOTAL RULE
            - If explicit package-total text is NOT found, DO NOT calculate whole-container totals.
            - Use the chosen visible column values directly, unchanged.
            - In this non-total case:
              - quantity.value MUST be 1.0
              - quantity.unit MUST be "SERVING"
            - This rule applies even when the chosen visible column is "per 100 g" or "per 100 ml".
            
            VISUAL ROBUSTNESS RULES
            1. Prefer the clearest / most zoomed nutrition-table image.
            2. Ignore phone UI chrome, app headers, colored bounding boxes, arrows, redaction bars, and surrounding text.
            3. Split-table rule:
               If the nutrition table continues to the right, rows such as Carbohydrates / Sugars / Fibre / Sodium
               belong to the SAME chosen header column and must be merged correctly.
            4. If product name is not clearly readable from the package, return "Unknown Name".
            
            NUTRIENT EXTRACTION RULES
            Extract these exact nutrients:
            - kcal
            - protein
            - fat
            - carbs
            - fiber
            - sugar
            - sodium
            
            DATA CONVERSIONS
            - If kcal is explicitly printed, use kcal directly.
            - If kcal is missing but kJ is printed, convert kcal = kJ / 4.184.
            - If sodium is missing but salt is printed, convert sodium_mg = salt_g * 393.4.
            - Normalize units as:
              - kcal
              - protein g
              - fat g
              - carbs g
              - fiber g
              - sugar g
              - sodium mg
            
            ZERO RULE
            - Output 0 only if the label explicitly shows 0 / 0.0 / zero.
            - Do NOT invent 0 because of blur or occlusion.
            
            SANITY CHECKS
            - fiber <= carbs
            - sugar <= carbs
            
            CONFIDENCE / WARNINGS
            - If any target nutrient row is partially blocked, cut off, or unreadable, set confidence < 0.5
              and include "LABEL_PARTIAL" in warnings.
            - If all target nutrient rows are readable, confidence >= 0.85.
            
            HEALTH SCORE
            - Score ONLY on the chosen visible basis.
            - Do NOT rescale to whole-container unless explicit package-total text is visible.
            
            REQUIRED JSON FORMAT:
            {
              "foodName": "string|null",
              "quantity": { "value": 1.0, "unit": "PACK|BOTTLE|CAN|PIECE|SERVING" },
              "nutrients": {
                "kcal": number,
                "protein": number,
                "fat": number,
                "carbs": number,
                "fiber": number,
                "sugar": number,
                "sodium": number
              },
              "_reasoning": "Chosen basis + whether explicit package-total text was found + whether math was applied",
              "confidence": number,
              "healthScore": number,
              "warnings": string[],
              "labelMeta": {
                "servingsPerContainer": number|null,
                "basis": %s
              }
            }
            
            OUTPUT RULES
            - warnings must use only: %s
            - quantity.unit must use only: %s
            - If explicit package-total text is NOT found and a per-100 column is chosen,
              then quantity.unit MUST still be "SERVING", and nutrients MUST remain the printed per-100 values unchanged.
            """.formatted(ALLOWED_BASES, ALLOWED_WARNINGS, ALLOWED_UNITS);

    public String mainPrompt(boolean isLabel) {
        return isLabel ? USER_PROMPT_LABEL_MAIN : USER_PROMPT_MAIN;
    }
}
