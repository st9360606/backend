package com.calai.backend.foodlog.provider;

final class GeminiPromptFactory {

    /**
     * PHOTO / ALBUM 專用主 prompt
     * 目標：
     * 1. 第一輪盡量直接成功
     * 2. 包裝商品成功結果一律回整包 WHOLE_PACKAGE
     * 3. 不讓模型把 PER_SERVING 當成 photo/album 包裝商品的最終答案
     */
    private static final String USER_PROMPT_MAIN = """
                        You are a highly advanced multilingual food nutrition extraction engine.
                        Analyze the image and return ONLY ONE minified JSON object.
                        No markdown. No extra text. No explanations. No extra keys.
            
                        Return only the normalized nutrition payload.
                        Do NOT output API envelope fields such as foodLogId, status, degradeLevel, tierUsed, fromCache, source, trace, or healthScore.
            
                        The image may be:
                        1) Real food or drink.
                        2) Packaged retail food/drink (front, back, side, angled, or partially visible).
                        3) A screenshot of food/drinks or a photo of a screen displaying food/drinks.
                           In that case, analyze the food or drink shown INSIDE the screen.
                        4) Water, mineral water, sparkling water, zero-calorie drinks, plain black coffee, or unsweetened tea.
                        5) Truly no edible food or beverage product.
            
                        Focus on exactly ONE dominant intended edible subject, usually the centered, largest, or most visually prominent food/drink item.
                        Do NOT merge multiple visible foods or products into one nutrition result.
                        Ignore hands, fingers, glare, shelves, phone UI, monitor bezel, reflections, borders, packaging shine, and background objects.
            
                        DECISION ORDER (internal only):
                        1) If a clear packaged retail food/drink product is the dominant intended edible subject, treat it as PACKAGED_PRODUCT.
                        2) Else if a real prepared food/drink is the dominant intended edible subject, treat it as REAL_FOOD.
                        3) Else treat it as NO_FOOD.
            
                        MAIN GOAL:
                        Return one final usable nutrition result in this first pass whenever possible.
            
                        PACKAGED PRODUCT RULES:
                        - A front-of-pack image is still a valid PACKAGED_PRODUCT case, even if the back nutrition label is not visible.
                        - If a branded packaged retail food or drink product is clearly identified, the target answer is WHOLE_PACKAGE totals.
                        - Identify the most exact product possible: brand, product line, variant/flavor/type, zero/sugar-free/original distinction, package form, and size when visible.
                        - Visible native-script product text and distinctive front-pack design are valid identity signals.
                        - If printed nutrition is readable, use printed values first.
                        - If printed nutrition is incomplete or unreadable but the exact product identity is clear and specific, you MAY use exact known WHOLE_PACKAGE nutrition.
                        - You MAY use exact known WHOLE_PACKAGE nutrition when the front-pack design uniquely identifies a common standard single-bag or single-unit retail SKU in that market, even if the nutrition table or net weight is not readable.
                        - Do this only when brand + product name + variant + package form are specific enough to exclude nearby variants or nearby package sizes.
                        - If multiple package sizes or nearby variants remain plausible, do NOT guess.
                        - If exact product identity is insufficient for safe numeric completion, set foodName = null instead of inventing core quartet values.
                        - If packaged identity is edible but still not specific enough for a safe WHOLE_PACKAGE nutrition result, prefer foodName = null with UNKNOWN_FOOD rather than guessing a nearby product.
                        - If values are shown per serving and servings per container is visible or highly certain, multiply to WHOLE_PACKAGE totals.
                        - If values are shown per 100g or per 100ml and the total net weight/volume is visible or highly certain, multiply to WHOLE_PACKAGE totals.
                        - Net content may be shown in g, kg, mL, L, oz, or fl oz. Convert carefully only when unit meaning is clear and reliable.
                        - Do NOT rely on percent daily value alone unless the conversion is highly reliable.
                        - If the product is a multi-pack or contains multiple inner units, return WHOLE_PACKAGE totals only when the number of inner units or the total net content is visible or otherwise highly reliable.
                        - Do NOT guess total package totals from one inner unit alone unless package count or total net content is visible or highly certain.
                        - Never output a packaged-product result with only one nutrient or a partial nutrients object.
                        - For a packaged-product success result, the core quartet must be complete: kcal, protein, fat, carbs.
                        - If you cannot safely produce a complete WHOLE_PACKAGE packaged result, return an UNKNOWN_FOOD style fallback instead of a partial packaged result.
            
                        PHOTO / ALBUM PACKAGED PRODUCT FINAL RULE:
                        - For PACKAGED_PRODUCT in PHOTO/ALBUM, a successful final answer must represent WHOLE_PACKAGE totals.
                        - Do NOT return PER_SERVING as the final successful packaged-product answer.
                        - If WHOLE_PACKAGE totals cannot be derived safely, return an unknown packaged-food style fallback instead of a per-serving packaged result.
            
                        FOR SUCCESSFUL WHOLE_PACKAGE PACKAGED OUTPUT:
                        - set quantity.value = 1
                        - set quantity.unit = "SERVING"
                        - set labelMeta.basis = "WHOLE_PACKAGE"
                        - set labelMeta.servingsPerContainer when visible or highly certain
                        - do not stop at foodName only; produce a final usable nutrition result
            
                        ZERO-CALORIE / WATER RULES:
                        - Water, mineral water, sparkling water, unsweetened tea, plain black coffee, and clearly zero-sugar or zero-calorie drinks may legitimately have 0 or near-0 values.
                        - Preserve true zeros. Do NOT convert true zero to null.
                        - Do NOT invent snack-like macros for water or zero-calorie beverages.
                        - Do NOT assume milk coffee, latte, cocoa coffee, cream coffee, sweetened tea, juice, or dairy drinks are zero unless clearly indicated.
            
                        REAL FOOD RULES:
                        - Estimate one visible logged meal portion of edible food.
                        - Ignore bones, shells, inedible garnish, plates, bowls, trays, pans, and outer packaging.
                        - If the image looks like a shared pan, shared tray, or family-style dish, do NOT automatically assume the whole container was consumed as one personal serving.
                        - For shared pans, trays, or family-style dishes without a clear personal portion cue, estimate one standard restaurant serving.
                        - For REAL_FOOD, set labelMeta.servingsPerContainer = null and labelMeta.basis = null.
            
                        SCREEN FOOD RULES:
                        - If the food or drink is shown on a phone screen, computer screen, TV screen, or screenshot, analyze the displayed food/drink itself.
                        - Then still apply PACKAGED_PRODUCT rules or REAL_FOOD rules based on what is shown inside the screen.
                        - Do NOT return NO_FOOD only because the food is inside a screen.
            
                        NO-FOOD RULES:
                        - Use NO_FOOD_DETECTED only when there is truly no edible food or beverage product.
                        - Do NOT use NO_FOOD_DETECTED for packaged products, bottled drinks, canned drinks, water, screenshots of food, or photos of screens showing food.
            
                        FOOD NAME RULES:
                        - foodName should be the most specific supported product or meal name.
                        - Keep visible brand/product wording when readable.
                        - Do NOT invent translations when the original wording is visible.
            
                        OUTPUT UNITS:
                        - sodium in mg
                        - protein/fat/carbs/fiber/sugar in g
                        - energy in kcal
            
                        REQUIRED OUTPUT SHAPE:
                        {
                          "foodName": "string|null",
                          "quantity": {
                            "value": number,
                            "unit": "GRAM|ML|SERVING"
                          },
                          "nutrients": {
                            "kcal": number|null,
                            "protein": number|null,
                            "fat": number|null,
                            "carbs": number|null,
                            "fiber": number|null,
                            "sugar": number|null,
                            "sodium": number|null
                          },
                          "confidence": number,
                          "warnings": [],
                          "labelMeta": {
                            "servingsPerContainer": number|null,
                            "basis": "PER_SERVING" | "WHOLE_PACKAGE" | null
                          }
                        }
            
                        HARD OUTPUT RULES:
                        - Return ALL required keys.
                        - If foodName is not null and not blank, nutrients.kcal, nutrients.protein, nutrients.fat, nutrients.carbs MUST all be numeric and not null.
                        - 0 or 0.0 is a valid value and must be preserved.
                        - fiber, sugar, sodium may be null only when not reasonably inferable.
                        - For PACKAGED_PRODUCT in PHOTO/ALBUM, a successful final answer must use labelMeta.basis = "WHOLE_PACKAGE".
                        - warnings:
                          - use [] when exact or highly certain
                          - include LOW_CONFIDENCE only when the result is usable but approximate
                          - include UNKNOWN_FOOD only when edible food is present but product/meal identity is still unclear
                          - include NO_FOOD_DETECTED only for true no-food cases
                        - confidence:
                          - 0.80 to 0.98 for exact / highly certain results
                          - 0.55 to 0.79 for usable but approximate results
                          - <= 0.54 only when warnings contains LOW_CONFIDENCE
            
                        Visible text may be in any language or script.
            """;

