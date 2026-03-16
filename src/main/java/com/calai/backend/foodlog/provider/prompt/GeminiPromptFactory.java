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
            - Evaluate based on "Nutrient Density" vs "Empty Calories".
            - GREEN FLAGS (Score +): High protein, high dietary fiber, low sugar-to-carb ratio, contains healthy fats.
            - RED FLAGS (Score -): Trans fats > 0, excessive sodium (>800mg per serving), high added sugar (>20g per serving or sugar being the primary carb source).
            - SCORING GUIDE:
              * 9-10 (Nutrient Dense): High protein/fiber with minimal processing markers. Essential "superfoods" (e.g., Natto, plain nuts).
              * 7-8 (Good/Balanced): Solid macro profile, moderate sodium, manageable sugar. Typical of healthy meals.
              * 4-6 (Moderate/Processed): High energy but low "quality" nutrients (fiber/protein). Many common snacks or instant meals fall here.
              * 1-3 (Empty Calories): High in "Red Flags". Pure sugar, high sodium, or trans fats with near-zero fiber/protein (e.g., Soda, candy, some ultra-processed pastries).
            - BEVERAGE RULE: Be stricter with liquid sugar; beverages with >10g sugar per 100ml should rarely score above 5.
            
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

//    private static final String USER_PROMPT_LABEL_MAIN = """
//            You are a specialized OCR extraction engine for PRINTED nutrition tables in any language
//            (English, Traditional Chinese, Simplified Chinese, Japanese, Korean, Spanish, French, German,
//            Portuguese, Italian, Dutch, Russian, Arabic, Hebrew, Thai, Vietnamese, Malay, Filipino, Hindi, etc.).
//            Return ONLY ONE MINIFIED JSON object. No markdown. No extra text.
//
//            PRIMARY GOAL
//            Extract the visible nutrition numbers from the clearest nutrition table in the provided images.
//            Use translation only as an internal aid to understand local-language headers and nutrient names.
//            Do NOT translate the whole table first.
//            Preserve the printed row and column structure exactly as seen.
//            Do NOT estimate or infer whole-package totals unless explicit package-total text is visible.
//
//            STRICT BASIS SELECTION ORDER
//            1. WHOLE CONTAINER calculation is MANDATORY when explicit package-total text is visible.
//               Explicit package-total text includes either:
//               - explicit total weight or volume (e.g. "1250 ml", "Net Weight 200 g"), or
//               - explicit serving size plus servings per container (e.g. "每一份量 125 毫升", "本包裝含 10 份", "Serving size 45g", "Servings per package 10").
//
//            2. BASIS PRIORITY when explicit package-total text is visible:
//               - If BOTH a "per serving" column AND an explicit servings-per-container value are visible,
//                 PRIMARY SOURCE MUST be the "per serving" column.
//                 Final nutrients MUST be calculated as:
//                 total_nutrient = per_serving_value * servings_per_container
//               - Else if a "per 100 g" or "per 100 ml" column AND an explicit total weight or volume are visible,
//                 PRIMARY SOURCE MUST be the "per 100 g" or "per 100 ml" column.
//                 Final nutrients MUST be calculated as:
//                 total_nutrient = per_100_value * total_quantity / 100
//               - If both calculation paths are available, prefer the PER_SERVING path for the final answer
//                 and use the PER_100 column only as a cross-check.
//
//            3. ROW-LEVEL RECOVERY is allowed ONLY when explicit package-total text is visible:
//               - If one target nutrient row in the primary source column is blurry, curved, compressed,
//                 partially cut off, or visually weaker than other rows, recover that row from the secondary column
//                 using explicit package math.
//               - Never discard a visible non-zero row just because adjacent rows are zero.
//
//            4. If explicit package-total text is NOT visible, then:
//               - Prefer a clearly visible "per 100 g" or "per 100 ml" column.
//               - If no per-100 column exists, then use a clearly visible "per serving" column.
//
//            5. Never infer total package size, multiplier, serving count, or total nutrition from:
//               - hidden or cropped text
//               - package shape
//               - brand knowledge
//               - typical market size
//               - nearby unrelated numbers
//
//            6. IMPORTANT:
//               - Cross-column math is FORBIDDEN unless explicit package-total text is visible.
//               - When explicit package-total text is visible, cross-column math is REQUIRED for row-level recovery.
//
//            IMPORTANT NON-TOTAL RULE
//            - If explicit package-total text is NOT found, DO NOT calculate whole-container totals.
//            - Use the chosen visible column values directly, unchanged.
//            - In this non-total case:
//              - quantity.value MUST be 1.0
//              - quantity.unit MUST be "SERVING"
//            - This rule applies even when the chosen visible column is "per 100 g" or "per 100 ml".
//
//            VISUAL ROBUSTNESS RULES
//            1. Prefer the clearest and most zoomed nutrition-table image.
//
//            2. Ignore phone UI chrome, app headers, colored bounding boxes, arrows, reflections,
//               transparent bottle background, redaction bars, and surrounding marketing text.
//
//            3. Read the table ROW BY ROW, not by overall pattern.
//               For each target nutrient, independently locate:
//               nutrient label -> same horizontal row -> selected basis column value -> unit.
//               Do NOT assume target nutrients appear in one uninterrupted consecutive sequence.
//               Non-target rows may appear between target rows, such as gluten, cholesterol, vitamins,
//               minerals, caffeine, amino acids, additives, or other extra nutrients.
//               These non-target rows MUST be skipped without shifting the mapping of later target rows.
//
//            4. BOTTOM-ROW RULE:
//               The last printed nutrient row is often the most distorted by bottle curvature, transparent base,
//               can rim, label seam, glare, or perspective compression.
//               You MUST inspect the bottom-most nutrient row separately and MUST NOT skip it.
//
//            5. CURVED SURFACE ALIGNMENT:
//               Nutrition tables on bottles or cans are often curved at the edges or bottom.
//               Carefully trace the horizontal line from the nutrient label
//               (e.g. Sodium / 鈉 / Natrium / Sodio / salt-equivalent row)
//               to its exact value, even if the text baseline is warped, skewed, or compressed.
//
//            6. FULL-ROW CROSS-COLUMN RECONCILIATION:
//               If the table has multiple columns (e.g. "per serving" and "per 100 g/ml"), you MUST reconcile ALL target nutrients
//               across both visible columns before final output whenever whole-container calculation is active.
//
//               Reconciliation rules:
//               - A target nutrient may be output as 0 ONLY if the exact target row supports zero in the chosen basis,
//                 and there is no visible numeric non-zero evidence for that same target nutrient in any other visible basis column.
//               - If one visible basis column shows a numeric non-zero value for a target nutrient, and the other column is blurry,
//                 missing, conflicting, or textually noisy, preserve the numeric non-zero value and compute the final result from the preferred basis.
//               - Never let an inferred zero override visible numeric evidence.
//               - Prefer the interpretation that preserves the largest number of visible target-row numeric values.
//
//            7. NON-UNIFORM ROW RULE:
//               Different rows may have different readability.
//               Never assume one row has the same value pattern as the rows above or below it.
//
//            8. If product name is not clearly readable from the package, return "Nutrition facts label".
//               Do not append weight, volume, serving count, or nutrition values to the foodName.
//
//            NUTRIENT EXTRACTION RULES
//            Extract ONLY these target nutrients:
//            - kcal
//            - protein
//            - fat
//            - carbs
//            - fiber
//            - sugar
//            - sodium
//
//            ROW LABEL MATCHING RULE
//            - Match each target nutrient by its own printed row label and its own value cell.
//            - Do NOT infer a target nutrient from nearby rows.
//            - Do NOT assume the target rows are consecutive.
//
//            TARGET ROW DEFINITIONS
//            - kcal = energy in kcal only. If kcal is missing, derive from kJ.
//            - protein = protein row only.
//            - fat = total fat row only. Do NOT use saturated fat or trans fat as total fat.
//            - carbs = total carbohydrate row only. Do NOT use sugars as total carbs.
//            - fiber = dietary fibre row only.
//            - sugar = sugars row only.
//            - sodium = sodium row only. If sodium is missing but salt is present, convert salt to sodium.
//
//            NON-TARGET ROW SKIP RULE
//            - Nutrition tables may contain non-target rows between target rows, such as:
//              gluten, cholesterol, vitamins, minerals, calcium, iron, caffeine, additives,
//              amino acids, saturated fat, trans fat, or other extra nutrients not requested by the schema.
//            - These rows are NOT target nutrients and MUST be ignored for output.
//            - Skipping a non-target row MUST NOT shift or overwrite the mapping of later target nutrients.
//
//            TEXTUAL VALUE SCOPING RULE
//            - Textual values such as "not detected", "none detected", "nil", "trace", "N/D", "ND",
//              "less than", "<", or similar apply ONLY to the exact row where they are printed.
//            - A textual value on one row MUST NOT propagate upward or downward to neighboring rows.
//            - A textual value on a non-target row MUST NOT zero-out or replace numeric values on later target rows.
//
//            WHOLE-CONTAINER MATH RULE
//            - When whole-container calculation is active, apply math independently to each target nutrient row.
//            - Multiply only the numeric value from that exact target row.
//            - Non-target rows do not participate in math and do not affect neighboring target rows.
//
//            DATA CONVERSIONS
//            - If kcal is explicitly printed, use kcal directly.
//            - If kcal is missing but kJ is printed, convert kcal = kJ / 4.184.
//            - If sodium is missing but salt is explicitly printed, convert sodium_mg = salt_g * 393.4.
//            - Accept multilingual unit variants such as:
//              "公克" = g, "克" = g, "그램" = g, "г" = g, "毫克" = mg, "mg" = mg, "大卡" = kcal.
//            - Accept decimal comma as decimal point when needed.
//            - Normalize units as:
//              - kcal
//              - protein g
//              - fat g
//              - carbs g
//              - fiber g
//              - sugar g
//              - sodium mg
//
//            ZERO RULE
//            - Output 0 only if the selected-basis value for that EXACT target nutrient row
//              is explicitly printed as 0, 0.0, zero, none, nil, not detected, or an equivalent textual-zero marker,
//              AND there is no visible numeric non-zero evidence for that same target nutrient in any other visible basis column.
//
//            - Do NOT output 0 because neighboring rows are zero.
//            - Do NOT output 0 because the product looks healthy, low-calorie, sugar-free, or low-sodium.
//            - Do NOT output 0 because a non-target row contains a textual value such as "not detected" or "trace".
//            - Do NOT output 0 as a fallback when later rows are harder to read than earlier rows.
//
//            TEXTUAL-RESULT SAFETY RULE
//            - If a TARGET nutrient row itself explicitly says "not detected", "none detected", "nil", "trace",
//              "N/D", or similar, treat that target row as zero for that target nutrient only.
//            - If a NON-TARGET row says "not detected", "none detected", "nil", "trace", "N/D", or similar,
//              that text applies ONLY to that non-target row and MUST NOT affect any target nutrient row.
//
//            NON-ZERO PRESERVATION RULE
//            - If ANY visible basis column shows a numeric non-zero value for a target nutrient,
//              the final answer for that target nutrient MUST remain non-zero.
//            - When explicit package-total text is visible, preserve that numeric row and apply the required whole-container math.
//            - Never replace a visible numeric non-zero value with 0 because of row-order confusion,
//              nearby textual rows, or pattern-matching from other rows.
//
//            LATE-ROW STABILITY RULE
//            - Later target rows such as carbs, sugar, fiber, and sodium are often more vulnerable to collapse than earlier rows.
//            - If earlier rows parse successfully but later rows collapse to 0 while visible numeric evidence exists,
//              keep the visible non-zero later-row values and do NOT emit a zero-collapse interpretation.
//
//            UNREADABLE-ROW RULE
//            - If a target nutrient row is unreadable and no visible numeric evidence exists in any basis column,
//              lower confidence and add warnings instead of inventing 0.
//            - Do NOT borrow a textual value from an adjacent row.
//
//            CONFLICT RESOLUTION RULE
//            - If multiple candidate parses are possible, choose the parse that:
//              1. preserves the greatest number of visible target-row numeric values,
//              2. preserves visible non-zero evidence,
//              3. remains consistent across "per serving", "per 100 g/ml", and whole-container math,
//              4. does not collapse later target rows to zero when those later rows visibly contain numeric values.
//
//            - Prefer a numerically consistent parse over a conservative zero-filled parse.
//            - Do NOT emit "MACRO_OUTLIER" when a more directly supported visible numeric parse exists.
//            - Warning generation must not replace or override visible row-level numeric evidence.
//
//            SANITY CHECKS
//            - fiber <= carbs when both are numeric.
//            - sugar <= carbs when both are numeric.
//            - Whole-container results must still preserve per-row math consistency.
//
//            CONFIDENCE AND WARNINGS
//            - If any target nutrient row is partially blocked, cut off, warped, curved, or unreadable,
//              set confidence below 0.5 and include "LABEL_PARTIAL" in warnings.
//            - If confidence is low, also include an appropriate low-confidence warning from the allowed warning set.
//            - If all target nutrient rows are readable, set confidence at or above 0.85.
//
//            HEALTH SCORE
//            - Score ONLY on the chosen visible basis before whole-container rescaling.
//            - Do NOT use marketing claims to influence the score.
//
//            REQUIRED JSON FORMAT:
//            {
//              "foodName": "string|null",
//              "quantity": { "value": number, "unit": "PACK|BOTTLE|CAN|PIECE|SERVING" },
//              "nutrients": {
//                "kcal": number,
//                "protein": number,
//                "fat": number,
//                "carbs": number,
//                "fiber": number,
//                "sugar": number,
//                "sodium": number
//              },
//              "_reasoning": "Chosen basis + whether explicit package-total text was found + whether math was applied",
//              "confidence": number,
//              "healthScore": number,
//              "warnings": string[],
//              "labelMeta": {
//                "servingsPerContainer": number|null,
//                "basis": %s
//              }
//            }
//
//            OUTPUT RULES
//            - warnings must use only: %s
//            - quantity.unit must use only: %s
//            - If explicit package-total text is NOT found and a per-100 column is chosen,
//              then quantity.unit MUST still be "SERVING", and nutrients MUST remain the printed per-100 values unchanged.
//            - If whole-container calculation is active, choose quantity.unit by visible container type:
//              PACK for bag, box, pouch, carton, or package;
//              BOTTLE for bottle;
//              CAN for can;
//              PIECE for a single discrete item;
//              SERVING only if the container type is unclear.
//            """.formatted(ALLOWED_BASES, ALLOWED_WARNINGS, ALLOWED_UNITS);

