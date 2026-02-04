package com.calai.backend.foodlog.provider;

import com.calai.backend.foodlog.config.AiModelRouter;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.mapper.ProviderErrorMapper;
import com.calai.backend.foodlog.model.ModelMode;
import com.calai.backend.foodlog.model.ProviderRefuseReason;
import com.calai.backend.foodlog.provider.config.GeminiProperties;
import com.calai.backend.foodlog.quota.model.ModelTier;
import com.calai.backend.foodlog.storage.StorageService;
import com.calai.backend.foodlog.task.FoodLogWarning;
import com.calai.backend.foodlog.task.ProviderClient;
import com.calai.backend.foodlog.task.ProviderTelemetry;
import com.calai.backend.foodlog.web.ModelRefusedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
public class GeminiProviderClient implements ProviderClient {

    private static final int MAX_UNKNOWN_FOOD_REPAIR_CALLS = 1;
    private static final int MAX_LABEL_REPAIR_CALLS = 1;
    private static final int PREVIEW_LEN = 200;
    private static final String FN_EMIT_NUTRITION = "emitNutrition";

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

    private static final String USER_PROMPT_LABEL_REPAIR = """
            Your previous output was invalid/truncated.
            
            Return ONLY ONE FULL MINIFIED JSON object matching the schema. No markdown. No extra text. No extra keys.
            You MUST output ALL required keys (including warnings and labelMeta). If unknown, use null (0.0 stays 0.0).
            Do NOT stop early. Do NOT output partial key:value. Ensure valid JSON.
            """;

    private final RestClient http;
    private final GeminiProperties props;
    private final ObjectMapper om;
    private final ProviderTelemetry telemetry;
    private final AiModelRouter modelRouter;

    public GeminiProviderClient(RestClient http, GeminiProperties props, ObjectMapper om,
                                ProviderTelemetry telemetry, AiModelRouter modelRouter) {
        this.http = http;
        this.props = props;
        this.om = om;
        this.telemetry = telemetry;
        this.modelRouter = modelRouter;
    }

    @Override
    public String providerCode() { return "GEMINI"; }