    private static final String USER_PROMPT_LABEL_MAIN = """
            You extract Nutrition Facts from a PRINTED nutrition table (not a food photo).
            
            Return ONLY ONE MINIFIED JSON object that matches the schema. No markdown. No extra text. No extra keys.
            
            Rules:
            - Numbers only (no units in strings). kcal=Energy, g=macros, mg=sodium.
            - If only kJ: kcal = kJ / 4.184. If only salt(g) and no sodium: sodium_mg = salt_g * 393.4.
            - If label shows 0 or 0.0, output 0.0 (not null). If a nutrient is NOT visible, output null (do not guess).
            
            Serving logic:
            - Prefer PER-SERVING values if present.
              - If serving size is stated (e.g., 125ml/30g), set quantity to that (ML/GRAM).
              - If servings-per-container exists, set labelMeta.servingsPerContainer = X and labelMeta.basis = "PER_SERVING".
              - Do NOT multiply to whole package (backend will scale).
            - Only set labelMeta.basis="WHOLE_PACKAGE" and quantity=1 SERVING when the table explicitly states values are for the whole container/package.
            - If only per 100g/100ml exists, set quantity=100 with unit GRAM/ML, and labelMeta.basis = null.
            
            Food name:
            - Set foodName only if a clear product name is printed; otherwise null. Do not guess.
            """;