//    private static final String USER_PROMPT_LABEL_MAIN = """
//            You are an object dedicated to identifying nutrition labels on food packaging from various countries around the world and calculating the nutritional value for the entire serving size.
//            Return only a concise JSON object. No Markdown or extra text.
//
//            PRIMARY GOAL:
//            Calculate the total nutritional content for the entire package.
//            If the label provides "Per Serving" and "Servings per Package", multiply them ONCE to get the total.
//            If "Per 100g/ml" is provided, multiply by (Total Net Weight / 100).
//            If the total net weight or servings per package is NOT visible anywhere in the image, calculate the nutrients based on the visible reference unit (e.g., per 100g or per 1 serving), set 'value' to 1.0, and explain this assumption in '_reasoning'.
//
//            VISUAL STRATEGY:
//            1. MULTI-LANGUAGE KEYWORDS: Match labels in any language (e.g., '碳水化合物'/'炭水化物'=carbs, '糖'/'糖類'/'糖分'=sugar, '膳食纖維'=fiber, '鈉'/'ナトリウム'=sodium, '食塩相当量'=salt_equivalent).
//            2. HIERARCHY & NESTED ITEMS: Tables often have indented sub-items (e.g., 'Saturated Fat' under 'Fat'). These are frequently prefixed with structural symbols like dashes, dots, or bullets (e.g., '-', 'ー', '・', '>'). You MUST treat these prefixes strictly as indentation markers. NEVER interpret a leading dash as a negative sign or a reason to skip the row. Continue scanning until you reach the absolute end of the macro-nutrient list (e.g., 'Sodium', 'Salt', or '食塩相当量').
//            3. INCLUDED CONTENTS PRIORITY: If a row presents multiple values (e.g., "0.6g (0.0g)"), intelligently select the value representing the product WITH all its INCLUDED sauces/seasoning packets.\s
//               - DO select the total value of the package contents.
//               - DO NOT select "As Prepared" values that include external ingredients not sold in the package (e.g., added milk, eggs, or oil).
//               - Use the table's header context (e.g., "たれ付" meaning with sauce, or "( )" meaning without sauce) to determine the correct value.
//            4. SINGLE PATH CALCULATION: Choose ONLY ONE base column (prefer "Per Serving"). Multiply by the serving count exactly ONCE. Do NOT double-multiply.
//            5. SKIP INTERRUPTIONS: Ignore non-target rows (Gluten, Vitamins, or marketing text) without stopping the scan. Ensure 'sodium' is captured even if it is at the very bottom or slightly distorted.
//            6. TYPOGRAPHICAL NOISE: Ignore all decorative characters used for alignment, such as leader dots (....) or decorative separators. Crucially, disregard internal whitespace used for text justification. (e.g., '糖  質' must be read as '糖質', and '- of which  sugars' as 'sugars'). If a word is split by symbols or spaces to fit a column, recombine it into the standard nutrient name before matching.
//            7. INEQUALITY HANDLING (Upper Bound Principle): > If a nutrient value contains inequality symbols such as '<' (less than) or '≤' (less than or equal to), you MUST treat the numeric value following the symbol as the base value for calculation (e.g., treat '<1g' as '1.0g'). This ensures a conservative "upper bound" estimate for total package nutrition, which is safer for dietary tracking. Do NOT estimate a lower value or treat it as zero unless the numeric value itself is 0.
//
//            HEALTH EVALUATION (1-10):
//            - Evaluate based on "Nutrient Density" vs "Empty Calories".
//            - GREEN FLAGS (Score +): High protein, high dietary fiber, low sugar-to-carb ratio, contains healthy fats.
//            - RED FLAGS (Score -): Trans fats > 0, excessive sodium (>800mg per serving), high added sugar (>20g per serving or sugar being the primary carb source).
//            - SCORING GUIDE:
//              * 9-10 (Nutrient Dense): High protein/fiber with minimal processing markers. Essential "superfoods" (e.g., Natto, plain nuts).
//              * 7-8 (Good/Balanced): Solid macro profile, moderate sodium, manageable sugar. Typical of healthy meals.
//              * 4-6 (Moderate/Processed): High energy but low "quality" nutrients (fiber/protein). Many common snacks or instant meals fall here.
//              * 1-3 (Empty Calories): High in "Red Flags". Pure sugar, high sodium, or trans fats with near-zero fiber/protein (e.g., Soda, candy, some ultra-processed pastries).
//            - BEVERAGE RULE: Be stricter with liquid sugar; beverages with >10g sugar per 100ml should rarely score above 5.
//
//            DATA CONVERSIONS:
//            - Energy: kJ to kcal (kcal = kJ / 4.184).
//            - Sodium: If ONLY "Salt" is provided, calculate Sodium (mg) = (salt_g * 393.4). If "Sodium (mg)" is explicitly printed, use that value directly.
//            - Defaults: Use 0.0 for missing nutrients.
//            - Names: Preserve the original visible product name from the package whenever possible. Do NOT force translation. Do NOT translate brand names. No weights/units in foodName.
//            - Round all calculated nutrient values to ONE decimal place (e.g., 45.5).
//
//            QUANTITY & UNIT RULES:
//            - "value": Always 1.0 (representing the whole entity).
//            - "unit": MUST be strictly one of [%s]. No "g", "ml", "oz".
//            - Use SERVING only if container type is unknown.
//
//            SANITY CHECKS:
//            - Fiber <= Carbs. Sugar <= Carbs. Confidence should be low if the image is blurry or numbers are unreadable.
//
//            CORE LOGIC (Entity Normalization):
//            1. PACKAGED FOOD (Bags/Boxes):
//               - ALWAYS calculate for the ENTIRE package content.
//               - Set quantity: {"value": 1.0, "unit": "PACK"}.
//               - Set labelMeta: {"servingsPerContainer": [Actual number from label], "basis": "WHOLE_PACKAGE"}.
//            2. BOTTLED/CANNED BEVERAGE:
//               - ALWAYS calculate for the ENTIRE bottle/can.
//               - Set quantity: {"value": 1.0, "unit": "BOTTLE" or "CAN"}.
//               - Set labelMeta: {"servingsPerContainer": [Actual number from label], "basis": "WHOLE_PACKAGE"}.
//
//            Please help me calculate the entire serving of "nutrients" REQUIRED JSON FORMAT:
//            {
//              "foodName": "string",
//              "quantity": { "value": 1.0, "unit": "PACK|BOTTLE|CAN|PIECE|SERVING" },
//              "nutrients": { "kcal": number, "protein": number, "fat": number, "carbs": number, "fiber": number, "sugar": number, "sodium": number },
//              "_reasoning": "string",
//              "confidence": number, (Range: 0.0 to 1.0)
//              "healthScore": number, (Range: 1 to 10)
//              "warnings": string[],
//              "labelMeta": { "servingsPerContainer": number|null, "basis": %s }
//            }
//            - "warnings": Use strings from this set: [%s].
//            """.formatted(ALLOWED_UNITS, ALLOWED_BASES, ALLOWED_WARNINGS);

    private static final String USER_PROMPT_LABEL_MAIN = """
            You are an object dedicated to identifying nutrition labels on food packaging from various countries around the world and calculating the nutritional value for the entire serving size.
            Return only a concise JSON object. No Markdown or extra text.

            PRIMARY GOAL:
            Calculate the total nutritional content for the entire package.
            If the label provides "Per Serving" and "Servings per Package", multiply them ONCE to get the total. If "Per 100g/ml" is provided, multiply by (Total Net Weight / 100).
            "If the total net weight or servings per package is NOT visible anywhere in the image, calculate the nutrients based on the visible reference unit (e.g., per 100g or per 1 serving), set 'value' to 1.0, and explain this assumption in '_reasoning'."

            VISUAL STRATEGY:
            1. MULTI-LANGUAGE KEYWORDS: Match labels in any language (e.g., '碳水化合物'/'炭水化物'=carbs, '糖'/'糖類'/'糖分'=sugar, '膳食纖維'=fiber, '鈉'/'ナトリウム'=sodium, '食塩相当量'=salt_equivalent).
            2. HIERARCHY & NESTED ITEMS: Tables often have indented sub-items (e.g., 'Saturated Fat' under 'Fat'). These are frequently prefixed with structural symbols like dashes, dots, or bullets (e.g., '-', 'ー', '・', '>'). You MUST treat these prefixes strictly as indentation markers. NEVER interpret a leading dash as a negative sign or a reason to skip the row. Continue scanning until you reach the absolute end of the macro-nutrient list (e.g., 'Sodium', 'Salt', or '食塩相当量').
            3. INCLUDED CONTENTS PRIORITY: If a row presents multiple values (e.g., "0.6g (0.0g)"), intelligently select the value representing the product WITH all its INCLUDED sauces/seasoning packets.\s
               - DO select the total value of the package contents.
               - DO NOT select "As Prepared" values that include external ingredients not sold in the package (e.g., added milk, eggs, or oil).
               - Use the table's header context (e.g., "たれ付" meaning with sauce, or "( )" meaning without sauce) to determine the correct value.
            4. SINGLE PATH CALCULATION: Choose ONLY ONE base column (prefer "Per Serving"). Multiply by the serving count exactly ONCE. Do NOT double-multiply.
            5. SKIP INTERRUPTIONS: Ignore non-target rows (Gluten, Vitamins, or marketing text) without stopping the scan. Ensure 'sodium' is captured even if it is at the very bottom or slightly distorted.
            6. TYPOGRAPHICAL NOISE: Ignore all decorative characters used for alignment, such as leader dots (....) or decorative separators. Crucially, disregard internal whitespace used for text justification. (e.g., '糖  質' must be read as '糖質', and '- of which  sugars' as 'sugars'). If a word is split by symbols or spaces to fit a column, recombine it into the standard nutrient name before matching.
            7. INEQUALITY HANDLING (Upper Bound Principle): > If a nutrient value contains inequality symbols such as '<' (less than) or '≤' (less than or equal to), you MUST treat the numeric value following the symbol as the base value for calculation (e.g., treat '<1g' as '1.0g'). This ensures a conservative "upper bound" estimate for total package nutrition, which is safer for dietary tracking. Do NOT estimate a lower value or treat it as zero unless the numeric value itself is 0.

            HEALTH EVALUATION (1-10):
            - Evaluate based on "Nutrient Density" vs "Empty Calories".
            - GREEN FLAGS (Score +): High protein, high dietary fiber, low sugar-to-carb ratio, contains healthy fats.
            - RED FLAGS (Score -): Trans fats > 0, excessive sodium (>800mg per serving), high added sugar (>20g per serving or sugar being the primary carb source).
            - SCORING GUIDE:
              * 9-10 (Nutrient Dense): High protein/fiber with minimal processing markers. Essential "superfoods" (e.g., Natto, plain nuts).
              * 7-8 (Good/Balanced): Solid macro profile, moderate sodium, manageable sugar. Typical of healthy meals.
              * 4-6 (Moderate/Processed): High energy but low "quality" nutrients (fiber/protein). Many common snacks or instant meals fall here.
              * 1-3 (Empty Calories): High in "Red Flags". Pure sugar, high sodium, or trans fats with near-zero fiber/protein (e.g., Soda, candy, some ultra-processed pastries).
            - BEVERAGE RULE: Be stricter with liquid sugar; beverages with >10g sugar per 100ml should rarely score above 5.

            DATA CONVERSIONS:
            - Energy: kJ to kcal (kcal = kJ / 4.184).
            - Sodium: If ONLY "Salt" is provided, calculate Sodium (mg) = (salt_g * 393.4). If "Sodium (mg)" is explicitly printed, use that value directly.
            - Defaults: Use 0.0 for missing nutrients.
            - Names: Preserve the original visible product name from the package whenever possible. Do NOT force translation. Do NOT translate brand names. No weights/units in foodName.
            - Round all calculated nutrient values to ONE decimal place (e.g., 45.5).

            QUANTITY & UNIT RULES:
            - "value": Always 1.0 (representing the whole entity).
            - "unit": MUST be strictly one of [%s]. No "g", "ml", "oz".
            - Use SERVING only if container type is unknown.

            SANITY CHECKS:
            - Fiber <= Carbs. Sugar <= Carbs. Confidence should be low if the image is blurry or numbers are unreadable.

            CORE LOGIC (Entity Normalization):
            1. PACKAGED FOOD (Bags/Boxes):
               - ALWAYS calculate for the ENTIRE package content.
               - Set quantity: {"value": 1.0, "unit": "PACK"}.
               - Set labelMeta: {"servingsPerContainer": [Actual number from label], "basis": "WHOLE_PACKAGE"}.
            2. BOTTLED/CANNED BEVERAGE:
               - ALWAYS calculate for the ENTIRE bottle/can.
               - Set quantity: {"value": 1.0, "unit": "BOTTLE" or "CAN"}.
               - Set labelMeta: {"servingsPerContainer": [Actual number from label], "basis": "WHOLE_PACKAGE"}.

            Please help me calculate the entire serving of "nutrients" REQUIRED JSON FORMAT:
            {
              "foodName": "string",
              "quantity": { "value": 1.0, "unit": "PACK|BOTTLE|CAN|PIECE|SERVING" },
              "nutrients": { "kcal": number, "protein": number, "fat": number, "carbs": number, "fiber": number, "sugar": number, "sodium": number },
              "_reasoning": "string",
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
