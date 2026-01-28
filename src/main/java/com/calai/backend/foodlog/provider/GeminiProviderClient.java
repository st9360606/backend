package com.calai.backend.foodlog.provider;

import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.mapper.ProviderErrorMapper;
import com.calai.backend.foodlog.storage.StorageService;
import com.calai.backend.foodlog.task.FoodLogWarning;
import com.calai.backend.foodlog.task.ProviderClient;
import com.calai.backend.foodlog.task.ProviderTelemetry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.util.Base64;
import java.util.Locale;

@Slf4j
public class GeminiProviderClient implements ProviderClient {

    private static final int MAX_UNKNOWN_FOOD_REPAIR_CALLS = 1;
    private static final int PREVIEW_LEN = 200;

    private static final String USER_PROMPT_MAIN =
            "You are a food nutrition estimation engine. "
            + "Analyze the image and return JSON strictly matching the schema. "
            + "Always output ALL required keys. If unknown, set null. "
            + "If the image contains a food item but you're uncertain, still ESTIMATE nutrition using typical values. "
            + "If no food is detected, set warnings include NO_FOOD_DETECTED and set confidence <= 0.2. "
            + "Output sodium in mg, macros in g.";

    private static final String USER_PROMPT_REPAIR =
            "Your previous response may have been truncated or invalid JSON. "
            + "Re-run the analysis and output FULL valid JSON matching the schema. "
            + "Return ONLY JSON. No markdown. No extra text. "
            + "Always output ALL required keys. If unknown, set null. "
            + "If the image contains food, ESTIMATE nutrition using typical values. "
            + "Do not stop early.";

    // ✅ 你選的 V4（加 393.4 + 400 近似）
    private static final String USER_PROMPT_LABEL_MAIN =
            "Act as a strict nutrition-label extraction engine. "
            + "Return ONLY a JSON object matching the schema. No markdown. No extra text. "

            + "1) SCOPE: The image is a Nutrition Facts / nutrition table (NOT a food photo). "
            + "IGNORE handwritten notes, colorful frames, stickers, or overlaid text (e.g., '成分表', '營養標籤'). "
            + "Focus ONLY on the printed nutrition table. "

            + "2) FOOD NAME (SAFE): "
            + "- Set foodName ONLY if a clear product name is printed (not addresses, dates, distributor info, UPC codes). "
            + "- If not clearly present, set foodName to null. Do NOT guess. "

            + "3) LANGUAGE: The label may be in ANY language. Do NOT refuse due to language. "
            + "Identify nutrients by context (Energy/Calories, Protein, Fat, Carbs, Sugars, Fiber, Sodium/Salt). "

            + "4) OUTPUT FORMAT (STRICT): "
            + "- ALL nutrient values MUST be pure numbers (no units in strings). "
            + "  Example: sodium: 312 (NOT \"312 mg\"), protein: 5.5 (NOT \"5.5g\"). "
            + "- Energy MUST be kcal (number). Macros MUST be grams (number). Sodium MUST be mg (number). "

            + "5) CONVERSIONS: "
            + "- If only kJ is present, convert to kcal: kcal = kJ / 4.184. "
            + "- If 'salt' is present but sodium missing, convert: sodium_mg = salt_g * 393.4 (≈ 400 acceptable approximation). "

            + "6) QUANTITY (PRIORITY): "
            + "- Highest priority: If 'per pack/container/package' (whole product) is present, "
            + "  set quantity.value=1, unit=SERVING, and nutrients must represent the WHOLE package. "
            + "- If both PER-SERVING nutrient values and per-100 values are present, ALWAYS prefer PER-SERVING nutrient values. "
            + "- Next: If the label shows 'servings per container' / '本包裝 X 份' / '本包裝含 X 份', "
            + "  and also shows PER-SERVING nutrient values, compute WHOLE package nutrients = (per-serving nutrients) * X, "
            + "  then set quantity.value=1, unit=SERVING (WHOLE package). "
            + "- If servings-per-container is present BUT per-serving nutrient values are NOT present, do NOT guess whole-package; "
            + "  fall back to per-100 as-is and add warning 'SERVING_SIZE_UNKNOWN'. "
            + "- Else: If 'per serving' size is shown (e.g., 30g, 250ml), prefer PER SERVING over per-100, "
            + "  set quantity.value/unit to that serving size, and nutrients must represent PER SERVING. "
            + "- If serving size is shown BUT the label does NOT provide per-serving nutrient values, do NOT guess; "
            + "  fall back to per-100 as-is and add warning 'SERVING_SIZE_UNKNOWN'. "
            + "- Else: If only 'per 100g/100ml' is shown, look for 'Net Weight/Net Wt/重量/淨重/內容量/容量'. "
            + "  If Net Weight is found (e.g., 250g or 1250ml), SCALE all nutrients by (Net Weight / 100) "
            + "  and set quantity to the Net Weight (unit=GRAM or ML). "
            + "- If Net Weight is NOT found, output per-100 as-is (nutrients represent PER 100): "
            + "  set quantity.value=100, unit=GRAM if the table says per 100g/100 g; "
            + "  set quantity.value=100, unit=ML if the table says per 100ml/100 ml; "
            + "  and add warning 'SERVING_SIZE_UNKNOWN'. "