    String mainPrompt(boolean isLabel) {
        return isLabel ? USER_PROMPT_LABEL_MAIN : USER_PROMPT_MAIN;
    }

    String buildTextOnlyRepairPrompt(boolean isLabel, String previousOutput) {
        String prev = truncateForPrompt(previousOutput, 4000);

        if (isLabel) {
            return """
                    Fix the PREVIOUS_OUTPUT into ONE valid minified JSON object that matches the schema.
                    Rules:
                    - Return ONLY JSON. No markdown. No extra keys.
                    - You MUST output ALL required keys:
                      foodName, quantity{value,unit}, nutrients{kcal,protein,fat,carbs,fiber,sugar,sodium},
                      confidence, warnings, labelMeta{servingsPerContainer,basis}
                    - If a nutrient is NOT present in PREVIOUS_OUTPUT, set it to null (do NOT guess).
                    - Keep 0 or 0.0 as 0.0 (do not convert to null).
                    - Do NOT re-analyze image; only repair/normalize JSON format.
                    
                    PREVIOUS_OUTPUT:
                    %s
                    """.formatted(prev);
        }

        return """
                You are a nutrition JSON fixer + completer.
                
                Return ONE valid MINIFIED JSON object ONLY (no markdown, no extra text, no extra keys).
                You MUST output ALL required keys:
                  foodName, quantity{value,unit}, nutrients{kcal,protein,fat,carbs,fiber,sugar,sodium},
                  confidence, warnings, labelMeta{servingsPerContainer,basis}
                
                CRITICAL RULES:
                1) If foodName is NOT null/blank, then kcal, protein, fat, carbs MUST be numeric and not null.
                2) 0 or 0.0 is a valid real value. Do NOT convert true zero to null.
                3) If this is a clearly identified packaged retail product, you MAY use exact known product knowledge to complete missing package nutrition.
                4) Prefer WHOLE_PACKAGE totals when package-level total is visible or highly certain from specific product identity.
                5) For PHOTO/ALBUM packaged retail products, do NOT return PER_SERVING as the final repaired answer.
                6) If WHOLE_PACKAGE totals cannot be derived safely for a packaged retail product, return an unknown packaged-food style fallback instead of a per-serving packaged result.
                7) For clearly zero-calorie or near-zero beverages, keep valid zero / near-zero values. Do NOT invent snack-like macros.
                8) Use NO_FOOD_DETECTED only when PREVIOUS_OUTPUT clearly indicates there is truly no food/product.
                9) Do NOT re-analyze image pixels; repair and complete from the previous structured output and product identity/context.
                
                PREVIOUS_OUTPUT:
                %s
                """.formatted(prev);
    }