    @Override
    public ProviderResult process(FoodLogEntity entity, StorageService storage) throws Exception {
        long t0 = System.nanoTime();
        String modelId = null;

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
            modelId = resolveModelIdVision(entity);
            final String promptMain   = isLabel ? USER_PROMPT_LABEL_MAIN   : USER_PROMPT_MAIN;
            final String promptRepair = isLabel ? USER_PROMPT_LABEL_REPAIR : USER_PROMPT_REPAIR;

            // ===== 1) main call =====
            CallResult r1 = callAndExtract(bytes, mime, promptMain, modelId, isLabel, entity.getId());
            Tok tok = r1.tok;

            // ✅ 先吃 functionArgs（根治）
            JsonNode parsed1 = (r1.functionArgs != null) ? r1.functionArgs : tryParseJson(r1.text);
            parsed1 = unwrapRootObjectOrNull(parsed1);

            // ✅ LABEL：保留「最佳候選」（即使不完整也先存起來，避免最後 throw）
            BestLabel best = BestLabel.empty();
            if (isLabel) best = best.consider(parsed1);

            if (isLabel && parsed1 == null) {
                log.warn("label_parse_failed foodLogId={} preview={}", entity.getId(), safeOneLine200(r1.text));
            }

            // ✅ LABEL：處理截斷 JSON（不增加 API 次數）
            if (isLabel && parsed1 == null && isJsonTruncated(r1.text)) {
                log.info("label_json_truncated_try_fix foodLogId={}", entity.getId());
                ObjectNode fromTruncated = tryFixTruncatedJson(r1.text);
                if (fromTruncated != null && hasAnyNutrientValue(fromTruncated)) {
                    ObjectNode effective = normalizeToEffective(fromTruncated);
                    applyWholePackageScalingIfNeeded(fromTruncated, effective);
                    telemetry.ok("GEMINI", modelId, entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
                    return new ProviderResult(effective, "GEMINI");
                }
            }

            // ✅ LABEL：若已回 JSON 且含 NO_LABEL_DETECTED，直接接受
            if (isLabel && parsed1 != null && hasWarning(parsed1, FoodLogWarning.NO_LABEL_DETECTED.name())) {
                ObjectNode effective = normalizeToEffective(parsed1);
                applyWholePackageScalingIfNeeded(parsed1, effective);
                telemetry.ok("GEMINI", modelId, entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
                return new ProviderResult(effective, "GEMINI");
            }

            // ✅ Photo/Album：main call 只要有任何 nutrients 值就成功
            if (!isLabel) {
                if (hasAnyNutrientValue(parsed1)) {
                    ObjectNode effective = normalizeToEffective(parsed1);
                    telemetry.ok("GEMINI", modelId, entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
                    return new ProviderResult(effective, "GEMINI");
                }

                // ✅ Photo/Album：no-food 永遠不重打
                if (isNoFoodLikely(r1.text)) {
                    ObjectNode fb = fallbackNoFoodDetected();
                    ObjectNode effective = normalizeToEffective(fb);
                    telemetry.ok("GEMINI", modelId, entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
                    return new ProviderResult(effective, "GEMINI");
                }

            } else {
                // ✅ LABEL：main call 成功條件更嚴格
                if (hasAnyNutrientValue(parsed1)) {
                    if (isLabelIncomplete(parsed1)) {
                        log.warn("label_incomplete_main_force_repair foodLogId={} preview={}",
                                entity.getId(), safeOneLine200(r1.text));
                        // 讓流程往下走 repair
                    } else {
                        ObjectNode effective = normalizeToEffective(parsed1);
                        applyWholePackageScalingIfNeeded(parsed1, effective);
                        telemetry.ok("GEMINI", modelId, entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
                        return new ProviderResult(effective, "GEMINI");
                    }
                } else {
                    log.warn("label_unusable_main_will_repair foodLogId={} preview={}",
                            entity.getId(), safeOneLine200(r1.text));
                }

                // ✅ LABEL：只有 parse 不出 JSON 才允許 salvage（不增加 API 次數）
                if (parsed1 == null) {

                    ObjectNode fromBrokenJson = tryExtractFromBrokenJsonLikeText(r1.text);
                    if (fromBrokenJson != null) {
                        ObjectNode effective = normalizeToEffective(fromBrokenJson);
                        applyWholePackageScalingIfNeeded(fromBrokenJson, effective);
                        telemetry.ok("GEMINI", modelId, entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
                        return new ProviderResult(effective, "GEMINI");
                    }

                    ObjectNode fromText = tryExtractLabelFromPlainText(r1.text);
                    if (fromText != null) {
                        ObjectNode effective = normalizeToEffective(fromText);
                        applyWholePackageScalingIfNeeded(fromText, effective);
                        telemetry.ok("GEMINI", modelId, entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
                        return new ProviderResult(effective, "GEMINI");
                    }
                }

                // ✅ LABEL：no-label（只處理模型回文字）
                if (isNoLabelLikely(r1.text)) {
                    ObjectNode fb = fallbackNoLabelDetected();
                    ObjectNode effective = normalizeToEffective(fb);
                    telemetry.ok("GEMINI", modelId, entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
                    return new ProviderResult(effective, "GEMINI");
                }
            }

            // ✅ Repair 改成 TEXT-ONLY：不再重送圖片（大幅省 tokens）
            // LABEL：只有在「完全 parse 不出來」才做 text-only repair（避免 text-only 去猜營養）
            final boolean allowTextRepair = (!isLabel) || (parsed1 == null && r1.text != null && !r1.text.isBlank());
            final int maxRepair = allowTextRepair
                    ? (isLabel ? MAX_LABEL_REPAIR_CALLS : MAX_UNKNOWN_FOOD_REPAIR_CALLS)
                    : 0;

            final String modelIdText = (maxRepair > 0) ? resolveModelIdText(entity) : null;

            String lastText = r1.text;

            // ===== 2) repair loop (TEXT-ONLY) =====
            for (int i = 1; i <= maxRepair; i++) {

                String repairPromptText = buildTextOnlyRepairPrompt(isLabel, lastText);
                CallResult rn = callAndExtractTextOnly(repairPromptText, modelIdText, entity.getId());

                tok = Tok.mergePreferNew(tok, rn.tok);
                lastText = rn.text;

                JsonNode parsedN = tryParseJson(rn.text);
                parsedN = unwrapRootObjectOrNull(parsedN);

                // ✅ LABEL：每次都更新 best（僅修 JSON 的話通常不會補更多值，但能救回截斷/壞 JSON）
                if (isLabel) best = best.consider(parsedN);

                if (isLabel && parsedN != null && hasWarning(parsedN, FoodLogWarning.NO_LABEL_DETECTED.name())) {
                    ObjectNode effective = normalizeToEffective(parsedN);
                    applyWholePackageScalingIfNeeded(parsedN, effective);
                    telemetry.ok("GEMINI", modelId, entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
                    return new ProviderResult(effective, "GEMINI");
                }

                if (hasAnyNutrientValue(parsedN)) {
                    if (isLabel && isLabelIncomplete(parsedN)) {
                        log.warn("label_incomplete_after_text_repair foodLogId={} preview={}",
                                entity.getId(), safeOneLine200(rn.text));
                        // 不 return，讓 loop 結束後走 final fallback（best / partial）
                    } else {
                        ObjectNode effective = normalizeToEffective(parsedN);
                        if (isLabel) applyWholePackageScalingIfNeeded(parsedN, effective);
                        telemetry.ok("GEMINI", modelId, entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
                        return new ProviderResult(effective, "GEMINI");
                    }
                }

                if (isLabel) {
                    // text-only repair 後仍可嘗試 plain-text salvage（保底）
                    ObjectNode fromText = tryExtractLabelFromPlainText(rn.text);
                    if (fromText != null) {
                        ObjectNode effective = normalizeToEffective(fromText);
                        applyWholePackageScalingIfNeeded(fromText, effective);
                        telemetry.ok("GEMINI", modelId, entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
                        return new ProviderResult(effective, "GEMINI");
                    }
                    if (isNoLabelLikely(rn.text)) {
                        ObjectNode fb = fallbackNoLabelDetected();
                        ObjectNode effective = normalizeToEffective(fb);
                        telemetry.ok("GEMINI", modelId, entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
                        return new ProviderResult(effective, "GEMINI");
                    }
                } else {
                    // Photo/Album：no-food 永遠不重打 vision，text-only repair 也照樣接受 no-food
                    if (isNoFoodLikely(rn.text)) {
                        ObjectNode fb = fallbackNoFoodDetected();
                        ObjectNode effective = normalizeToEffective(fb);
                        telemetry.ok("GEMINI", modelId, entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
                        return new ProviderResult(effective, "GEMINI");
                    }
                }
            }

            // ===== 3) final fallback（✅ LABEL 不再 throw）=====
            if (isLabel) {

                // 1) 如果有 best（即使不完整），就回 best + PARTIAL/LOW_CONFIDENCE
                if (best.raw != null) {
                    // 不完整就標記 partial
                    if (!best.complete) {
                        addWarningIfMissing(best.raw, FoodLogWarning.LABEL_PARTIAL);
                        addWarningIfMissing(best.raw, FoodLogWarning.LOW_CONFIDENCE);
                        // 信心值保守
                        if (best.raw.path("confidence").isNull()) best.raw.put("confidence", 0.25);
                    }

                    ObjectNode effective = normalizeToEffective(best.raw);
                    applyWholePackageScalingIfNeeded(best.raw, effective);
                    telemetry.ok("GEMINI", modelId, entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
                    return new ProviderResult(effective, "GEMINI");
                }

                // 2) 明顯是 JSON 開頭但被截斷 → 回 PARTIAL（避免誤判 NO_LABEL）
                if (looksLikeStartedJsonLabel(lastText) || (isJsonTruncated(lastText) && lastText.trim().startsWith("{"))) {
                    ObjectNode partial = fallbackLabelPartialDetected(lastText);
                    ObjectNode effective = normalizeToEffective(partial);
                    applyWholePackageScalingIfNeeded(partial, effective);
                    telemetry.ok("GEMINI", modelId, entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
                    return new ProviderResult(effective, "GEMINI");
                }

                // 3) 真的像 no-label 才回 NO_LABEL
                if (isNoLabelLikely(lastText)) {
                    ObjectNode fb = fallbackNoLabelDetected();
                    ObjectNode effective = normalizeToEffective(fb);
                    telemetry.ok("GEMINI", modelId, entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
                    return new ProviderResult(effective, "GEMINI");
                }

                // 4) 不確定時，保守回 NO_LABEL（避免亂 PARTIAL）
                ObjectNode fb = fallbackNoLabelDetected();
                ObjectNode effective = normalizeToEffective(fb);
                telemetry.ok("GEMINI", modelId, entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
                return new ProviderResult(effective, "GEMINI");
            }

            ObjectNode fbUnknown = fallbackUnknownFoodFromBrokenText(lastText);
            ObjectNode effective = normalizeToEffective(fbUnknown);
            telemetry.ok("GEMINI", modelId, entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
            return new ProviderResult(effective, "GEMINI");

        } catch (Exception e) {
            ProviderErrorMapper.Mapped mapped = ProviderErrorMapper.map(e);
            telemetry.fail("GEMINI", modelId, entity.getId(), msSince(t0), mapped.code(), mapped.retryAfterSec());
            throw e;
        }
    }

    private static boolean looksLikeStartedJsonLabel(String text) {
        if (text == null) return false;
        String s = text.trim().toLowerCase(Locale.ROOT);
        if (!s.startsWith("{")) return false;
        return s.contains("\"foodname\"") || s.contains("\"quantity\"") || s.contains("\"nutrients\"");
    }

    private ObjectNode fallbackLabelPartialDetected(String rawText) {
        ObjectNode root = om.createObjectNode();
        String name = extractJsonStringValue(rawText, "foodName");
        if (name == null || name.isBlank()) root.putNull("foodName");
        else root.put("foodName", name);

        ObjectNode q = root.putObject("quantity");
        q.put("value", 1d);
        q.put("unit", "SERVING");

        ObjectNode n = root.putObject("nutrients");
        n.putNull("kcal"); n.putNull("protein"); n.putNull("fat"); n.putNull("carbs");
        n.putNull("fiber"); n.putNull("sugar"); n.putNull("sodium");

        root.put("confidence", 0.15);

        ArrayNode w = root.putArray("warnings");
        w.add(FoodLogWarning.LABEL_PARTIAL.name());
        w.add(FoodLogWarning.LOW_CONFIDENCE.name());

        ObjectNode lm = root.putObject("labelMeta");
        lm.putNull("servingsPerContainer");
        lm.putNull("basis");

        return root;
    }

    private ObjectNode nutritionFnSchema(boolean isLabel) {
        // Gemini functionDeclaration.parameters 使用的是 Google Schema（非 JSON Schema）
        ObjectNode root = om.createObjectNode();
        root.put("type", "OBJECT");

        ObjectNode props = root.putObject("properties");

        // foodName: STRING|null
        props.set("foodName", sString(true));

        // quantity: OBJECT (required: value, unit)
        ObjectNode quantity = sObject(false);
        ObjectNode qProps = quantity.putObject("properties");
        qProps.set("value", sNumber(true)); // number|null
        qProps.set("unit", sEnumString(true, List.of("SERVING", "GRAM", "ML"))); // string|null + enum
        quantity.putArray("required").add("value").add("unit");
        props.set("quantity", quantity);

        // nutrients: OBJECT (all required keys exist, values nullable)
        ObjectNode nutrients = sObject(false);
        ObjectNode nProps = nutrients.putObject("properties");
        ArrayNode nReq = nutrients.putArray("required");
        for (String k : new String[]{"kcal","protein","fat","carbs","fiber","sugar","sodium"}) {
            nProps.set(k, sNumber(true)); // number|null
            nReq.add(k);
        }
        props.set("nutrients", nutrients);

        // confidence: NUMBER|null
        props.set("confidence", sNumber(true));

        // warnings: ARRAY of STRING（✅ 移除 enum 清單，減少 prompt tokens）
        ObjectNode warnings = sArray(false, sString(false));
        props.set("warnings", warnings);

        // labelMeta: LABEL 才要求（你現在 useFn 只在 LABEL）
        ObjectNode labelMeta = sObject(true); // object|null
        ObjectNode lmProps = labelMeta.putObject("properties");
        lmProps.set("servingsPerContainer", sNumber(true));
        lmProps.set("basis", sEnumString(true, List.of("PER_SERVING", "WHOLE_PACKAGE")));
        // 如果你希望 labelMeta 兩欄位都必出現（允許 null），就加 required：
        labelMeta.putArray("required").add("servingsPerContainer").add("basis");
        props.set("labelMeta", labelMeta);

        // required keys（你要「根治」就把 warnings/labelMeta 都要求）
        ArrayNode req = root.putArray("required");
        req.add("foodName");
        req.add("quantity");
        req.add("nutrients");
        req.add("confidence");

        // LABEL：你想更嚴格就加上
        if (isLabel) {
            req.add("warnings");
            req.add("labelMeta");
        }

        return root;
    }

    private List<String> enumFoodLogWarnings() {
        // 直接把 enum 展開成 List<String>
        // （你也可以 cache 起來）
        return java.util.Arrays.stream(FoodLogWarning.values())
                .map(Enum::name)
                .toList();
    }

    /** ===== helpers (Gemini Schema) ===== */
    private ObjectNode sObject(boolean nullable) {
        ObjectNode o = om.createObjectNode();
        o.put("type", "OBJECT");
        if (nullable) o.put("nullable", true);
        return o;
    }

    private ObjectNode sString(boolean nullable) {
        ObjectNode o = om.createObjectNode();
        o.put("type", "STRING");
        if (nullable) o.put("nullable", true);
        return o;
    }

    private ObjectNode sNumber(boolean nullable) {
        ObjectNode o = om.createObjectNode();
        o.put("type", "NUMBER");
        if (nullable) o.put("nullable", true);
        return o;
    }

    private ObjectNode sArray(boolean nullable, ObjectNode itemsSchema) {
        ObjectNode o = om.createObjectNode();
        o.put("type", "ARRAY");
        if (nullable) o.put("nullable", true);
        o.set("items", itemsSchema);
        return o;
    }

    private ObjectNode sEnumString(boolean nullable, List<String> enums) {
        ObjectNode o = sString(nullable);
        ArrayNode arr = o.putArray("enum");
        for (String e : enums) arr.add(e);
        return o;
    }

    // ===== call Gemini (包含拒答轉 ModelRefusedException) =====
    private CallResult callAndExtract(byte[] imageBytes, String mimeType, String userPrompt,
                                      String modelId, boolean isLabel, String foodLogIdForLog) {
        JsonNode resp;
        try {
            resp = callGenerateContent(imageBytes, mimeType, userPrompt, modelId, isLabel);
        } catch (RestClientResponseException re) {
            ProviderErrorMapper.Mapped mapped = ProviderErrorMapper.map(re);
            ProviderRefuseReason reason = ProviderRefuseReason.fromErrorCodeOrNull(mapped.code());
            if (reason != null) {
                log.warn("gemini_refused_http foodLogId={} modelId={} reason={} code={}",
                        foodLogIdForLog, modelId, reason, mapped.code());
                throw new ModelRefusedException(reason, mapped.code());
            }
            throw re;
        }

        ProviderRefuseReason reason = GeminiRefusalDetector.detectOrNull(resp);
        if (reason != null) {
            String code = "PROVIDER_REFUSED_" + reason.name();
            log.warn("gemini_refused foodLogId={} modelId={} reason={} code={}",
                    foodLogIdForLog, modelId, reason, code);
            throw new ModelRefusedException(reason, code);
        }

        Tok tok = extractUsage(resp);

        // ✅ 先拿 functionCall.args（根治：不走文字 parse）
        JsonNode fnArgs = extractFunctionArgsOrNull(resp);

        String text = extractJoinedTextOrNull(resp);
        if (text == null) text = "";

        if (fnArgs != null) {
            log.debug("geminiFnArgs foodLogId={} modelId={} keys={}",
                    foodLogIdForLog, modelId, fnArgs.fieldNames().hasNext() ? "HAS_FIELDS" : "EMPTY");
        } else {
            log.debug("geminiTextPreview foodLogId={} modelId={} preview={}",
                    foodLogIdForLog, modelId, safeOneLine200(text));
        }

        return new CallResult(tok, text, fnArgs);
    }

    private JsonNode callGenerateContent(byte[] imageBytes, String mimeType, String userPrompt, String modelId, boolean isLabel) {
        ObjectNode req = buildRequest(imageBytes, mimeType, userPrompt, isLabel);
        return http.post()
                .uri("/v1beta/models/{model}:generateContent", modelId)
                .header("x-goog-api-key", requireApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(req)
                .retrieve()
                .body(JsonNode.class);
    }

    // ==============================
// ✅ TEXT-ONLY repair（不重送圖片）
// ==============================

    private String resolveModelIdText(FoodLogEntity entity) {
        ModelTier tier = resolveTierFromDegradeLevel(entity.getDegradeLevel());
        AiModelRouter.Resolved r = modelRouter.resolveOrThrow(tier, ModelMode.TEXT);
        return r.modelId();
    }

    private CallResult callAndExtractTextOnly(String userPrompt, String modelId, String foodLogIdForLog) {
        JsonNode resp;
        try {
            resp = callGenerateContentTextOnly(userPrompt, modelId);
        } catch (RestClientResponseException re) {
            ProviderErrorMapper.Mapped mapped = ProviderErrorMapper.map(re);
            ProviderRefuseReason reason = ProviderRefuseReason.fromErrorCodeOrNull(mapped.code());
            if (reason != null) {
                log.warn("gemini_refused_http(textOnly) foodLogId={} modelId={} reason={} code={}",
                        foodLogIdForLog, modelId, reason, mapped.code());
                throw new ModelRefusedException(reason, mapped.code());
            }
            throw re;
        }

        ProviderRefuseReason reason = GeminiRefusalDetector.detectOrNull(resp);
        if (reason != null) {
            String code = "PROVIDER_REFUSED_" + reason.name();
            log.warn("gemini_refused(textOnly) foodLogId={} modelId={} reason={} code={}",
                    foodLogIdForLog, modelId, reason, code);
            throw new ModelRefusedException(reason, code);
        }

        Tok tok = extractUsage(resp);

        String text = extractJoinedTextOrNull(resp);
        if (text == null) text = "";

        log.debug("geminiTextOnlyPreview foodLogId={} modelId={} preview={}",
                foodLogIdForLog, modelId, safeOneLine200(text));

        return new CallResult(tok, text, null);
    }

    private JsonNode callGenerateContentTextOnly(String userPrompt, String modelId) {
        ObjectNode req = buildTextOnlyRequest(userPrompt);
        return http.post()
                .uri("/v1beta/models/{model}:generateContent", modelId)
                .header("x-goog-api-key", requireApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(req)
                .retrieve()
                .body(JsonNode.class);
    }

    private ObjectNode buildTextOnlyRequest(String userPrompt) {
        ObjectNode root = om.createObjectNode();

        ObjectNode sys = root.putObject("systemInstruction");
        sys.putArray("parts").addObject().put("text",
                "You are a JSON repair engine. Return ONLY ONE minified JSON object. No markdown. No extra text.");

        ArrayNode contents = root.putArray("contents");
        ObjectNode c0 = contents.addObject();
        c0.put("role", "user");
        c0.putArray("parts").addObject().put("text", userPrompt);

        ObjectNode gen = root.putObject("generationConfig");
        gen.put("responseMimeType", "application/json"); // ✅ 強制 JSON（很省）
        gen.put("maxOutputTokens", 768);
        gen.put("temperature", 0.0);

        return root;
    }

    private static String buildTextOnlyRepairPrompt(boolean isLabel, String previousOutput) {
        String prev = truncateForPrompt(previousOutput, 4000); // ✅ 防止 previousOutput 太肥吃爆 tokens

        if (isLabel) {
            // LABEL：text-only repair 只能「修 JSON」，不能猜 unseen nutrients
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

        // Photo/Album：允許估算，但 repair 的目標仍是「把輸出修成合法 JSON」
        return """
            Fix the PREVIOUS_OUTPUT into ONE valid minified JSON object that matches the schema.
            Rules:
            - Return ONLY JSON. No markdown. No extra keys.
            - You MUST output ALL required keys:
              foodName, quantity{value,unit}, nutrients{kcal,protein,fat,carbs,fiber,sugar,sodium},
              confidence, warnings
            - If a key/value is missing or broken, set it to null (or reasonable defaults: quantity.value=1, unit="SERVING").
            - Do NOT re-analyze image; only repair/normalize JSON format.

            PREVIOUS_OUTPUT:
            %s
            """.formatted(prev);
    }

    private static String truncateForPrompt(String s, int maxChars) {
        if (s == null) return "";
        String t = s.trim();
        if (t.length() <= maxChars) return t;
        return t.substring(0, maxChars);
    }

    private ObjectNode buildRequest(byte[] imageBytes, String mimeType, String userPrompt, boolean isLabel) {
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        ObjectNode root = om.createObjectNode();
        // systemInstruction：useFn 時不要要求 "application/json" 的文字輸出，改成要求「只呼叫函式」
        ObjectNode sys = root.putObject("systemInstruction");
        String sysText = isLabel
                ? ("You MUST call function " + FN_EMIT_NUTRITION + " with arguments only. "
                   + "Do NOT output any text.")
                : "Return ONLY minified JSON. No markdown. No extra text.";
        sys.putArray("parts").addObject().put("text", sysText);

        ArrayNode contents = root.putArray("contents");
        ObjectNode c0 = contents.addObject();
        c0.put("role", "user");
        ArrayNode parts = c0.putArray("parts");
        parts.addObject().put("text", userPrompt);

        ObjectNode imgPart = parts.addObject();
        ObjectNode inline = imgPart.putObject("inlineData");
        inline.put("mimeType", mimeType);
        inline.put("data", b64);

        if (isLabel) {
            ArrayNode tools = root.putArray("tools");
            ObjectNode tool0 = tools.addObject();
            ArrayNode fns = tool0.putArray("functionDeclarations");

            ObjectNode fn = fns.addObject();
            fn.put("name", FN_EMIT_NUTRITION);
            fn.put("description", "Return nutrition JSON strictly matching the schema.");
            fn.set("parameters", nutritionFnSchema(true));

            ObjectNode toolConfig = root.putObject("toolConfig");
            ObjectNode fcc = toolConfig.putObject("functionCallingConfig");
            fcc.put("mode", "ANY"); // ✅ 強制走 functionCall
            fcc.putArray("allowedFunctionNames").add(FN_EMIT_NUTRITION);
        }

        ObjectNode gen = root.putObject("generationConfig");

        // ✅ 重要：useFn=true 時，不能再送 responseMimeType=application/json（否則你現在的 400）
        // 也不能送 responseSchema / _responseJsonSchema（文件寫明需要 responseMimeType）:contentReference[oaicite:5]{index=5}
        if (!isLabel) {
            gen.put("responseMimeType", "application/json");
            gen.set("_responseJsonSchema", nutritionJsonSchema());
        }

        gen.put("maxOutputTokens", isLabel ? 1024 : props.getMaxOutputTokens());
        gen.put("temperature", isLabel ? 0.0 : props.getTemperature());

        return root;
    }

    private static JsonNode extractFunctionArgsOrNull(JsonNode resp) {
        if (resp == null || resp.isNull()) return null;

        JsonNode parts = resp.path("candidates").path(0).path("content").path("parts");
        if (!parts.isArray()) return null;

        for (JsonNode p : parts) {
            JsonNode fc = p.get("functionCall");
            if (fc == null || !fc.isObject()) continue;

            String name = fc.path("name").asText(null);
            if (name == null || name.isBlank()) continue;

            JsonNode args = fc.get("args");
            if (args != null && args.isObject()) return args;
        }
        return null;
    }

    private ObjectNode nutritionJsonSchemaLabelStrict() {
        ObjectNode schema = nutritionJsonSchema();

        ArrayNode req = (ArrayNode) schema.get("required");
        if (req != null) {
            // 避免重複 add
            if (!contains(req, "warnings")) req.add("warnings");
            if (!contains(req, "labelMeta")) req.add("labelMeta");
        }

        ObjectNode propsNode = (ObjectNode) schema.get("properties");
        ObjectNode labelMeta = (ObjectNode) propsNode.get("labelMeta");

        // labelMeta required：兩個欄位必須出現（允許 null）
        ArrayNode lmReq = labelMeta.putArray("required");
        lmReq.add("servingsPerContainer");
        lmReq.add("basis");

        return schema;
    }

    private static boolean contains(ArrayNode arr, String s) {
        for (JsonNode it : arr) if (it != null && s.equals(it.asText())) return true;
        return false;
    }

    private ObjectNode nutritionJsonSchema() {
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);

        ObjectNode propsNode = schema.putObject("properties");

        ObjectNode labelMeta = propsNode.putObject("labelMeta");
        labelMeta.put("type", "object");
        labelMeta.put("additionalProperties", false);

        ObjectNode lmProps = labelMeta.putObject("properties");
        lmProps.putObject("servingsPerContainer").putArray("type").add("number").add("null");

        ObjectNode basis = lmProps.putObject("basis");
        basis.putArray("type").add("string").add("null");
        basis.putArray("enum").add("PER_SERVING").add("WHOLE_PACKAGE");

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
        items.put("type", "string"); // ✅ 移除 enum 清單，減少 prompt tokens

        schema.putArray("required")
                .add("foodName")
                .add("quantity")
                .add("nutrients")
                .add("confidence");

        return schema;
    }

    // ===== Step 2：依 degradeLevel 決定 tier =====
    private static ModelTier resolveTierFromDegradeLevel(String degradeLevel) {
        if (degradeLevel == null) return ModelTier.MODEL_TIER_LOW;
        String v = degradeLevel.trim().toUpperCase(Locale.ROOT);
        return v.startsWith("DG-0")
                ? ModelTier.MODEL_TIER_HIGH
                : ModelTier.MODEL_TIER_LOW;
    }

    private String resolveModelIdVision(FoodLogEntity entity) {
        ModelTier tier = resolveTierFromDegradeLevel(entity.getDegradeLevel());
        AiModelRouter.Resolved r = modelRouter.resolveOrThrow(tier, ModelMode.VISION);
        return r.modelId();
    }

    // ===== parse + normalize（以下保持你原本邏輯）=====
    private JsonNode tryParseJson(String rawText) {
        if (rawText == null) return null;

        String s = stripFence(rawText.trim());
        if (s.isBlank()) return null;

        s = stripBomAndNulls(s);
        s = escapeBadCharsInsideJsonStrings(s);

        String payload = extractFirstJsonPayload(s);
        if (payload == null || payload.isBlank()) return null;

        payload = patchDanglingTail(payload);
        payload = balanceJsonIfNeeded(payload);
        payload = removeTrailingCommas(payload);
        payload = patchDanglingTail(payload);

        try {
            return om.readTree(payload);
        } catch (Exception first) {
            String fixed = balanceJsonIfNeeded(payload);
            fixed = removeTrailingCommas(fixed);
            fixed = patchDanglingTail(fixed);
            try {
                return om.readTree(fixed);
            } catch (Exception second) {
                return null;
            }
        }
    }

    private static String patchDanglingTail(String s) {
        if (s == null) return null;
        String t = rtrim(s);

        if (t.endsWith(":")) {
            String key = extractLastJsonKeyBeforeColon(t);
            if (key != null) {
                String lower = key.toLowerCase(Locale.ROOT);
                if (lower.equals("unit")) {
                    t = t + "\"SERVING\"";
                } else if (lower.equals("basis")) {
                    t = t + "\"PER_SERVING\"";
                } else {
                    t = t + "null";
                }
            } else {
                t = t + "null";
            }
        }
        return t;
    }

    private static String rtrim(String s) {
        int i = s.length();
        while (i > 0 && Character.isWhitespace(s.charAt(i - 1))) i--;
        return s.substring(0, i);
    }

    private static String extractLastJsonKeyBeforeColon(String t) {
        int colon = t.length() - 1;
        int q2 = t.lastIndexOf('"', colon - 1);
        if (q2 < 0) return null;
        int q1 = t.lastIndexOf('"', q2 - 1);
        if (q1 < 0) return null;
        return t.substring(q1 + 1, q2);
    }

    private ObjectNode normalizeToEffective(JsonNode raw) {
        raw = unwrapRootObjectOrNull(raw);
        if (raw == null || !raw.isObject()) throw new IllegalStateException("PROVIDER_BAD_RESPONSE");

        ObjectNode out = JsonNodeFactory.instance.objectNode();

        JsonNode foodNameNode = raw.get("foodName");
        if (foodNameNode != null && !foodNameNode.isNull()) {
            String foodName = foodNameNode.asText(null);
            if (foodName != null && !foodName.isBlank()) out.put("foodName", foodName);
        }

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

        Double conf = parseNumberNodeOrText(raw.get("confidence"));
        conf = normalizeConfidence(conf);
        if (conf == null) out.putNull("confidence");
        else out.put("confidence", conf);

        ArrayNode outW = JsonNodeFactory.instance.arrayNode();
        JsonNode w = raw.get("warnings");
        if (w != null && w.isArray()) {
            for (JsonNode it : w) {
                if (it == null || it.isNull()) continue;
                FoodLogWarning ww = FoodLogWarning.parseOrNull(it.asText());
                if (ww != null) outW.add(ww.name());
            }
        }
        out.set("warnings", outW);

        // labelMeta（保持：raw 有就留給 applyWholePackageScalingIfNeeded 用）
        if (raw.has("labelMeta")) {
            out.set("labelMeta", raw.get("labelMeta"));
        }
        return out;
    }

    private static void copyNonNegativeNumberOrNull(JsonNode from, ObjectNode to, String key) {
        JsonNode v = from.get(key);
        if (v == null || v.isMissingNode() || v.isNull()) {
            to.putNull(key);
            return;
        }

        Double d = null;
        String unit = null;

        if (v.isNumber()) {
            d = v.asDouble();
        } else if (v.isTextual()) {
            String raw = v.asText("").trim();
            if (!raw.isEmpty()) {
                d = parseFirstNumber(raw);
                unit = raw;
            }
        } else if (v.isObject()) {
            JsonNode vv = v.get("value");
            if (vv != null) {
                if (vv.isNumber()) d = vv.asDouble();
                else if (vv.isTextual()) d = parseFirstNumber(vv.asText("").trim());
            }
            JsonNode uu = v.get("unit");
            if (uu == null) uu = v.get("uom");
            if (uu != null && uu.isTextual()) unit = uu.asText();
        } else {
            to.putNull(key);
            return;
        }

        if (d == null || d < 0) {
            to.putNull(key);
            return;
        }

        String u = (unit == null) ? "" : unit.toLowerCase(Locale.ROOT);

        if ("kcal".equals(key)) {
            if (u.contains("kj") && !u.contains("kcal")) d = d / 4.184d;
        }

        if ("sodium".equals(key)) {
            if (u.contains(" g") || u.endsWith("g") || u.equals("g")) {
                if (!u.contains("mg")) d = d * 1000d;
            }
        } else if (!"kcal".equals(key)) {
            if (u.contains("mg")) d = d / 1000d;
        }

        to.put(key, d);
    }

    private static boolean hasAnyNutrientValue(JsonNode root) {
        root = unwrapRootObjectOrNull(root);
        return root != null && !isAllNutrientsNull(root);
    }

    private static boolean hasWarning(JsonNode root, String code) {
        root = unwrapRootObjectOrNull(root);
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

    // ===== JSON truncation detection + fix (保持你原本) =====
    private static boolean isJsonTruncated(String text) {
        if (text == null || text.isBlank()) return false;

        String trimmed = text.trim();
        if (!trimmed.startsWith("{")) return false;

        int balance = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (inString) {
                if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }

            if (ch == '"') {
                inString = true;
            } else if (ch == '{') {
                balance++;
            } else if (ch == '}') {
                balance--;
            }
        }

        return balance > 0 || !trimmed.endsWith("}");
    }

    private ObjectNode tryFixTruncatedJson(String rawText) {
        if (rawText == null || rawText.trim().isEmpty()) return null;

        String text = rawText.trim();
        log.debug("tryFixTruncatedJson input: {}", text.substring(0, Math.min(text.length(), 500)));

        String fixed = fixCommonTruncationPatterns(text);

        try {
            JsonNode parsed = om.readTree(fixed);
            parsed = unwrapRootObjectOrNull(parsed);

            if (parsed != null && parsed.isObject()) {
                ObjectNode obj = (ObjectNode) parsed;

                if (!obj.has("nutrients") || !obj.get("nutrients").isObject()) {
                    ObjectNode nutrients = om.createObjectNode();
                    nutrients.putNull("kcal");
                    nutrients.putNull("protein");
                    nutrients.putNull("fat");
                    nutrients.putNull("carbs");
                    nutrients.putNull("fiber");
                    nutrients.putNull("sugar");
                    nutrients.putNull("sodium");
                    obj.set("nutrients", nutrients);
                }

                if (!obj.has("confidence")) obj.put("confidence", 0.25);

                if (!obj.has("quantity") || !obj.get("quantity").isObject()) {
                    ObjectNode quantity = om.createObjectNode();
                    quantity.put("value", 1.0);
                    quantity.put("unit", "SERVING");
                    obj.set("quantity", quantity);
                }

                if (!obj.has("warnings") || !obj.get("warnings").isArray()) {
                    obj.set("warnings", om.createArrayNode().add(FoodLogWarning.LOW_CONFIDENCE.name()));
                }

                if (!obj.has("labelMeta")) {
                    ObjectNode lm = om.createObjectNode();
                    lm.putNull("servingsPerContainer");
                    lm.putNull("basis");
                    obj.set("labelMeta", lm);
                }

                return obj;
            }
        } catch (Exception e) {
            log.debug("tryFixTruncatedJson parse failed: {}", e.getMessage());
        }

        return extractFromTruncatedText(text);
    }

    private String fixCommonTruncationPatterns(String text) {
        if (text == null || text.isEmpty()) return text;

        StringBuilder fixed = new StringBuilder(text);

        int nutrientsStart = text.indexOf("\"nutrients\":{");
        if (nutrientsStart >= 0) {
            int nutrientsOpen = nutrientsStart + "\"nutrients\":{".length();
            int nutrientsClose = findMatchingBrace(text, nutrientsOpen - 1);

            if (nutrientsClose < 0) {
                Double kcal = extractNumberAfter(text, "\"kcal\":");
                Double sodium = extractNumberAfter(text, "\"sodium\":");
                Double sodiumAlt = extractNumberAfter(text, "\"sodium\" :");

                if (sodium == null && sodiumAlt != null) sodium = sodiumAlt;

                StringBuilder nutrients = new StringBuilder();
                nutrients.append("\"nutrients\":{");
                nutrients.append("\"kcal\":").append(kcal != null ? kcal : "null");
                nutrients.append(",\"protein\":null");
                nutrients.append(",\"fat\":null");
                nutrients.append(",\"carbs\":null");
                nutrients.append(",\"fiber\":null");
                nutrients.append(",\"sugar\":null");
                nutrients.append(",\"sodium\":").append(sodium != null ? sodium : "null");
                nutrients.append("}");

                String beforeNutrients = text.substring(0, text.indexOf("\"nutrients\":{"));
                String afterNutrients = text.substring(text.indexOf("\"nutrients\":{") + "\"nutrients\":{".length());

                if (afterNutrients.contains("\"confidence\":")) {
                    return beforeNutrients + nutrients + afterNutrients.substring(afterNutrients.indexOf("\"confidence\":"));
                } else if (afterNutrients.contains("}")) {
                    int firstBrace = afterNutrients.indexOf('}');
                    return beforeNutrients + nutrients + afterNutrients.substring(firstBrace);
                } else {
                    return beforeNutrients + nutrients + ",\"confidence\":0.25,\"warnings\":[\"LOW_CONFIDENCE\"],\"labelMeta\":{\"servingsPerContainer\":null,\"basis\":null}}";
                }
            }
        }

        if (!text.endsWith("}")) {
            int openBraces = 0;
            int closeBraces = 0;
            boolean inString = false;
            boolean escaped = false;

            for (int i = 0; i < fixed.length(); i++) {
                char ch = fixed.charAt(i);
                if (escaped) { escaped = false; continue; }
                if (inString) {
                    if (ch == '\\') escaped = true;
                    else if (ch == '"') inString = false;
                    continue;
                }
                if (ch == '"') inString = true;
                else if (ch == '{') openBraces++;
                else if (ch == '}') closeBraces++;
            }

            if (openBraces > closeBraces) {
                int missing = openBraces - closeBraces;
                for (int i = 0; i < missing; i++) fixed.append('}');
            }
        }

        String result = removeTrailingCommas(fixed.toString());
        if (!result.endsWith("}")) result = result + "}";
        return result;
    }

    private ObjectNode extractFromTruncatedText(String text) {
        if (text == null || text.trim().isEmpty()) return null;

        ObjectNode root = om.createObjectNode();

        String foodName = extractJsonStringValue(text, "foodName");
        if (foodName != null && !foodName.isEmpty()) root.put("foodName", foodName);
        else root.putNull("foodName");

        ObjectNode quantity = om.createObjectNode();
        Double qtyValue = extractJsonNumberValue(text, "value");
        String qtyUnit = extractJsonStringValue(text, "unit");

        quantity.put("value", qtyValue != null ? qtyValue : 1.0);
        quantity.put("unit", (qtyUnit != null && !qtyUnit.isEmpty()) ? qtyUnit : "SERVING");
        root.set("quantity", quantity);

        ObjectNode nutrients = om.createObjectNode();
        Double kcal = extractJsonNumberValue(text, "kcal");
        Double sodium = extractJsonNumberValue(text, "sodium");
        if (sodium == null) sodium = extractSodiumFromText(text);

        if (kcal != null) nutrients.put("kcal", kcal); else nutrients.putNull("kcal");
        nutrients.putNull("protein");
        nutrients.putNull("fat");
        nutrients.putNull("carbs");
        nutrients.putNull("fiber");
        nutrients.putNull("sugar");
        if (sodium != null) nutrients.put("sodium", sodium); else nutrients.putNull("sodium");

        root.set("nutrients", nutrients);

        root.put("confidence", 0.25);
        ArrayNode warnings = root.putArray("warnings");
        warnings.add(FoodLogWarning.LOW_CONFIDENCE.name());

        ObjectNode lm = root.putObject("labelMeta");
        lm.putNull("servingsPerContainer");
        lm.putNull("basis");

        return root;
    }

    private String extractJsonStringValue(String text, String key) {
        if (text == null || key == null) return null;

        String[] patterns = {
                "\"" + key + "\":\"([^\"]*)\"",
                "\"" + key + "\"\\s*:\\s*\"([^\"]*)\"",
                "'" + key + "':\"([^\"]*)\"",
                "'" + key + "'\\s*:\\s*\"([^\"]*)\""
        };

        for (String pattern : patterns) {
            try {
                java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
                java.util.regex.Matcher m = p.matcher(text);
                if (m.find()) return m.group(1);
            } catch (Exception ignore) {}
        }
        return null;
    }

    private Double extractJsonNumberValue(String text, String key) {
        if (text == null || key == null) return null;

        String[] patterns = {
                "\"" + key + "\":\\s*(-?\\d+(?:\\.\\d+)?)",
                "\"" + key + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)",
                "'" + key + "':\\s*(-?\\d+(?:\\.\\d+)?)",
                "'" + key + "'\\s*:\\s*(-?\\d+(?:\\.\\d+)?)"
        };

        for (String pattern : patterns) {
            try {
                java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
                java.util.regex.Matcher m = p.matcher(text);
                if (m.find()) return Double.parseDouble(m.group(1));
            } catch (Exception ignore) {}
        }
        return null;
    }

    private Double extractSodiumFromText(String text) {
        if (text == null) return null;

        String lowerText = text.toLowerCase(Locale.ROOT);
        String[] sodiumMarkers = {
                "\"sodium\":", "\"sodium\" :",
                "\"鈉\":", "\"鈊\":", "\"钠\":",
                "sodium", "鈉", "钠", "natrium"
        };

        for (String marker : sodiumMarkers) {
            int idx = lowerText.indexOf(marker.toLowerCase(Locale.ROOT));
            if (idx >= 0) {
                String afterMarker = text.substring(idx + marker.length());
                Double sodium = extractFirstNumber(afterMarker);
                if (sodium != null) return sodium;
            }
        }
        return null;
    }

    private Double extractFirstNumber(String text) {
        if (text == null || text.isEmpty()) return null;

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("-?\\d+(?:\\.\\d+)?");
        java.util.regex.Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            try { return Double.parseDouble(matcher.group()); }
            catch (NumberFormatException ignore) { return null; }
        }
        return null;
    }

    private Double extractNumberAfter(String text, String marker) {
        if (text == null || marker == null) return null;

        int idx = text.indexOf(marker);
        if (idx >= 0) {
            String afterMarker = text.substring(idx + marker.length());
            return extractFirstNumber(afterMarker);
        }
        return null;
    }

    private int findMatchingBrace(String text, int openBracePos) {
        if (text == null || openBracePos < 0 || openBracePos >= text.length()) return -1;

        int balance = 1;
        boolean inString = false;
        boolean escaped = false;

        for (int i = openBracePos + 1; i < text.length(); i++) {
            char ch = text.charAt(i);

            if (escaped) { escaped = false; continue; }

            if (inString) {
                if (ch == '\\') escaped = true;
                else if (ch == '"') inString = false;
                continue;
            }

            if (ch == '"') inString = true;
            else if (ch == '{') balance++;
            else if (ch == '}') {
                balance--;
                if (balance == 0) return i;
            }
        }
        return -1;
    }

    // ===== no-label 判斷（保持你原本）=====
    private static final java.util.regex.Pattern P_NUM =
            java.util.regex.Pattern.compile("(-?\\d+(?:[.,]\\d+)?)");

    private static boolean isNoLabelLikely(String rawText) {
        if (rawText == null) return false;
        String s = rawText.trim();
        if (s.isEmpty()) return false;

        String lower = s.toLowerCase(Locale.ROOT);

        if (P_NUM.matcher(lower).find()) return false;

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
                || lower.contains("營養") || lower.contains("能量") || lower.contains("熱量") || lower.contains("蛋白") || lower.contains("脂肪")
                || lower.contains("碳水") || lower.contains("糖") || lower.contains("鈉") || lower.contains("鹽");

        if (hasSignals) return false;

        return lower.contains("no label")
               || lower.contains("no nutrition label")
               || lower.contains("nutrition table not found")
               || lower.contains("not a label")
               || lower.contains("cannot read")
               || lower.contains("unable to read")
               || lower.contains("cannot extract")
               || lower.contains("unable to extract")
               || lower.contains("too blurry")
               || lower.contains("blurry")
               || lower.contains("too small")
               || lower.contains("glare")
               || lower.contains("reflection")
               || lower.contains("no text detected")
               || lower.contains("無法辨識")
               || lower.contains("無法識別")
               || lower.contains("無法讀取")
               || lower.contains("看不清")
               || lower.contains("太模糊")
               || lower.contains("反光")
               || lower.contains("太小")
               || lower.contains("無法擷取")
               || lower.contains("無法提取");
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
        ObjectNode lm = root.putObject("labelMeta");
        lm.putNull("servingsPerContainer");
        lm.putNull("basis");
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

    // ===== salvage from broken text / plain text (保持你原本) =====

    private static final java.util.regex.Pattern P_JSON_KV_STRING =
            java.util.regex.Pattern.compile("\"%s\"\\s*:\\s*\"([^\"]*)\"");
    private static final java.util.regex.Pattern P_JSON_KV_NUMBER =
            java.util.regex.Pattern.compile("\"%s\"\\s*:\\s*(-?\\d+(?:[.,]\\d+)?)");

    private ObjectNode tryExtractFromBrokenJsonLikeText(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.replace('\r', ' ').replace('\n', ' ');

        String name = findJsonStringValue(s, "foodName");
        Double kcal = findJsonNumberValue(s, "kcal");
        Double sodium = findJsonNumberValue(s, "sodium");

        if (kcal == null && sodium == null) return null;

        ObjectNode root = om.createObjectNode();
        if (name == null || name.isBlank()) root.putNull("foodName");
        else root.put("foodName", name);

        ObjectNode q = root.putObject("quantity");
        q.put("value", 1d);
        q.put("unit", "SERVING");

        ObjectNode n = root.putObject("nutrients");
        putOrNull(n, "kcal", kcal);
        n.putNull("protein");
        n.putNull("fat");
        n.putNull("carbs");
        n.putNull("fiber");
        n.putNull("sugar");
        putOrNull(n, "sodium", sodium);

        root.put("confidence", 0.25);
        root.putArray("warnings").add(FoodLogWarning.LOW_CONFIDENCE.name());

        ObjectNode lm = root.putObject("labelMeta");
        lm.putNull("servingsPerContainer");
        lm.putNull("basis");

        return root;
    }

    private static String findJsonStringValue(String text, String key) {
        var p = java.util.regex.Pattern.compile(String.format(P_JSON_KV_STRING.pattern(), java.util.regex.Pattern.quote(key)));
        var m = p.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static Double findJsonNumberValue(String text, String key) {
        var p = java.util.regex.Pattern.compile(String.format(P_JSON_KV_NUMBER.pattern(), java.util.regex.Pattern.quote(key)));
        var m = p.matcher(text);
        if (!m.find()) return null;
        String v = m.group(1).replace(',', '.');
        try { return Double.parseDouble(v); } catch (Exception ignored) { return null; }
    }

    private ObjectNode tryExtractLabelFromPlainText(String raw) {
        if (raw == null) return null;
        String s = raw.replace('\r',' ').replace('\n',' ').trim();
        if (s.isEmpty()) return null;

        String lower = s.toLowerCase(Locale.ROOT);
        if (!P_NUM.matcher(lower).find()) return null;

        Double sodium = findNumberAfterAny(lower, new String[]{"sodium", "鈉", "natrium"});
        Double kcal   = findNumberAfterAny(lower, new String[]{"kcal", "calories", "熱量", "大卡", "能量"});
        Double servingMl = findNumberAfterAny(lower, new String[]{"serving size", "每一份量", "每份量", "每份"});
        Double servingsPerContainer = findNumberAfterAny(lower, new String[]{"servings per container", "本包裝含", "本包裝", "每包裝"});

        if (sodium == null && kcal == null) return null;

        ObjectNode root = om.createObjectNode();
        root.putNull("foodName");

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
        n.putNull("protein");
        n.putNull("fat");
        n.putNull("carbs");
        n.putNull("fiber");
        n.putNull("sugar");
        putOrNull(n, "sodium", sodium);

        root.put("confidence", 0.35);
        ArrayNode w = root.putArray("warnings");
        w.add(FoodLogWarning.LOW_CONFIDENCE.name());

        ObjectNode lm = root.putObject("labelMeta");
        if (servingsPerContainer != null && servingsPerContainer > 1) {
            lm.put("servingsPerContainer", servingsPerContainer);
            lm.put("basis", "PER_SERVING");
        } else {
            lm.putNull("servingsPerContainer");
            lm.putNull("basis");
        }

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

    private static boolean isLabelIncomplete(JsonNode root) {
        root = unwrapRootObjectOrNull(root);
        if (root == null || !root.isObject()) return true;

        JsonNode n = root.get("nutrients");
        if (n == null || !n.isObject()) return true;

        int nonNull = 0;
        JsonNode kcal = n.get("kcal");
        boolean kcalPresent = (kcal != null && !kcal.isNull());

        for (String k : new String[]{"kcal","protein","fat","carbs","fiber","sugar","sodium"}) {
            JsonNode v = n.get(k);
            if (v != null && !v.isNull()) nonNull++;
        }

        if (!kcalPresent) return true;
        if (nonNull <= 1) return true;

        boolean sodiumNull = (n.get("sodium") == null || n.get("sodium").isNull());
        boolean allMacrosNull =
                (n.get("protein") == null || n.get("protein").isNull()) &&
                (n.get("fat") == null || n.get("fat").isNull()) &&
                (n.get("carbs") == null || n.get("carbs").isNull()) &&
                (n.get("sugar") == null || n.get("sugar").isNull());

        return sodiumNull && allMacrosNull;
    }

    private static String removeTrailingCommas(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder out = new StringBuilder(s.length());
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (escaped) {
                out.append(ch);
                escaped = false;
                continue;
            }
            if (inString) {
                out.append(ch);
                if (ch == '\\') escaped = true;
                else if (ch == '"') inString = false;
                continue;
            }
            if (ch == '"') {
                inString = true;
                out.append(ch);
                continue;
            }
            if (ch == ',') {
                int j = i + 1;
                while (j < s.length() && Character.isWhitespace(s.charAt(j))) j++;
                if (j < s.length()) {
                    char nx = s.charAt(j);
                    if (nx == '}' || nx == ']') continue;
                }
            }
            out.append(ch);
        }
        return out.toString();
    }

    private static void applyWholePackageScalingIfNeeded(JsonNode raw, ObjectNode effective) {
        if (raw == null || effective == null) return;

        JsonNode lm = raw.get("labelMeta");
        if (lm == null || !lm.isObject()) return;

        Double servings = parseNumberNodeOrText(lm.get("servingsPerContainer"));
        if (servings == null || servings <= 1.0) return;

        String basis = (lm.get("basis") == null || lm.get("basis").isNull())
                ? null : lm.get("basis").asText(null);

        if (!"PER_SERVING".equalsIgnoreCase(basis)) return;

        JsonNode nNode = effective.get("nutrients");
        if (nNode == null || !nNode.isObject()) return;
        ObjectNode n = (ObjectNode) nNode;

        for (String k : new String[]{"kcal","protein","fat","carbs","fiber","sugar","sodium"}) {
            JsonNode v = n.get(k);
            if (v != null && v.isNumber()) n.put(k, v.asDouble() * servings);
        }

        JsonNode q = effective.get("quantity");
        if (q != null && q.isObject()) {
            ((ObjectNode) q).put("value", 1d);
            ((ObjectNode) q).put("unit", "SERVING");
        }
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

    private static Double parseFirstNumber(String raw) {
        if (raw == null) return null;
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

    private static Double normalizeConfidence(Double v) {
        if (v == null) return null;
        if (v >= 0.0 && v <= 1.0) return v;
        if (v > 1.0 && v <= 100.0) return v / 100.0;
        return null;
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

    private static JsonNode unwrapRootObjectOrNull(JsonNode raw) {
        if (raw == null || raw.isNull()) return null;

        if (raw.isArray()) {
            if (raw.size() == 0) return null;
            JsonNode first = raw.get(0);
            return (first != null && first.isObject()) ? first : null;
        }

        if (!raw.isObject()) return null;

        JsonNode r = raw.get("result");
        if (r != null && r.isObject()) return r;

        JsonNode d = raw.get("data");
        if (d != null && d.isObject()) return d;

        return raw;
    }

    private static String stripFence(String s) {
        if (s == null) return "";
        if (!s.contains("```")) return s.trim();

        int first = s.indexOf("```");
        int firstNl = s.indexOf('\n', first);
        if (firstNl > 0) s = s.substring(firstNl + 1);
        int lastFence = s.lastIndexOf("```");
        if (lastFence >= 0) s = s.substring(0, lastFence);
        return s.trim();
    }

    private static String extractFirstJsonPayload(String s) {
        if (s == null) return null;

        int iObj = s.indexOf('{');
        int iArr = s.indexOf('[');

        int start;
        if (iObj < 0 && iArr < 0) return null;
        if (iObj < 0) start = iArr;
        else if (iArr < 0) start = iObj;
        else start = Math.min(iObj, iArr);

        boolean inString = false;
        boolean escaped = false;

        int brace = 0;
        int bracket = 0;

        for (int i = start; i < s.length(); i++) {
            char ch = s.charAt(i);

            if (escaped) { escaped = false; continue; }

            if (inString) {
                if (ch == '\\') { escaped = true; continue; }
                if (ch == '"') { inString = false; }
                continue;
            } else {
                if (ch == '"') { inString = true; continue; }
                if (ch == '{') brace++;
                else if (ch == '}') brace--;
                else if (ch == '[') bracket++;
                else if (ch == ']') bracket--;

                if (brace == 0 && bracket == 0) {
                    return s.substring(start, i + 1).trim();
                }
            }
        }

        return s.substring(start).trim();
    }

    private static String balanceJsonIfNeeded(String s) {
        if (s == null || s.isBlank()) return s;

        boolean inString = false, escaped = false;
        Deque<Character> stack = new ArrayDeque<>();

        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);

            if (escaped) { escaped = false; continue; }

            if (inString) {
                if (ch == '\\') escaped = true;
                else if (ch == '"') inString = false;
                continue;
            }

            if (ch == '"') { inString = true; continue; }

            if (ch == '{' || ch == '[') {
                stack.push(ch);
            } else if (ch == '}' || ch == ']') {
                if (!stack.isEmpty()) {
                    char top = stack.peek();
                    if ((top == '{' && ch == '}') || (top == '[' && ch == ']')) stack.pop();
                }
            }
        }

        if (stack.isEmpty()) return s;

        StringBuilder out = new StringBuilder(s);
        while (!stack.isEmpty()) {
            char open = stack.pop();
            out.append(open == '{' ? '}' : ']');
        }
        return out.toString();
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

    private static String extractFoodNameFromBrokenTextFallback(String raw) {
        return extractFoodNameFromBrokenText(raw);
    }

    private record BestLabel(ObjectNode raw, int score, boolean complete) {
        static BestLabel empty() { return new BestLabel(null, -1, false); }

        BestLabel consider(JsonNode cand) {
            if (cand == null || !cand.isObject()) return this;
            ObjectNode obj = (ObjectNode) cand;

            ensureLabelRequiredKeys(obj);

            int sc = labelNutrientScore(obj);
            boolean comp = !isLabelIncomplete(obj); // 你原本的判斷直接沿用

            if (sc > this.score) return new BestLabel(obj.deepCopy(), sc, comp);
            return this;
        }
    }

    // ✅ nutrient score：非 null 越多分越高
    private static int labelNutrientScore(JsonNode root) {
        root = unwrapRootObjectOrNull(root);
        if (root == null || !root.isObject()) return -1;
        JsonNode n = root.get("nutrients");
        if (n == null || !n.isObject()) return -1;

        int nonNull = 0;
        for (String k : new String[]{"kcal","protein","fat","carbs","fiber","sugar","sodium"}) {
            JsonNode v = n.get(k);
            if (v != null && !v.isNull()) nonNull++;
        }
        return nonNull;
    }

    private static void ensureLabelRequiredKeys(ObjectNode obj) {
        if (obj == null) return;

        if (!obj.has("foodName")) obj.putNull("foodName");

        if (!obj.has("quantity") || !obj.get("quantity").isObject()) {
            ObjectNode q = JsonNodeFactory.instance.objectNode();
            q.put("value", 1d);
            q.put("unit", "SERVING");
            obj.set("quantity", q);
        } else {
            ObjectNode q = (ObjectNode) obj.get("quantity");
            if (!q.has("value")) q.put("value", 1d);
            if (!q.has("unit")) q.put("unit", "SERVING");
        }

        if (!obj.has("nutrients") || !obj.get("nutrients").isObject()) {
            ObjectNode n = JsonNodeFactory.instance.objectNode();
            n.putNull("kcal"); n.putNull("protein"); n.putNull("fat"); n.putNull("carbs");
            n.putNull("fiber"); n.putNull("sugar"); n.putNull("sodium");
            obj.set("nutrients", n);
        } else {
            ObjectNode n = (ObjectNode) obj.get("nutrients");
            for (String k : new String[]{"kcal","protein","fat","carbs","fiber","sugar","sodium"}) {
                if (!n.has(k)) n.putNull(k);
            }
        }

        if (!obj.has("confidence")) obj.putNull("confidence");

        if (!obj.has("warnings") || !obj.get("warnings").isArray()) {
            obj.set("warnings", JsonNodeFactory.instance.arrayNode());
        }

        if (!obj.has("labelMeta") || !obj.get("labelMeta").isObject()) {
            ObjectNode lm = JsonNodeFactory.instance.objectNode();
            lm.putNull("servingsPerContainer");
            lm.putNull("basis");
            obj.set("labelMeta", lm);
        } else {
            ObjectNode lm = (ObjectNode) obj.get("labelMeta");
            if (!lm.has("servingsPerContainer")) lm.putNull("servingsPerContainer");
            if (!lm.has("basis")) lm.putNull("basis");
        }
    }

    private static void addWarningIfMissing(ObjectNode raw, FoodLogWarning w) {
        if (raw == null || w == null) return;
        ArrayNode arr = raw.withArray("warnings");
        for (JsonNode it : arr) {
            if (it != null && !it.isNull() && w.name().equalsIgnoreCase(it.asText())) return;
        }
        arr.add(w.name());
    }

    // ===== 你原本 record =====
    private record CallResult(Tok tok, String text, JsonNode functionArgs) {}

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