            + "7) NO_LABEL_DETECTED RULE: "
            + "- DO NOT set NO_LABEL_DETECTED if ANY numeric nutrition value is readable (kcal/kJ/protein/fat/carbs/sodium/salt). "
            + "- DO NOT set NO_LABEL_DETECTED just because serving size or net weight is missing. "
            + "- Only if you truly cannot read the nutrition table at all, set warnings include NO_LABEL_DETECTED and confidence <= 0.2.";

    private static final String USER_PROMPT_LABEL_REPAIR =
            "Your previous response may have been truncated or invalid JSON. "
            + "Re-run label extraction and output FULL valid JSON matching the schema. "
            + "Return ONLY JSON. No markdown. No extra text. "
            + "Keep the same conversion rules: kJ->kcal, salt->sodium(mg).";

    private final RestClient http;
    private final GeminiProperties props;
    private final ObjectMapper om;
    private final ProviderTelemetry telemetry;

    public GeminiProviderClient(RestClient http, GeminiProperties props, ObjectMapper om, ProviderTelemetry telemetry) {
        this.http = http;
        this.props = props;
        this.om = om;
        this.telemetry = telemetry;
    }

    @Override
    public String providerCode() { return "GEMINI"; }

    @Override
    public ProviderResult process(FoodLogEntity entity, StorageService storage) throws Exception {
        long t0 = System.nanoTime();

        try {
            byte[] bytes;
            try (InputStream in = storage.open(entity.getImageObjectKey()).inputStream()) {
                bytes = in.readAllBytes();
            }
            if (bytes.length == 0) throw new IllegalStateException("EMPTY_IMAGE");

            String mime = (entity.getImageContentType() == null || entity.getImageContentType().isBlank())
                    ? "image/jpeg"
                    : entity.getImageContentType();

            final boolean isLabel = "LABEL".equalsIgnoreCase(entity.getMethod());
            final String promptMain   = isLabel ? USER_PROMPT_LABEL_MAIN   : USER_PROMPT_MAIN;
            final String promptRepair = isLabel ? USER_PROMPT_LABEL_REPAIR : USER_PROMPT_REPAIR;

            // ===== 1) main call =====
            CallResult r1 = callAndExtract(bytes, mime, promptMain, entity.getId());
            Tok tok = r1.tok;

            JsonNode parsed1 = tryParseJson(r1.text);
            if (isLabel && (parsed1 == null)) {
                log.warn("label_parse_failed foodLogId={} preview={}", entity.getId(), safeOneLine200(r1.text));
            }

            // ✅ LABEL：若已回 JSON 且含 NO_LABEL_DETECTED，直接接受（degraded DRAFT）
            if (isLabel && parsed1 != null && hasWarning(parsed1, FoodLogWarning.NO_LABEL_DETECTED.name())) {
                ObjectNode effective = normalizeToEffective(parsed1);
                telemetry.ok("GEMINI", entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
                return new ProviderResult(effective, "GEMINI");
            }

            // ✅ 有任何 nutrients 值就接受
            if (hasAnyNutrientValue(parsed1)) {
                ObjectNode effective = normalizeToEffective(parsed1);
                telemetry.ok("GEMINI", entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
                return new ProviderResult(effective, "GEMINI");
            }

            // ✅ Photo/Album：no-food 永遠不重打
            if (!isLabel && isNoFoodLikely(r1.text)) {
                ObjectNode fb = fallbackNoFoodDetected();
                ObjectNode effective = normalizeToEffective(fb);
                telemetry.ok("GEMINI", entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
                return new ProviderResult(effective, "GEMINI");
            }

            // ✅ LABEL：若不是 JSON 或 JSON 沒值，嘗試從文字直接抽取（不增加 API 次數）
            if (isLabel) {
                ObjectNode fromText = tryExtractLabelFromPlainText(r1.text);
                if (fromText != null) {
                    ObjectNode effective = normalizeToEffective(fromText);
                    telemetry.ok("GEMINI", entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
                    return new ProviderResult(effective, "GEMINI");
                }
            }

            // ✅ LABEL：no-label（這裡只處理「模型回一段文字」的情況）
            if (isLabel && isNoLabelLikely(r1.text)) {
                ObjectNode fb = fallbackNoLabelDetected();
                ObjectNode effective = normalizeToEffective(fb);
                telemetry.ok("GEMINI", entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
                return new ProviderResult(effective, "GEMINI");
            }

            // ✅ LABEL 最多重試一次：provider 內不 repair，交給 worker attempts=2
            final int maxRepair = isLabel ? 0 : MAX_UNKNOWN_FOOD_REPAIR_CALLS;
            String lastText = r1.text;

            // ===== 2) repair loop（只有 Photo/Album 才會進）=====
            for (int i = 1; i <= maxRepair; i++) {
                CallResult rn = callAndExtract(bytes, mime, promptRepair, entity.getId());
                tok = Tok.mergePreferNew(tok, rn.tok);
                lastText = rn.text;

                JsonNode parsedN = tryParseJson(rn.text);
                if (hasAnyNutrientValue(parsedN)) {
                    ObjectNode effective = normalizeToEffective(parsedN);
                    telemetry.ok("GEMINI", entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
                    return new ProviderResult(effective, "GEMINI");
                }

                if (isNoFoodLikely(rn.text)) {
                    ObjectNode fb = fallbackNoFoodDetected();
                    ObjectNode effective = normalizeToEffective(fb);
                    telemetry.ok("GEMINI", entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
                    return new ProviderResult(effective, "GEMINI");
                }
            }

            // ===== 3) final fallback =====
            if (isLabel) {
                // 讓 worker 自動重試一次（attempts=2）；最後 worker 會 fallback NO_LABEL_DETECTED
                throw new IllegalStateException("PROVIDER_BAD_RESPONSE");
            }

            ObjectNode fbUnknown = fallbackUnknownFoodFromBrokenText(lastText);
            ObjectNode effective = normalizeToEffective(fbUnknown);
            telemetry.ok("GEMINI", entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
            return new ProviderResult(effective, "GEMINI");

        } catch (Exception e) {
            ProviderErrorMapper.Mapped mapped = ProviderErrorMapper.map(e);
            telemetry.fail("GEMINI", entity.getId(), msSince(t0), mapped.code(), mapped.retryAfterSec());
            throw e;
        }
    }

    // ===== no-label 判斷（多語 + 有數字就不要誤判）=====
    private static boolean isNoLabelLikely(String rawText) {
        if (rawText == null) return false;
        String s = rawText.trim();
        if (s.isEmpty()) return false;

        String lower = s.toLowerCase(Locale.ROOT);

        // 1) 數字防線：只要有數字，極大機率讀到部分表格，不要判 NO_LABEL
        if (P_NUM.matcher(lower).find()) return false;

        // 2) 正向信號（你目前這版）
        boolean hasSignals =
                lower.contains("nutrition") || lower.contains("calor")
                || lower.contains("energy") || lower.contains("energi") || lower.contains("energia")
                || lower.contains("kcal") || lower.contains("kj")
                || lower.contains("protein") || lower.contains("prote")
                || lower.contains("fat") || lower.contains("lipid") || lower.contains("grasa") || lower.contains("fett") || lower.contains("lemak")
                || lower.contains("carb") || lower.contains("glucid")
                || lower.contains("sugar") || lower.contains("zucker") || lower.contains("azucar")
                || lower.contains("fiber") || lower.contains("fibre")
                || lower.contains("sodium") || lower.contains("salt") || lower.contains("salz")
                || lower.contains("serving") || lower.contains("portion") || lower.contains("per 100")
                || lower.contains("100g") || lower.contains("100 g") || lower.contains("100ml") || lower.contains("100 ml")

                // --- 中日韓 ---
                || lower.contains("營養") || lower.contains("能量") || lower.contains("熱量") || lower.contains("蛋白") || lower.contains("脂肪")
                || lower.contains("碳水") || lower.contains("糖") || lower.contains("鈉") || lower.contains("鹽")
                || lower.contains("栄養") || lower.contains("エネルギ") || lower.contains("カロリ")
                || lower.contains("たんぱく") || lower.contains("タンパク") || lower.contains("脂質") || lower.contains("炭水") || lower.contains("糖質")
                || lower.contains("ナトリウム") || lower.contains("食塩")
                || lower.contains("영양") || lower.contains("열량") || lower.contains("에너지")
                || lower.contains("단백") || lower.contains("지방") || lower.contains("탄수") || lower.contains("당")
                || lower.contains("나트륨") || lower.contains("소금")

                // --- 東南亞/南亞 ---
                || lower.contains("năng lượng") || lower.contains("nang luong")
                || lower.contains("đạm") || lower.contains("dam")
                || lower.contains("béo") || lower.contains("beo")
                || lower.contains("พลังงาน") || lower.contains("โปรตีน") || lower.contains("โซเดียม")
                || lower.contains("tenaga") || lower.contains("karbo") || lower.contains("garam") || lower.contains("natrium")
                || lower.contains("ऊर्जा") || lower.contains("प्रोटीन") || lower.contains("वसा") || lower.contains("कार्ब")
                || lower.contains("শক্তি") || lower.contains("প্রোটিন") || lower.contains("চর্ব") || lower.contains("কার্ব")

                // --- 中東 (AR/HE) ---
                || lower.contains("طاقة") || lower.contains("بروتين") || lower.contains("دهون") || lower.contains("صوديوم") || lower.contains("ملح")
                || lower.contains("אנרגיה") || lower.contains("חלבון") || lower.contains("שומן") || lower.contains("נתרן") || lower.contains("מלח");

        if (hasSignals) return false;

        // 3) 負面語意（保守）
        return lower.contains("no label")
               || lower.contains("no nutrition label")
               || lower.contains("nutrition table not found")
               || lower.contains("not a label")
               || lower.contains("cannot read")
               || lower.contains("unable to read")
               || lower.contains("too blurry")
               || lower.contains("no text detected")
               || lower.contains("tidak ditemukan")
               || lower.contains("không tìm thấy") || lower.contains("khong tim thay")
               || lower.contains("ไม่พบ") || lower.contains("อ่านไม่ออก");
    }

    // ===== fallbacks =====
    private ObjectNode fallbackNoLabelDetected() {
        ObjectNode root = om.createObjectNode();
        root.putNull("foodName");
        ObjectNode q = root.putObject("quantity");
        q.put("value", 1d);
        q.put("unit", "SERVING");
        ObjectNode n = root.putObject("nutrients");
        n.putNull("kcal"); n.putNull("protein"); n.putNull("fat"); n.putNull("carbs");
        n.putNull("fiber"); n.putNull("sugar"); n.putNull("sodium");
        root.put("confidence", 0.1);
        root.putArray("warnings").add(FoodLogWarning.NO_LABEL_DETECTED.name());
        return root;
    }

    private ObjectNode fallbackNoFoodDetected() {
        ObjectNode root = om.createObjectNode();
        root.putNull("foodName");
        ObjectNode q = root.putObject("quantity");
        q.put("value", 1d);
        q.put("unit", "SERVING");
        ObjectNode n = root.putObject("nutrients");
        n.putNull("kcal"); n.putNull("protein"); n.putNull("fat"); n.putNull("carbs");
        n.putNull("fiber"); n.putNull("sugar"); n.putNull("sodium");
        root.put("confidence", 0.1);
        root.putArray("warnings").add(FoodLogWarning.NO_FOOD_DETECTED.name());
        return root;
    }

    private ObjectNode fallbackUnknownFoodFromBrokenText(String rawText) {
        String name = extractFoodNameFromBrokenText(rawText);
        ObjectNode root = om.createObjectNode();
        if (name == null || name.isBlank()) root.putNull("foodName");
        else root.put("foodName", name);

        ObjectNode q = root.putObject("quantity");
        q.put("value", 1d);
        q.put("unit", "SERVING");

        ObjectNode n = root.putObject("nutrients");
        n.putNull("kcal"); n.putNull("protein"); n.putNull("fat"); n.putNull("carbs");
        n.putNull("fiber"); n.putNull("sugar"); n.putNull("sodium");

        root.put("confidence", 0.2);
        ArrayNode w = root.putArray("warnings");
        w.add(FoodLogWarning.UNKNOWN_FOOD.name());
        w.add(FoodLogWarning.LOW_CONFIDENCE.name());
        return root;
    }

    // ===== call Gemini =====
    private CallResult callAndExtract(byte[] imageBytes, String mimeType, String userPrompt, String foodLogIdForLog) {
        JsonNode resp = callGenerateContent(imageBytes, mimeType, userPrompt);
        Tok tok = extractUsage(resp);

        String text = extractJoinedTextOrNull(resp);
        if (text == null) text = "";

        log.debug("geminiTextPreview foodLogId={} preview={}", foodLogIdForLog, safeOneLine200(text));
        return new CallResult(tok, text);
    }

    private JsonNode callGenerateContent(byte[] imageBytes, String mimeType, String userPrompt) {
        ObjectNode req = buildRequest(imageBytes, mimeType, userPrompt);

        return http.post()
                .uri("/v1beta/models/{model}:generateContent", props.getModel())
                .header("x-goog-api-key", requireApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(req)
                .retrieve()
                .body(JsonNode.class);
    }

    private ObjectNode buildRequest(byte[] imageBytes, String mimeType, String userPrompt) {
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        ObjectNode root = om.createObjectNode();

        ObjectNode sys = root.putObject("systemInstruction");
        sys.putArray("parts").addObject().put("text",
                "Return ONLY JSON that matches the schema. No markdown. No extra text."
        );

        ArrayNode contents = root.putArray("contents");
        ObjectNode c0 = contents.addObject();
        c0.put("role", "user");
        ArrayNode parts = c0.putArray("parts");
        parts.addObject().put("text", userPrompt);

        ObjectNode imgPart = parts.addObject();
        ObjectNode inline = imgPart.putObject("inlineData");
        inline.put("mimeType", mimeType);
        inline.put("data", b64);

        ObjectNode gen = root.putObject("generationConfig");
        gen.put("responseMimeType", "application/json");
        gen.set("_responseJsonSchema", nutritionJsonSchema()); // ✅
        gen.put("maxOutputTokens", props.getMaxOutputTokens());
        gen.put("temperature", props.getTemperature());

        return root;
    }

    private ObjectNode nutritionJsonSchema() {
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);

        ObjectNode propsNode = schema.putObject("properties");

        ObjectNode foodName = propsNode.putObject("foodName");
        foodName.putArray("type").add("string").add("null");
        foodName.put("maxLength", 80);

        ObjectNode quantity = propsNode.putObject("quantity");
        quantity.put("type", "object");
        quantity.put("additionalProperties", false);

        ObjectNode qProps = quantity.putObject("properties");
        qProps.putObject("value").putArray("type").add("number").add("null");

        ObjectNode unit = qProps.putObject("unit");
        unit.putArray("type").add("string").add("null");
        unit.putArray("enum").add("SERVING").add("GRAM").add("ML");

        quantity.putArray("required").add("value").add("unit");

        ObjectNode nutrients = propsNode.putObject("nutrients");
        nutrients.put("type", "object");
        nutrients.put("additionalProperties", false);

        ObjectNode nProps = nutrients.putObject("properties");
        ArrayNode nReq = nutrients.putArray("required");
        for (String k : new String[]{"kcal","protein","fat","carbs","fiber","sugar","sodium"}) {
            nProps.putObject(k).putArray("type").add("number").add("null");
            nReq.add(k);
        }

        propsNode.putObject("confidence").putArray("type").add("number").add("null");

        ObjectNode warnings = propsNode.putObject("warnings");
        warnings.put("type", "array");
        ObjectNode items = warnings.putObject("items");
        items.put("type", "string");
        ArrayNode wEnum = items.putArray("enum");
        for (FoodLogWarning w : FoodLogWarning.values()) {
            wEnum.add(w.name());
        }

        schema.putArray("required").add("foodName").add("quantity").add("nutrients").add("confidence");
        return schema;
    }

    // ===== parse + normalize =====
    private JsonNode tryParseJson(String rawText) {
        if (rawText == null) return null;

        String s = stripFenceAndExtractObject(rawText.trim());
        if (s.isBlank() || !s.contains("{")) return null;

        s = stripBomAndNulls(s);
        s = escapeBadCharsInsideJsonStrings(s);

        try {
            return om.readTree(s);
        } catch (Exception first) {
            String fixed = balanceBracesIfNeeded(s);
            try {
                return om.readTree(fixed);
            } catch (Exception second) {
                return null;
            }
        }
    }

    private ObjectNode normalizeToEffective(JsonNode raw) {
        if (raw == null || !raw.isObject()) throw new IllegalStateException("PROVIDER_BAD_RESPONSE");

        ObjectNode out = JsonNodeFactory.instance.objectNode();

        JsonNode foodNameNode = raw.get("foodName");
        if (foodNameNode != null && !foodNameNode.isNull()) {
            String foodName = foodNameNode.asText(null);
            if (foodName != null && !foodName.isBlank()) out.put("foodName", foodName);
        }

        // quantity（保底 + 容錯）
        ObjectNode oq = out.putObject("quantity");
        JsonNode q = raw.get("quantity");
        if (q != null && q.isObject()) {
            Double value = parseNumberNodeOrText(q.get("value"));
            if (value == null || value < 0) value = 1d;
            oq.put("value", value);

            String unit = (q.get("unit") == null || q.get("unit").isNull())
                    ? "SERVING"
                    : q.get("unit").asText("SERVING");
            oq.put("unit", normalizeUnit(unit));
        } else {
            oq.put("value", 1d);
            oq.put("unit", "SERVING");
        }

        JsonNode n = raw.get("nutrients");
        if (n == null || !n.isObject()) throw new IllegalStateException("PROVIDER_BAD_RESPONSE");

        ObjectNode on = out.putObject("nutrients");
        copyNonNegativeNumberOrNull(n, on, "kcal");
        copyNonNegativeNumberOrNull(n, on, "protein");
        copyNonNegativeNumberOrNull(n, on, "fat");
        copyNonNegativeNumberOrNull(n, on, "carbs");
        copyNonNegativeNumberOrNull(n, on, "fiber");
        copyNonNegativeNumberOrNull(n, on, "sugar");
        copyNonNegativeNumberOrNull(n, on, "sodium");

        // confidence（容錯字串）
        Double conf = parseNumberNodeOrText(raw.get("confidence"));
        if (conf == null) out.putNull("confidence");
        else {
            if (conf < 0 || conf > 1) throw new IllegalStateException("PROVIDER_BAD_RESPONSE");
            out.put("confidence", conf);
        }

        // warnings
        JsonNode w = raw.get("warnings");
        if (w != null && w.isArray()) {
            ArrayNode outW = JsonNodeFactory.instance.arrayNode();
            for (JsonNode it : w) {
                if (it == null || it.isNull()) continue;
                FoodLogWarning ww = FoodLogWarning.parseOrNull(it.asText());
                if (ww != null) outW.add(ww.name());
            }
            if (!outW.isEmpty()) out.set("warnings", outW);
        }

        return out;
    }

    // ✅ 容錯：允許 "312 mg" / "5.5g" / "2118 kJ"
    private static void copyNonNegativeNumberOrNull(JsonNode from, ObjectNode to, String key) {
        JsonNode v = from.get(key);
        if (v == null || v.isMissingNode() || v.isNull()) {
            to.putNull(key);
            return;
        }

        Double d = null;

        if (v.isNumber()) d = v.asDouble();
        else if (v.isTextual()) {
            String raw = v.asText("").trim();
            if (!raw.isEmpty()) {
                d = parseFirstNumber(raw);
                if (d != null) {
                    String lower = raw.toLowerCase(Locale.ROOT);

                    if ("kcal".equals(key) && lower.contains("kj") && !lower.contains("kcal")) d = d / 4.184d;

                    if ("sodium".equals(key)) {
                        if (lower.contains(" g") || lower.endsWith("g")) d = d * 1000d; // g -> mg
                    }

                    if (!"sodium".equals(key) && !"kcal".equals(key)) {
                        if (lower.contains("mg")) d = d / 1000d; // mg -> g
                    }
                }
            }
        } else {
            throw new IllegalStateException("PROVIDER_BAD_RESPONSE");
        }

        if (d == null) {
            to.putNull(key);
            return;
        }
        if (d < 0) throw new IllegalStateException("PROVIDER_BAD_RESPONSE");
        to.put(key, d);
    }

    // ===== misc helpers =====
    private static boolean hasAnyNutrientValue(JsonNode root) {
        return root != null && !isAllNutrientsNull(root);
    }

    private static boolean hasWarning(JsonNode root, String code) {
        if (root == null || !root.isObject()) return false;
        JsonNode w = root.get("warnings");
        if (w == null || !w.isArray()) return false;
        for (JsonNode it : w) {
            if (it == null || it.isNull()) continue;
            if (code.equalsIgnoreCase(it.asText())) return true;
        }
        return false;
    }

    private static boolean isAllNutrientsNull(JsonNode root) {
        if (root == null || !root.isObject()) return true;
        JsonNode n = root.get("nutrients");
        if (n == null || !n.isObject()) return true;

        for (String k : new String[]{"kcal","protein","fat","carbs","fiber","sugar","sodium"}) {
            JsonNode v = n.get(k);
            if (v != null && !v.isNull()) return false;
        }
        return true;
    }

    private static boolean isNoFoodLikely(String rawText) {
        if (rawText == null) return false;
        String lower = rawText.toLowerCase(Locale.ROOT);
        return lower.contains("no food")
               || lower.contains("no meal")
               || lower.contains("not food")
               || lower.contains("undetected")
               || lower.contains("cannot identify")
               || lower.contains("unable to")
               || lower.contains("\"foodname\":null")
               || lower.contains("\"foodname\" : null");
    }

    private static String extractJoinedTextOrNull(JsonNode resp) {
        if (resp == null || resp.isNull()) return null;
        JsonNode cand0 = resp.path("candidates").path(0);
        JsonNode parts = cand0.path("content").path("parts");
        if (!parts.isArray()) return null;

        StringBuilder sb = new StringBuilder(256);
        for (JsonNode p : parts) {
            String t = p.path("text").asText(null);
            if (t != null) sb.append(t);
        }
        String joined = sb.toString().trim();
        return joined.isEmpty() ? null : joined;
    }

    private Tok extractUsage(JsonNode resp) {
        Integer p = null, c = null, t = null;
        JsonNode usage = (resp == null) ? null : resp.path("usageMetadata");
        if (usage != null && !usage.isMissingNode() && !usage.isNull()) {
            p = usage.path("promptTokenCount").isInt() ? usage.path("promptTokenCount").asInt() : null;
            c = usage.path("candidatesTokenCount").isInt() ? usage.path("candidatesTokenCount").asInt() : null;
            t = usage.path("totalTokenCount").isInt() ? usage.path("totalTokenCount").asInt() : null;
        }
        return new Tok(p, c, t);
    }

    private static String safeOneLine200(String s) {
        if (s == null) return null;
        String t = s.replace("\r", " ").replace("\n", " ").trim();
        return (t.length() > PREVIEW_LEN) ? t.substring(0, PREVIEW_LEN) : t;
    }

    private static long msSince(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    private String requireApiKey() {
        String k = props.getApiKey();
        if (k == null || k.isBlank()) throw new IllegalStateException("GEMINI_API_KEY_MISSING");
        return k.trim();
    }

    private static final java.util.regex.Pattern P_NUM =
            java.util.regex.Pattern.compile("(-?\\d+(?:[.,]\\d+)?)");

    private static Double parseFirstNumber(String raw) {
        var m = P_NUM.matcher(raw);
        if (!m.find()) return null;
        String s = m.group(1).replace(',', '.');
        try { return Double.parseDouble(s); }
        catch (Exception ignored) { return null; }
    }

    private static Double parseNumberNodeOrText(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (node.isNumber()) return node.asDouble();
        if (!node.isTextual()) return null;
        String raw = node.asText("").trim();
        if (raw.isEmpty()) return null;
        return parseFirstNumber(raw);
    }

    private static String normalizeUnit(String unit) {
        if (unit == null) return "SERVING";
        String u = unit.trim().toUpperCase(Locale.ROOT);
        if (u.isEmpty()) return "SERVING";
        if (u.equals("G") || u.equals("GRAM") || u.equals("GRAMS")) return "GRAM";
        if (u.equals("ML") || u.equals("MILLILITER") || u.equals("MILLILITERS") || u.equals("MILLILITRE") || u.equals("MILLILITRES"))
            return "ML";
        if (u.equals("SERVING") || u.equals("SERVINGS") || u.equals("PORTION") || u.equals("PORTIONS") || u.equals("PCS") || u.equals("PC"))
            return "SERVING";
        if (u.equals("GRAM") || u.equals("ML") || u.equals("SERVING")) return u;
        return "SERVING";
    }

    private static String stripFenceAndExtractObject(String s) {
        if (s == null) return "";
        if (s.contains("```")) {
            int first = s.indexOf("```");
            int firstNl = s.indexOf('\n', first);
            if (firstNl > 0) s = s.substring(firstNl + 1);
            int lastFence = s.lastIndexOf("```");
            if (lastFence >= 0) s = s.substring(0, lastFence);
            s = s.trim();
        }
        int l = s.indexOf('{');
        if (l < 0) return "";
        s = s.substring(l).trim();
        int r = s.lastIndexOf('}');
        if (r >= 0) s = s.substring(0, r + 1).trim();
        return s;
    }

    private static String balanceBracesIfNeeded(String s) {
        if (s == null || s.isBlank()) return s;
        boolean inString = false, escaped = false;
        int balance = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (ch == '\\') { if (inString) escaped = true; continue; }
            if (ch == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (ch == '{') balance++;
            else if (ch == '}') balance--;
        }
        if (balance <= 0) return s;
        return s + "}".repeat(balance);
    }

    private static String stripBomAndNulls(String s) {
        if (s == null) return "";
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') s = s.substring(1);
        return s.replace("\u0000", "");
    }

    private static String escapeBadCharsInsideJsonStrings(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder out = new StringBuilder(s.length() + 16);
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (!inString) {
                if (ch == '"') inString = true;
                if (ch == '\u0000') continue;
                if (ch < 0x20 && ch != '\n' && ch != '\r' && ch != '\t') continue;
                out.append(ch);
                continue;
            }
            if (escaped) { out.append(ch); escaped = false; continue; }
            if (ch == '\\') { out.append(ch); escaped = true; continue; }
            if (ch == '"') { out.append(ch); inString = false; continue; }
            if (ch == '\r' || ch == '\n') { out.append("\\n"); continue; }
            if (ch < 0x20) { out.append(' '); continue; }
            out.append(ch);
        }
        return out.toString();
    }

    private ObjectNode tryExtractLabelFromPlainText(String raw) {
        if (raw == null) return null;
        String s = raw.replace('\r',' ').replace('\n',' ').trim();
        if (s.isEmpty()) return null;

        String lower = s.toLowerCase(Locale.ROOT);
        if (!P_NUM.matcher(lower).find()) return null;

        Double sodium = findNumberAfterAny(lower, new String[]{"sodium", "鈉"});
        Double kcal   = findNumberAfterAny(lower, new String[]{"kcal", "calories", "熱量", "大卡"});
        Double servingMl = findNumberAfterAny(lower, new String[]{"serving size", "每份量"});
        Double servingsPerContainer = findNumberAfterAny(lower, new String[]{"servings per container", "本包裝", "本包裝含"});

        // 至少抓到 sodium 或 kcal 才算有效
        if (sodium == null && kcal == null) return null;

        ObjectNode root = om.createObjectNode();
        root.putNull("foodName");

        // quantity：優先用 serving size（ml）
        ObjectNode q = root.putObject("quantity");
        if (servingMl != null && servingMl > 0) {
            q.put("value", servingMl);
            q.put("unit", "ML");
        } else {
            q.put("value", 1d);
            q.put("unit", "SERVING");
        }

        ObjectNode n = root.putObject("nutrients");
        putOrNull(n, "kcal", kcal);
        putOrNull(n, "protein", 0d);
        putOrNull(n, "fat", 0d);
        putOrNull(n, "carbs", 0d);
        putOrNull(n, "fiber", 0d);
        putOrNull(n, "sugar", 0d);
        putOrNull(n, "sodium", sodium); // mg

        root.put("confidence", 0.35);

        ArrayNode w = root.putArray("warnings");
        w.add(FoodLogWarning.LOW_CONFIDENCE.name());

        // 若只抓到 per-100 類型資訊但沒有淨重/每份，這裡也可加 SERVING_SIZE_UNKNOWN（視你需要）
        // if (...) w.add(FoodLogWarning.SERVING_SIZE_UNKNOWN.name());

        return root;
    }

    private static void putOrNull(ObjectNode obj, String key, Double v) {
        if (v == null) obj.putNull(key);
        else obj.put(key, v);
    }

    private static Double findNumberAfterAny(String text, String[] keys) {
        for (String k : keys) {
            int idx = text.indexOf(k);
            if (idx < 0) continue;
            String tail = text.substring(idx);
            if (tail.length() > 80) tail = tail.substring(0, 80);
            Double d = parseFirstNumber(tail);
            if (d != null) return d;
        }
        return null;
    }


    private static String extractFoodNameFromBrokenText(String raw) {
        if (raw == null) return null;
        int idx = raw.toLowerCase(Locale.ROOT).indexOf("\"foodname\"");
        if (idx < 0) return null;
        int colon = raw.indexOf(':', idx);
        if (colon < 0) return null;
        int q1 = raw.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        int q2 = raw.indexOf('"', q1 + 1);
        String out = (q2 < 0) ? raw.substring(q1 + 1) : raw.substring(q1 + 1, q2);
        out = out.replace("\r", " ").replace("\n", " ").trim();
        if (out.length() > 80) out = out.substring(0, 80);
        return out.isBlank() ? null : out;
    }

    private record CallResult(Tok tok, String text) {}
    private record Tok(Integer promptTok, Integer candTok, Integer totalTok) {
        static Tok mergePreferNew(Tok oldTok, Tok newTok) {
            if (newTok == null) return oldTok;
            return new Tok(
                    newTok.promptTok != null ? newTok.promptTok : oldTok.promptTok,
                    newTok.candTok != null ? newTok.candTok : oldTok.candTok,
                    newTok.totalTok != null ? newTok.totalTok : oldTok.totalTok
            );
        }
    }
}
