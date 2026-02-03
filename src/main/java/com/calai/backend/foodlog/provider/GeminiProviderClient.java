package com.calai.backend.foodlog.provider;

import com.calai.backend.foodlog.config.AiModelRouter;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.mapper.ProviderErrorMapper;
import com.calai.backend.foodlog.model.ModelMode;
import com.calai.backend.foodlog.quota.model.ModelTier;
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
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.Locale;

@Slf4j
public class GeminiProviderClient implements ProviderClient {

    private static final int MAX_UNKNOWN_FOOD_REPAIR_CALLS = 1;
    private static final int MAX_LABEL_REPAIR_CALLS = 1; // ✅ NEW：LABEL 失敗時補 1 次 repair
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

            JsonNode parsed1 = tryParseJson(r1.text);
            parsed1 = unwrapRootObjectOrNull(parsed1);

            if (isLabel && parsed1 == null) {
                log.warn("label_parse_failed foodLogId={} preview={}", entity.getId(), safeOneLine200(r1.text));
            }

            // ✅ 新增：处理截断的 JSON（LABEL 模式）
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

            // ✅ LABEL：若已回 JSON 且含 NO_LABEL_DETECTED，直接接受（degraded DRAFT）
            if (isLabel && parsed1 != null && hasWarning(parsed1, FoodLogWarning.NO_LABEL_DETECTED.name())) {
                ObjectNode effective = normalizeToEffective(parsed1);
                applyWholePackageScalingIfNeeded(parsed1, effective);
                telemetry.ok("GEMINI", modelId, entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
                return new ProviderResult(effective, "GEMINI");
            }

            // ✅ Photo/Album：main call 只要有任何 nutrients 值就成功（維持你原本寬鬆規則）
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
                // ✅ LABEL：main call 成功條件必須更嚴格
                // 1) 要有 nutrients 任一非 null
                // 2) 且不能是 incomplete（例如只有 kcal）
                if (hasAnyNutrientValue(parsed1)) {
                    if (isLabelIncomplete(parsed1)) {
                        log.warn("label_incomplete_main_force_repair foodLogId={} preview={}",
                                entity.getId(), safeOneLine200(r1.text));
                        // 尝试修复截断的 JSON
                        if (isJsonTruncated(r1.text)) {
                            ObjectNode fromTruncated = tryFixTruncatedJson(r1.text);
                            if (fromTruncated != null && hasAnyNutrientValue(fromTruncated)) {
                                ObjectNode effective = normalizeToEffective(fromTruncated);
                                applyWholePackageScalingIfNeeded(fromTruncated, effective);
                                telemetry.ok("GEMINI", modelId, entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
                                return new ProviderResult(effective, "GEMINI");
                            }
                        }
                        // 不 return，讓流程往下走 repair
                    } else {
                        ObjectNode effective = normalizeToEffective(parsed1);
                        applyWholePackageScalingIfNeeded(parsed1, effective);
                        telemetry.ok("GEMINI", modelId, entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
                        return new ProviderResult(effective, "GEMINI");
                    }
                } else {
                    log.warn("label_unusable_main_will_repair foodLogId={} preview={}",
                            entity.getId(), safeOneLine200(r1.text));
                    // 不 return，讓流程往下走 salvage/repair
                }

                // ✅ LABEL：只有「parsed1 == null」（真的 parse 不出 JSON）才允許 salvage（不增加 API 次數）
                if (parsed1 == null) {

                    // 1) 先救「看起來像 JSON 但 parse 掛掉」：至少撈回 kcal/sodium 等數字（你已改成 kcal/sodium 都拿不到就 return null）
                    ObjectNode fromBrokenJson = tryExtractFromBrokenJsonLikeText(r1.text);
                    if (fromBrokenJson != null) {
                        ObjectNode effective = normalizeToEffective(fromBrokenJson);
                        applyWholePackageScalingIfNeeded(fromBrokenJson, effective);
                        telemetry.ok("GEMINI", modelId, entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
                        return new ProviderResult(effective, "GEMINI");
                    }

                    // 2) 再退回純文字抽取（例如模型回一段文字但含數字）
                    ObjectNode fromText = tryExtractLabelFromPlainText(r1.text);
                    if (fromText != null) {
                        ObjectNode effective = normalizeToEffective(fromText);
                        applyWholePackageScalingIfNeeded(fromText, effective);
                        telemetry.ok("GEMINI", modelId, entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
                        return new ProviderResult(effective, "GEMINI");
                    }
                }

                // ✅ LABEL：no-label（只處理模型回一段文字的情況）
                if (isNoLabelLikely(r1.text)) {
                    ObjectNode fb = fallbackNoLabelDetected();
                    ObjectNode effective = normalizeToEffective(fb);
                    telemetry.ok("GEMINI", modelId, entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
                    return new ProviderResult(effective, "GEMINI");
                }
            }

            // ✅ 改：LABEL 允許補 1 次 repair；Photo/Album 仍維持原本 max=1
            final int maxRepair = isLabel ? MAX_LABEL_REPAIR_CALLS : MAX_UNKNOWN_FOOD_REPAIR_CALLS;
            String lastText = r1.text;

            // ===== 2) repair loop（Photo/Album + LABEL(最多1次)）=====
            for (int i = 1; i <= maxRepair; i++) {
                CallResult rn = callAndExtract(bytes, mime, promptRepair, modelId, isLabel, entity.getId());
                tok = Tok.mergePreferNew(tok, rn.tok);
                lastText = rn.text;

                JsonNode parsedN = tryParseJson(rn.text);
                parsedN = unwrapRootObjectOrNull(parsedN);

                // ✅ 新增：修复循环中的截断 JSON
                if (isLabel && parsedN == null && isJsonTruncated(rn.text)) {
                    log.info("label_json_truncated_in_repair foodLogId={} attempt={}", entity.getId(), i);
                    ObjectNode fromTruncated = tryFixTruncatedJson(rn.text);
                    if (fromTruncated != null && hasAnyNutrientValue(fromTruncated)) {
                        ObjectNode effective = normalizeToEffective(fromTruncated);
                        applyWholePackageScalingIfNeeded(fromTruncated, effective);
                        telemetry.ok("GEMINI", modelId, entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
                        return new ProviderResult(effective, "GEMINI");
                    }
                }

                if (isLabel && parsedN != null && hasWarning(parsedN, FoodLogWarning.NO_LABEL_DETECTED.name())) {
                    ObjectNode effective = normalizeToEffective(parsedN);
                    applyWholePackageScalingIfNeeded(parsedN, effective);
                    telemetry.ok("GEMINI", modelId, entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
                    return new ProviderResult(effective, "GEMINI");
                }

                if (hasAnyNutrientValue(parsedN)) {
                    // ✅ LABEL：repair 後仍 incomplete 就不要成功（避免只補到 kcal）
                    if (isLabel && isLabelIncomplete(parsedN)) {
                        log.warn("label_incomplete_after_repair foodLogId={} preview={}",
                                entity.getId(), safeOneLine200(rn.text));
                        // 尝试修复截断的 JSON
                        if (isJsonTruncated(rn.text)) {
                            ObjectNode fromTruncated = tryFixTruncatedJson(rn.text);
                            if (fromTruncated != null && hasAnyNutrientValue(fromTruncated)) {
                                ObjectNode effective = normalizeToEffective(fromTruncated);
                                applyWholePackageScalingIfNeeded(fromTruncated, effective);
                                telemetry.ok("GEMINI", modelId, entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
                                return new ProviderResult(effective, "GEMINI");
                            }
                        }
                        // 不 return，讓 loop 結束後走 PROVIDER_BAD_RESPONSE
                    } else {
                        ObjectNode effective = normalizeToEffective(parsedN);
                        if (isLabel) applyWholePackageScalingIfNeeded(parsedN, effective);
                        telemetry.ok("GEMINI", modelId, entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
                        return new ProviderResult(effective, "GEMINI");
                    }
                }

                // ✅ LABEL：repair 若回的是「文字」，再嘗試一次文字抽取（不增加 API 次數）
                if (isLabel) {
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
                    // ✅ Photo/Album：repair 途中若判斷 no-food 直接回 degraded
                    if (isNoFoodLikely(rn.text)) {
                        ObjectNode fb = fallbackNoFoodDetected();
                        ObjectNode effective = normalizeToEffective(fb);
                        telemetry.ok("GEMINI", modelId, entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
                        return new ProviderResult(effective, "GEMINI");
                    }
                }
            }

            // ===== 3) final fallback =====
            if (isLabel) {
                // ✅ 新增：最后尝试从文字中提取（即使之前已经试过）
                if (isJsonTruncated(lastText)) {
                    log.info("label_final_attempt_truncated_fix foodLogId={}", entity.getId());
                    ObjectNode fromTruncated = tryFixTruncatedJson(lastText);
                    if (fromTruncated != null && hasAnyNutrientValue(fromTruncated)) {
                        ObjectNode effective = normalizeToEffective(fromTruncated);
                        applyWholePackageScalingIfNeeded(fromTruncated, effective);
                        telemetry.ok("GEMINI", modelId, entity.getId(), msSince(t0), tok.promptTok, tok.candTok, tok.totalTok);
                        return new ProviderResult(effective, "GEMINI");
                    }
                }

                // ✅ LABEL：到這裡還是不行 → 交給 worker attempts=2；最後 worker fallback NO_LABEL_DETECTED
                throw new IllegalStateException("PROVIDER_BAD_RESPONSE");
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

    /**
     * ✅ 判断 JSON 是否被截断（缺少结束大括号）
     */
    private static boolean isJsonTruncated(String text) {
        if (text == null || text.isBlank()) return false;

        String trimmed = text.trim();
        if (!trimmed.startsWith("{")) return false;

        // 统计大括号是否平衡
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

        // 如果大括号不平衡（通常是 balance > 0），说明 JSON 被截断
        // 或者最后不是以 } 结尾
        return balance > 0 || !trimmed.endsWith("}");
    }

    /**
     * ✅ 改進的JSON截斷修復方法
     */
    private ObjectNode tryFixTruncatedJson(String rawText) {
        if (rawText == null || rawText.trim().isEmpty()) {
            return null;
        }

        String text = rawText.trim();
        log.debug("tryFixTruncatedJson input: {}", text.substring(0, Math.min(text.length(), 500)));

        // 1. 先嘗試修復常見的截斷模式
        String fixed = fixCommonTruncationPatterns(text);

        // 2. 嘗試解析
        try {
            JsonNode parsed = om.readTree(fixed);
            parsed = unwrapRootObjectOrNull(parsed);

            if (parsed != null && parsed.isObject()) {
                // 確保有nutrients對象
                ObjectNode obj = (ObjectNode) parsed;
                if (!obj.has("nutrients") || !obj.get("nutrients").isObject()) {
                    // 創建nutrients對象
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

                // 確保有confidence
                if (!obj.has("confidence")) {
                    obj.put("confidence", 0.25);
                }

                // 確保有quantity
                if (!obj.has("quantity") || !obj.get("quantity").isObject()) {
                    ObjectNode quantity = om.createObjectNode();
                    quantity.put("value", 1.0);
                    quantity.put("unit", "SERVING");
                    obj.set("quantity", quantity);
                }

                return obj;
            }
        } catch (Exception e) {
            log.debug("tryFixTruncatedJson parse failed: {}", e.getMessage());
            // 繼續嘗試其他方法
        }

        // 3. 如果標準修復失敗，嘗試從文本中提取關鍵信息
        return extractFromTruncatedText(text);
    }

    /**
     * ✅ 修復常見的截斷模式
     */
    private String fixCommonTruncationPatterns(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        StringBuilder fixed = new StringBuilder(text);

        // 情況1: nutrients部分截斷，例如 "nutrients":{"kcal": 但沒有結束
        int nutrientsStart = text.indexOf("\"nutrients\":{");
        if (nutrientsStart >= 0) {
            int nutrientsOpen = nutrientsStart + "\"nutrients\":{".length();
            // 尋找nutrients的結束
            int nutrientsClose = findMatchingBrace(text, nutrientsOpen - 1);

            if (nutrientsClose < 0) {
                // 嘗試找到kcal的值
                Double kcal = extractNumberAfter(text, "\"kcal\":");
                Double sodium = extractNumberAfter(text, "\"sodium\":");
                Double sodiumAlt = extractNumberAfter(text, "\"sodium\" :");

                if (sodium == null && sodiumAlt != null) {
                    sodium = sodiumAlt;
                }

                // 重新構建nutrients部分
                StringBuilder nutrients = new StringBuilder();
                nutrients.append("\"nutrients\":{");

                if (kcal != null) {
                    nutrients.append("\"kcal\":").append(kcal);
                } else {
                    nutrients.append("\"kcal\":null");
                }

                nutrients.append(",\"protein\":null");
                nutrients.append(",\"fat\":null");
                nutrients.append(",\"carbs\":null");
                nutrients.append(",\"fiber\":null");
                nutrients.append(",\"sugar\":null");

                if (sodium != null) {
                    nutrients.append(",\"sodium\":").append(sodium);
                } else {
                    nutrients.append(",\"sodium\":null");
                }

                nutrients.append("}");

                // 替換nutrients部分
                int nutrientsEnd = text.indexOf("\"nutrients\":{") + "\"nutrients\":{".length();
                String beforeNutrients = text.substring(0, text.indexOf("\"nutrients\":{"));
                String afterNutrients = text.substring(nutrientsEnd);

                // 如果nutrients後面還有其他內容，嘗試保留
                if (afterNutrients.contains("\"confidence\":")) {
                    return beforeNutrients + nutrients.toString() + afterNutrients.substring(afterNutrients.indexOf("\"confidence\":"));
                } else if (afterNutrients.contains("}")) {
                    // 找到第一個}作為結束
                    int firstBrace = afterNutrients.indexOf('}');
                    return beforeNutrients + nutrients.toString() + afterNutrients.substring(firstBrace);
                } else {
                    // 直接添加nutrients和結束
                    return beforeNutrients + nutrients.toString() + ",\"confidence\":0.25,\"warnings\":[\"LOW_CONFIDENCE\"]}";
                }
            }
        }

        // 情況2: 整個JSON沒有結束大括號
        if (!text.endsWith("}")) {
            // 統計大括號
            int openBraces = 0;
            int closeBraces = 0;
            boolean inString = false;
            boolean escaped = false;

            for (int i = 0; i < fixed.length(); i++) {
                char ch = fixed.charAt(i);
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
                    openBraces++;
                } else if (ch == '}') {
                    closeBraces++;
                }
            }

            // 添加缺少的大括號
            if (openBraces > closeBraces) {
                int missing = openBraces - closeBraces;
                for (int i = 0; i < missing; i++) {
                    fixed.append('}');
                }
            } else if (closeBraces > openBraces) {
                // 這通常不會發生，但如果發生，我們刪除多餘的}
                int extra = closeBraces - openBraces;
                for (int i = 0; i < extra && fixed.length() > 0; i++) {
                    if (fixed.charAt(fixed.length() - 1) == '}') {
                        fixed.deleteCharAt(fixed.length() - 1);
                    }
                }
            }
        }

        // 情況3: 移除尾隨逗號
        String result = removeTrailingCommas(fixed.toString());

        // 確保以}結束
        if (!result.endsWith("}")) {
            result = result + "}";
        }

        return result;
    }

    /**
     * ✅ 從截斷文本中提取信息
     */
    private ObjectNode extractFromTruncatedText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }

        ObjectNode root = om.createObjectNode();

        // 1. 提取食品名稱
        String foodName = extractJsonStringValue(text, "foodName");
        if (foodName != null && !foodName.isEmpty()) {
            root.put("foodName", foodName);
        } else {
            root.putNull("foodName");
        }

        // 2. 創建quantity對象
        ObjectNode quantity = om.createObjectNode();
        Double qtyValue = extractJsonNumberValue(text, "value");
        String qtyUnit = extractJsonStringValue(text, "unit");

        if (qtyValue != null) {
            quantity.put("value", qtyValue);
        } else {
            quantity.put("value", 1.0);
        }

        if (qtyUnit != null && !qtyUnit.isEmpty()) {
            quantity.put("unit", qtyUnit);
        } else {
            quantity.put("unit", "SERVING");
        }
        root.set("quantity", quantity);

        // 3. 提取營養值
        ObjectNode nutrients = om.createObjectNode();

        Double kcal = extractJsonNumberValue(text, "kcal");
        Double sodium = extractJsonNumberValue(text, "sodium");

        // 從文本中嘗試其他方式提取鈉含量
        if (sodium == null) {
            sodium = extractSodiumFromText(text);
        }

        if (kcal != null) {
            nutrients.put("kcal", kcal);
        } else {
            nutrients.putNull("kcal");
        }

        nutrients.putNull("protein");
        nutrients.putNull("fat");
        nutrients.putNull("carbs");
        nutrients.putNull("fiber");
        nutrients.putNull("sugar");

        if (sodium != null) {
            nutrients.put("sodium", sodium);
        } else {
            nutrients.putNull("sodium");
        }

        root.set("nutrients", nutrients);

        // 4. 信心度和警告
        root.put("confidence", 0.25);
        ArrayNode warnings = root.putArray("warnings");
        warnings.add(FoodLogWarning.LOW_CONFIDENCE.name());

        return root;
    }

    /**
     * ✅ 從文本中提取JSON字符串值
     */
    private String extractJsonStringValue(String text, String key) {
        if (text == null || key == null) {
            return null;
        }

        // 嘗試多種模式
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
                if (m.find()) {
                    return m.group(1);
                }
            } catch (Exception e) {
                // 繼續嘗試下一個模式
            }
        }

        return null;
    }

    /**
     * ✅ 從文本中提取JSON數字值
     */
    private Double extractJsonNumberValue(String text, String key) {
        if (text == null || key == null) {
            return null;
        }

        // 嘗試多種模式
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
                if (m.find()) {
                    String numStr = m.group(1);
                    return Double.parseDouble(numStr);
                }
            } catch (Exception e) {
                // 繼續嘗試下一個模式
            }
        }

        return null;
    }

    /**
     * ✅ 從文本中提取鈉含量（多語言支持）
     */
    private Double extractSodiumFromText(String text) {
        if (text == null) {
            return null;
        }

        String lowerText = text.toLowerCase(Locale.ROOT);

        // 搜索鈉的各種表示方式
        String[] sodiumMarkers = {
                "\"sodium\":", "\"sodium\" :",
                "\"鈉\":", "\"鈊\":", "\"钠\":",
                "sodium", "鈉", "钠", "natrium"
        };

        for (String marker : sodiumMarkers) {
            int idx = lowerText.indexOf(marker.toLowerCase(Locale.ROOT));
            if (idx >= 0) {
                // 在標記後查找數字
                String afterMarker = text.substring(idx + marker.length());
                Double sodium = extractFirstNumber(afterMarker);
                if (sodium != null) {
                    return sodium;
                }
            }
        }

        return null;
    }

    /**
     * ✅ 在文本中提取第一個數字
     */
    private Double extractFirstNumber(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("-?\\d+(?:\\.\\d+)?");
        java.util.regex.Matcher matcher = pattern.matcher(text);

        if (matcher.find()) {
            try {
                return Double.parseDouble(matcher.group());
            } catch (NumberFormatException e) {
                return null;
            }
        }

        return null;
    }

    /**
     * ✅ 在文本中查找標記後的數字
     */
    private Double extractNumberAfter(String text, String marker) {
        if (text == null || marker == null) {
            return null;
        }

        int idx = text.indexOf(marker);
        if (idx >= 0) {
            String afterMarker = text.substring(idx + marker.length());
            return extractFirstNumber(afterMarker);
        }

        return null;
    }

    /**
     * ✅ 查找匹配的大括號
     */
    private int findMatchingBrace(String text, int openBracePos) {
        if (text == null || openBracePos < 0 || openBracePos >= text.length()) {
            return -1;
        }

        int balance = 1;
        boolean inString = false;
        boolean escaped = false;

        for (int i = openBracePos + 1; i < text.length(); i++) {
            char ch = text.charAt(i);

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
                if (balance == 0) {
                    return i;
                }
            }
        }

        return -1;
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

        // 3) 負面語意（保守）：中英常見「看不清/太小/反光/無法擷取」
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

                // 印尼/越南/泰
                || lower.contains("tidak ditemukan")
                || lower.contains("không tìm thấy") || lower.contains("khong tim thay")
                || lower.contains("ไม่พบ") || lower.contains("อ่านไม่ออก")

                // 中文常見
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
    private CallResult callAndExtract(byte[] imageBytes, String mimeType, String userPrompt, String modelId, boolean isLabel, String foodLogIdForLog) {
        JsonNode resp = callGenerateContent(imageBytes, mimeType, userPrompt, modelId, isLabel);
        Tok tok = extractUsage(resp);
        String text = extractJoinedTextOrNull(resp);
        if (text == null) text = "";
        log.debug("geminiTextPreview foodLogId={} modelId={} preview={}",
                foodLogIdForLog, modelId, safeOneLine200(text));
        return new CallResult(tok, text);
    }

    private JsonNode callGenerateContent(byte[] imageBytes, String mimeType, String userPrompt, String modelId, boolean isLabel) {
        ObjectNode req = buildRequest(imageBytes, mimeType, userPrompt, isLabel);
        return http.post()
                .uri("/v1beta/models/{model}:generateContent", modelId)   // ✅ Step 2：用 router 解析後的 modelId
                .header("x-goog-api-key", requireApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(req)
                .retrieve()
                .body(JsonNode.class);
    }

    private ObjectNode buildRequest(byte[] imageBytes, String mimeType, String userPrompt, boolean isLabel) {
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        ObjectNode root = om.createObjectNode();

        ObjectNode sys = root.putObject("systemInstruction");
        sys.putArray("parts").addObject().put("text", "Return ONLY minified JSON. No markdown. No extra text.");

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

        // ✅ 用 JSON Schema：一定要用 _responseJsonSchema
        // ✅ 且不要同時設定 responseSchema（官方也明講 responseSchema 必須省略）
        gen.set("_responseJsonSchema", isLabel ? nutritionJsonSchemaLabelStrict() : nutritionJsonSchema());

        // maxOutputTokens：LABEL 建議 512~768，想保險 1024 也可以
        gen.put("maxOutputTokens", isLabel ? 768 : props.getMaxOutputTokens());
        gen.put("temperature", isLabel ? 0.0 : props.getTemperature());

        return root;
    }

    private ObjectNode nutritionJsonSchemaLabelStrict() {
        ObjectNode schema = nutritionJsonSchema();

        // root required：加 warnings + labelMeta
        ArrayNode req = (ArrayNode) schema.get("required");
        req.add("warnings");
        req.add("labelMeta");

        // labelMeta required：servingsPerContainer + basis 欄位必須存在（但允許 null）
        ObjectNode propsNode = (ObjectNode) schema.get("properties");
        ObjectNode labelMeta = (ObjectNode) propsNode.get("labelMeta");
        // 確保 labelMeta 有 required array
        ArrayNode lmReq = labelMeta.putArray("required");
        lmReq.add("servingsPerContainer");
        lmReq.add("basis");

        return schema;
    }

    private ObjectNode nutritionJsonSchema() {
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);

        ObjectNode propsNode = schema.putObject("properties");

        // ✅ labelMeta：只給 LABEL 用，讓後端能算整包（130 = 13 * 10）
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
        items.put("type", "string");
        ArrayNode wEnum = items.putArray("enum");
        for (FoodLogWarning w : FoodLogWarning.values()) {
            wEnum.add(w.name());
        }

        schema.putArray("required")
                .add("foodName")
                .add("quantity")
                .add("nutrients")
                .add("confidence");
        return schema;
    }

    // ===== parse + normalize =====
    private JsonNode tryParseJson(String rawText) {
        if (rawText == null) return null;

        String s = stripFence(rawText.trim());
        if (s.isBlank()) return null;

        s = stripBomAndNulls(s);
        s = escapeBadCharsInsideJsonStrings(s);

        String payload = extractFirstJsonPayload(s);
        if (payload == null || payload.isBlank()) return null;

        // ✅ NEW：先修補尾巴（"value": 這種）
        payload = patchDanglingTail(payload);

        payload = balanceJsonIfNeeded(payload);
        payload = removeTrailingCommas(payload);

        // ✅ NEW：補完括號後再修補一次（避免 balance 後尾巴仍是 ':'）
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

        // 如果最後一個非空白字元是 ':'，代表像 `"value":` 沒有值 → 補 null/預設
        if (t.endsWith(":")) {
            String key = extractLastJsonKeyBeforeColon(t);
            if (key != null) {
                String lower = key.toLowerCase(Locale.ROOT);
                if (lower.equals("unit")) {
                    t = t + "\"SERVING\"";
                } else if (lower.equals("basis")) {
                    t = t + "\"PER_SERVING\"";
                } else {
                    // value/kcal/protein/.../servingsPerContainer/confidence 都用 null 最安全
                    t = t + "null";
                }
            } else {
                // 找不到 key 就補 null，至少讓 JSON 變合法
                t = t + "null";
            }
        }
        // 若結尾是 `,"key"` 之類也可能炸，但你已經有 removeTrailingCommas()
        return t;
    }

    private static String rtrim(String s) {
        int i = s.length();
        while (i > 0 && Character.isWhitespace(s.charAt(i - 1))) i--;
        return s.substring(0, i);
    }

    /**
     * 從尾巴往前找最後一個 `"xxx":` 的 key 名
     * 只處理「結尾剛好是冒號」這種截斷案例，避免過度修補。
     */
    private static String extractLastJsonKeyBeforeColon(String t) {
        // t endsWith ':'
        int colon = t.length() - 1;
        // 找到 colon 前最近的雙引號對
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

        // ✅ confidence：允許 70 這種百分比 → 0.7；超出合理範圍 → null（不再炸）
        Double conf = parseNumberNodeOrText(raw.get("confidence"));
        conf = normalizeConfidence(conf);
        if (conf == null) out.putNull("confidence");
        else out.put("confidence", conf);

        // warnings
        ArrayNode outW = JsonNodeFactory.instance.arrayNode();
        JsonNode w = raw.get("warnings");
        if (w != null && w.isArray()) {
            for (JsonNode it : w) {
                if (it == null || it.isNull()) continue;
                FoodLogWarning ww = FoodLogWarning.parseOrNull(it.asText());
                if (ww != null) outW.add(ww.name());
            }
        }
        // ✅ 無論空不空，都輸出
        out.set("warnings", outW);

        return out;
    }

    /**
     * ✅ 容錯：允許
     * - number
     * - "312 mg" / "5.5g" / "2118 kJ"
     * - {"value":13,"unit":"mg"}
     * 注意：為了避免 LABEL 被奇怪型別打爆，未知型別會視為 null（不 throw）。
     */
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
                unit = raw; // 用字串做 unit 判斷
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
            // ✅ 不可預期型別 → null，不炸
            to.putNull(key);
            return;
        }

        if (d == null) {
            to.putNull(key);
            return;
        }
        if (d < 0) {
            to.putNull(key);
            return;
        }

        // ===== 單位/換算修正（盡量保守）=====
        String u = (unit == null) ? "" : unit.toLowerCase(Locale.ROOT);

        // kcal：若 unit/字串提到 kJ 且沒提 kcal → kJ -> kcal
        if ("kcal".equals(key)) {
            if (u.contains("kj") && !u.contains("kcal")) d = d / 4.184d;
        }

        // sodium：目標 mg
        if ("sodium".equals(key)) {
            // 若提供 g → mg
            if (u.contains(" g") || u.endsWith("g") || u.equals("g")) {
                if (!u.contains("mg")) d = d * 1000d;
            }
        } else if (!"kcal".equals(key)) {
            // macros：目標 g；若 mg → g
            if (u.contains("mg")) d = d / 1000d;
        }

        to.put(key, d);
    }

    // ✅ Step 2：依 degradeLevel 決定 tier（DG-0=HIGH；其他=LOW）
    private static ModelTier resolveTierFromDegradeLevel(String degradeLevel) {
        if (degradeLevel == null) return ModelTier.MODEL_TIER_LOW;
        String v = degradeLevel.trim().toUpperCase(Locale.ROOT);
        return v.startsWith("DG-0")
                ? ModelTier.MODEL_TIER_HIGH
                : ModelTier.MODEL_TIER_LOW;
    }

    // ✅ Step 2：從 router 解析出 modelId（目前你送圖，所以 mode 固定 VISION）
    private String resolveModelIdVision(FoodLogEntity entity) {
        ModelTier tier = resolveTierFromDegradeLevel(entity.getDegradeLevel());
        AiModelRouter.Resolved r = modelRouter.resolveOrThrow(tier, ModelMode.VISION);
        return r.modelId();
    }

    // ===== misc helpers =====
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

    private static final java.util.regex.Pattern P_NUM =
            java.util.regex.Pattern.compile("(-?\\d+(?:[.,]\\d+)?)");

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

    // ✅ NEW：confidence 容錯（70 -> 0.7）
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

    // ✅ NEW：unwrap root（支援 result/data/array）
    private static JsonNode unwrapRootObjectOrNull(JsonNode raw) {
        if (raw == null || raw.isNull()) return null;

        // array -> first object
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

    // ✅ NEW：只去掉 fence，不先截 JSON（截 JSON 交給 extractFirstJsonPayload）
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

    /**
     * ✅ NEW：抽出第一段完整 JSON（object 或 array）
     * - 找到第一個 '{' 或 '['
     * - 逐字掃描，處理字串/跳脫，計算 {} 和 []
     * - 回傳平衡結束的位置
     */
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

                // 結束條件：回到 0 且已經開始過
                if (brace == 0 && bracket == 0) {
                    return s.substring(start, i + 1).trim();
                }
            }
        }

        // 沒找到完整結尾，先回到字串尾巴，後面 balanceJsonIfNeeded 再補
        return s.substring(start).trim();
    }

    // ✅ NEW：同時補齊 } 和 ]（忽略字串內括號）
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
                // 只在 stack top 匹配時才 pop（不匹配就忽略，避免越修越壞）
                if (!stack.isEmpty()) {
                    char top = stack.peek();
                    if ((top == '{' && ch == '}') || (top == '[' && ch == ']')) {
                        stack.pop();
                    }
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

        // 如果三者都撈不到，就放棄
        if (kcal == null && sodium == null) return null;

        ObjectNode root = om.createObjectNode();
        if (name == null || name.isBlank()) root.putNull("foodName");
        else root.put("foodName", name);

        ObjectNode q = root.putObject("quantity");
        q.put("value", 1d);
        q.put("unit", "SERVING");

        ObjectNode n = root.putObject("nutrients");
        putOrNull(n, "kcal", kcal);
        // 其他先給 null（讓後處理/或 repair 再補）
        n.putNull("protein");
        n.putNull("fat");
        n.putNull("carbs");
        n.putNull("fiber");
        n.putNull("sugar");
        putOrNull(n, "sodium", sodium);

        // 低信心：因為是從破碎 JSON 撈的
        root.put("confidence", 0.25);
        root.putArray("warnings").add(FoodLogWarning.LOW_CONFIDENCE.name());
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
        // ✅ 不要亂填 0，讀不到就 null（避免誤判成 0）
        n.putNull("protein");
        n.putNull("fat");
        n.putNull("carbs");
        n.putNull("fiber");
        n.putNull("sugar");
        putOrNull(n, "sodium", sodium);

        root.put("confidence", 0.35);

        // warnings 一定給（空也行）
        ArrayNode w = root.putArray("warnings");
        w.add(FoodLogWarning.LOW_CONFIDENCE.name());

        // ✅ labelMeta：若抓到份數，就給 PER_SERVING，讓 applyWholePackageScalingIfNeeded 去乘
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

    private static boolean isLabelUsable(JsonNode raw) {
        raw = unwrapRootObjectOrNull(raw);
        if (raw == null || !raw.isObject()) return false;

        JsonNode n = raw.get("nutrients");
        if (n == null || !n.isObject()) return false;

        // ✅ Label 最少要有一個實際數字（0.0 也算數字）
        for (String k : new String[]{"kcal","protein","fat","carbs","fiber","sugar","sodium"}) {
            JsonNode v = n.get(k);
            if (v != null && v.isNumber()) return true;
        }
        return false;
    }

    // ✅ LABEL 完整度檢查：避免「只抓到 kcal=0」就被當成功
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

        // 情況 1：連 kcal 都沒有 → 一定不完整
        if (!kcalPresent) return true;

        // 情況 2：只有 1 個欄位有值（幾乎就是只抓到 kcal=0）→ 不完整
        if (nonNull <= 1) return true;

        // 情況 3：你這次的典型失敗：kcal 有、但三大營養素/糖/鈉都沒抓到 → 不完整
        boolean sodiumNull = (n.get("sodium") == null || n.get("sodium").isNull());
        boolean allMacrosNull =
                (n.get("protein") == null || n.get("protein").isNull()) &&
                        (n.get("fat") == null || n.get("fat").isNull()) &&
                        (n.get("carbs") == null || n.get("carbs").isNull()) &&
                        (n.get("sugar") == null || n.get("sugar").isNull());

        return sodiumNull && allMacrosNull;
    }

    // ✅ NEW：移除 JSON 裡最常見的錯：trailing comma（在 } / ] 前面的逗號）
    // 只處理「非字串內」的情況
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
            // not in string
            if (ch == '"') {
                inString = true;
                out.append(ch);
                continue;
            }
            if (ch == ',') {
                // lookahead to next non-ws
                int j = i + 1;
                while (j < s.length() && Character.isWhitespace(s.charAt(j))) j++;
                if (j < s.length()) {
                    char nx = s.charAt(j);
                    if (nx == '}' || nx == ']') {
                        // skip this comma
                        continue;
                    }
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
            if (v != null && v.isNumber()) {
                n.put(k, v.asDouble() * servings);
            }
        }

        // quantity 維持 1 SERVING（整包）
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
