package com.calai.backend.foodlog.provider;

final class GeminiPromptFactory {

    private static final String USER_PROMPT_MAIN =
            "You are a food nutrition estimation engine. "
            + "Analyze the image and return ONE minified JSON object only (no markdown, no extra text). "
            + "The image may be product PACKAGING (front box) or the actual food. "
            + "Important: A human hand, fingers, background shelves, glare are NON-FOOD context; do NOT treat them as no-food. "
            + "Prefer reading printed text on the package (brand/product name, flavor, net weight). "
            + "If you recognize the product name but cannot see a nutrition label, STILL estimate typical nutrition for ONE unit (use net weight if visible, e.g., 72g). "
            + "If uncertain, output reasonable numbers but set confidence <= 0.35 and include LOW_CONFIDENCE warning. "
            + "If truly no food/product is present, then use NO_FOOD_DETECTED with confidence <= 0.2. "
            + "If foodName is not null/blank, you MUST provide numeric estimates for kcal, protein, fat, carbs (not null). "
            + "Output sodium in mg, macros in g. Always output ALL required keys; unknown -> null.";

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
        You are a nutrition JSON fixer + estimator.

        Return ONE valid MINIFIED JSON object ONLY (no markdown, no extra text, no extra keys).
        You MUST output ALL required keys:
          foodName, quantity{value,unit}, nutrients{kcal,protein,fat,carbs,fiber,sugar,sodium}, confidence, warnings

        CRITICAL RULES:
        1) If foodName is NOT null/blank, then nutrients MUST NOT be all null.
           - You MUST provide numeric estimates at least for: kcal, protein, fat, carbs.
           - fiber/sugar/sodium can be null if you truly cannot infer, but try your best.
        2) If you are unsure, still estimate typical values based on foodName/category words.
           - Set confidence <= 0.35 and include LOW_CONFIDENCE in warnings.
        3) Use NO_FOOD_DETECTED + all-null nutrients ONLY when PREVIOUS_OUTPUT clearly indicates there is no food/product.
        4) Do NOT re-analyze image; you may infer from foodName and context text only.

        PREVIOUS_OUTPUT:
        %s
        """.formatted(prev);
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

    // ✅ repair：要求它「不要把 nutrients 留空」的態度更強硬（但仍不重送圖片）
    private static final String USER_PROMPT_REPAIR =
            "Your previous response may have been invalid JSON or missing fields. "
            + "Return ONE full valid minified JSON object only (no markdown, no extra text). "
            + "Always output ALL required keys. "
            + "If foodName is present but nutrients are null, you MUST provide estimated typical nutrition for one unit and set confidence <= 0.35 with LOW_CONFIDENCE warning. "
            + "Do not stop early.";

    private static final String USER_PROMPT_LABEL_REPAIR = """
            Your previous output was invalid/truncated.
            
            Return ONLY ONE FULL MINIFIED JSON object matching the schema. No markdown. No extra text. No extra keys.
            You MUST output ALL required keys (including warnings and labelMeta). If unknown, use null (0.0 stays 0.0).
            Do NOT stop early. Do NOT output partial key:value. Ensure valid JSON.
            """;
}