    String buildPackagedProductFinalizationPrompt(String foodNameHint, String previousOutput) {
        String name = truncateForPrompt(foodNameHint, 160);
        String prev = truncateForPrompt(previousOutput, 2500);

        return """
                Finalize nutrition JSON for the packaged retail food or drink product shown in the image.
                
                Return ONE valid MINIFIED JSON object ONLY.
                No markdown. No extra text. No extra keys.
                
                PRODUCT_IDENTITY_HINT:
                %s
                
                CONTEXT_FROM_PREVIOUS_PASS:
                %s
                
                HARD RULES:
                1) This is a packaged retail food/drink product, not a no-food case.
                2) Re-check the image, but do NOT require a perfectly readable back nutrition table in order to finish.
                3) Use visible printed nutrition numbers first when readable.
                4) If printed nutrition is incomplete or unreadable but the product identity is clear and specific,
                   you MAY use exact known product nutrition to complete the result.
                5) You MUST return a COMPLETE core quartet:
                   - nutrients.kcal
                   - nutrients.protein
                   - nutrients.fat
                   - nutrients.carbs
                   All four must be numeric and not null.
                6) If CONTEXT_FROM_PREVIOUS_PASS contains servingsPerContainer or basis, preserve and use those hints.
                7) If the previous pass indicates WHOLE_PACKAGE, return WHOLE_PACKAGE totals directly.
                8) If the previous pass indicates PER_SERVING and servingsPerContainer > 1, multiply and return WHOLE_PACKAGE totals.
                9) If the package is single-serve or servingsPerContainer = 1, still return the final packaged result as WHOLE_PACKAGE, not PER_SERVING.
                10) When returning WHOLE_PACKAGE totals:
                   - quantity.value = 1
                   - quantity.unit = "SERVING"
                   - labelMeta.basis = "WHOLE_PACKAGE"
                11) If only per-serving values are known and servingsPerContainer is known from context, multiply to whole-package totals.
                12) fiber, sugar, sodium should be numeric when reasonably inferable; otherwise null.
                13) 0 or 0.0 is valid and must be preserved.
                14) Do NOT output a partial nutrients object.
                    If you can provide kcal, you must also provide protein, fat, and carbs.
                15) Do NOT return NO_FOOD_DETECTED.
                16) Do NOT return PER_SERVING as the final packaged-product answer.
                17) warnings:
                    - use [] when exact / highly certain
                    - include LOW_CONFIDENCE only when the result is usable but approximate
                
                confidence:
                - exact / highly certain package result: 0.80 to 0.95
                - usable but approximate package result: 0.55 to 0.79
                
                REQUIRED OUTPUT SHAPE:
                {
                  "foodName": "string",
                  "quantity": { "value": 1, "unit": "SERVING" },
                  "nutrients": {
                    "kcal": number,
                    "protein": number,
                    "fat": number,
                    "carbs": number,
                    "fiber": number|null,
                    "sugar": number|null,
                    "sodium": number|null
                  },
                  "confidence": number,
                  "warnings": [],
                  "labelMeta": {
                    "servingsPerContainer": number|null,
                    "basis": "PER_SERVING" | "WHOLE_PACKAGE" | null
                  }
                }
                """.formatted(name, prev);
    }

    String buildPhotoRetryPrompt(String foodNameHint) {
        String name = truncateForPrompt(foodNameHint, 120);

        return """
            Re-analyze the image and return ONLY ONE valid minified JSON object.
            No markdown. No extra text. No extra keys.
            
            This is a PHOTO/ALBUM food-recognition request.
            Focus on exactly ONE dominant edible subject.
            
            Important:
            - The image may show a packaged retail food/drink product, even if only the front pack is visible.
            - The image may also be a screenshot or a photo of a screen showing a packaged product.
            - Ignore hands, glare, screen UI, borders, shelves, reflections, and background people.
            
            PACKAGED PRODUCT PRIORITY:
            - If the dominant subject is a packaged retail food/drink product, return WHOLE_PACKAGE totals.
            - A clearly visible brand + product name + distinctive front-pack design can be enough to identify a common standard single-bag or single-unit SKU.
            - If exact printed nutrition is not readable but the product identity is highly specific, you MAY use exact known WHOLE_PACKAGE nutrition.
            - Do NOT return a partial packaged result.
            - If packaged-product nutrition cannot be safely completed, return foodName=null and warnings including UNKNOWN_FOOD.
            
            REAL FOOD:
            - If the dominant subject is real prepared food, estimate one logged meal portion.
            
            ZERO-LIKE DRINKS:
            - Water, sparkling water, unsweetened tea, plain black coffee, and clearly zero-calorie drinks may legitimately be 0.
            - Preserve true zeros.
            
            FOOD NAME HINT:
            %s
            
            REQUIRED OUTPUT:
            {
              "foodName": "string|null",
              "quantity": { "value": number, "unit": "GRAM|ML|SERVING" },
              "nutrients": {
                "kcal": number|null,
                "protein": number|null,
                "fat": number|null,
                "carbs": number|null,
                "fiber": number|null,
                "sugar": number|null,
                "sodium": number|null
              },
              "confidence": number,
              "warnings": [],
              "labelMeta": {
                "servingsPerContainer": number|null,
                "basis": "PER_SERVING" | "WHOLE_PACKAGE" | null
              }
            }
            
            HARD RULES:
            - Return all required keys.
            - If foodName is not null and not blank, kcal/protein/fat/carbs must all be numeric and not null.
            - For packaged-product success results, the final answer must use labelMeta.basis="WHOLE_PACKAGE".
            - Do not return PER_SERVING as the final packaged-product answer.
            """.formatted(name == null || name.isBlank() ? "(none)" : name);
    }

    private String truncateForPrompt(String s, int maxChars) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.length() <= maxChars) {
            return t;
        }
        return t.substring(0, maxChars);
    }
}
