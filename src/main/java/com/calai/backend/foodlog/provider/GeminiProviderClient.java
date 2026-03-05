package com.calai.backend.foodlog.provider;

import com.calai.backend.foodlog.barcode.BarcodeLookupService;
import com.calai.backend.foodlog.config.AiModelRouter;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.mapper.ProviderErrorMapper;
import com.calai.backend.foodlog.model.ModelMode;
import com.calai.backend.foodlog.packagedfood.ImageBarcodeDetector;
import com.calai.backend.foodlog.packagedfood.OpenFoodFactsSearchService;
import com.calai.backend.foodlog.provider.config.GeminiProperties;
import com.calai.backend.foodlog.provider.util.LabelJsonRepairUtil;
import com.calai.backend.foodlog.quota.model.ModelTier;
import com.calai.backend.foodlog.storage.StorageService;
import com.calai.backend.foodlog.task.FoodLogWarning;
import com.calai.backend.foodlog.task.ProviderClient;
import com.calai.backend.foodlog.task.ProviderTelemetry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Slf4j
public class GeminiProviderClient implements ProviderClient {

    private static final int PREVIEW_LEN = 200;

    private final GeminiPackagedFoodResolver packagedFoodResolver;
    private final GeminiJsonParsingSupport jsonParsingSupport;
    private final GeminiLabelFallbackSupport labelFallbackSupport;
    private final GeminiTransportSupport transportSupport;
    private final GeminiPromptFactory promptFactory;

    private final ObjectMapper om;
    private final ProviderTelemetry telemetry;
    private final AiModelRouter modelRouter;

    public GeminiProviderClient(
            RestClient http,
            GeminiProperties props,
            ObjectMapper om,
            ProviderTelemetry telemetry,
            AiModelRouter modelRouter,
            ImageBarcodeDetector imageBarcodeDetector,
            BarcodeLookupService barcodeLookupService,
            OpenFoodFactsSearchService offSearchService
    ) {
        this.om = om;
        this.telemetry = telemetry;
        this.modelRouter = modelRouter;
        this.packagedFoodResolver = new GeminiPackagedFoodResolver(
                http,
                props,
                om,
                telemetry,
                imageBarcodeDetector,
                barcodeLookupService,
                offSearchService
        );
        this.jsonParsingSupport = new GeminiJsonParsingSupport(om);
        this.labelFallbackSupport = new GeminiLabelFallbackSupport(om);
        this.promptFactory = new GeminiPromptFactory();

        GeminiRequestBuilder requestBuilder = new GeminiRequestBuilder(om, props);
        this.transportSupport = new GeminiTransportSupport(http, props, requestBuilder);
    }

    @Override
    public String providerCode() {
        return "GEMINI";
    }

    @Override
    public ProviderResult process(FoodLogEntity entity, StorageService storage) throws Exception {
        long t0 = System.nanoTime();
        String modelId = null;

        try {
            byte[] bytes;
            try (InputStream in = storage.open(entity.getImageObjectKey()).inputStream()) {
                bytes = in.readAllBytes();
            }
            if (bytes.length == 0) {
                throw new IllegalStateException("EMPTY_IMAGE");
            }

            String mime = ImageMimeResolver.resolveOrDefault(entity.getImageContentType(), bytes);

            if (entity.getImageContentType() == null || entity.getImageContentType().isBlank()) {
                log.warn("image_content_type_missing_resolved foodLogId={} resolvedMime={}",
                        entity.getId(), mime);
            }

            final boolean isLabel = "LABEL".equalsIgnoreCase(entity.getMethod());
            modelId = resolveModelIdVision(entity);
            final String promptMain = promptFactory.mainPrompt(isLabel);

            if (!isLabel) {
                Optional<ProviderResult> autoBarcodeResolved =
                        tryResolvePackagedFoodByLocalBarcode(entity, bytes, t0);

                if (autoBarcodeResolved.isPresent()) {
                    return autoBarcodeResolved.get();
                }
            }

            CallResult r1 = callAndExtract(bytes, mime, promptMain, modelId, isLabel, entity.getId());
            Tok tok = r1.tok;

            JsonNode parsed1 = (r1.functionArgs != null) ? r1.functionArgs : tryParseJson(r1.text);
            parsed1 = unwrapRootObjectOrNull(parsed1);

            BestLabel best = emptyBestLabel();
            if (isLabel) {
                best = best.consider(parsed1);
            }

            if (isLabel && parsed1 == null) {
                log.warn("label_parse_failed foodLogId={} preview={}", entity.getId(), safeOneLine200(r1.text));
            }

            if (isLabel && parsed1 == null) {
                ObjectNode repaired1 = LabelJsonRepairUtil.repairOrExtract(om, r1.text, true);

                if (repaired1 != null) {
                    best = best.consider(repaired1);
                    parsed1 = repaired1;

                    if (hasAnyNutrientValue(parsed1) && !isLabelIncomplete(parsed1)) {
                        ObjectNode effective = normalizeToEffective(parsed1);
                        return okAndReturn(modelId, entity, t0, tok, true, parsed1, effective);
                    }
                }
            }

            if (isLabel && parsed1 == null && isJsonTruncated(r1.text)) {
                log.info("label_json_truncated_try_fix foodLogId={}", entity.getId());
                ObjectNode fromTruncated = tryFixTruncatedJson(r1.text);
                if (fromTruncated != null && hasAnyNutrientValue(fromTruncated)) {
                    ObjectNode effective = normalizeToEffective(fromTruncated);
                    return okAndReturn(modelId, entity, t0, tok, true, fromTruncated, effective);
                }
            }

            if (isLabel && parsed1 != null && hasWarning(parsed1, FoodLogWarning.NO_LABEL_DETECTED.name())) {
                ObjectNode effective = normalizeToEffective(parsed1);
                return okAndReturn(modelId, entity, t0, tok, true, parsed1, effective);
            }

            if (isLabel && parsed1 != null && isLabelEmptyArgs(parsed1)) {
                ObjectNode partial = fallbackLabelPartialDetected(r1.text);
                ObjectNode effective = normalizeToEffective(partial);
                return okAndReturn(modelId, entity, t0, tok, true, partial, effective);
            }

            // =========================
            // ✅ 非 LABEL：修正版流程
            // =========================
            if (!isLabel) {
                boolean noFoodDetected = isNoFoodDetected(parsed1, r1.text);
                boolean mainHasNutrition = hasAnyNutrientValue(parsed1);

                log.info("photo_main_result foodLogId={} mainHasNutrition={} noFoodDetected={} preview={}",
                        entity.getId(),
                        mainHasNutrition,
                        noFoodDetected,
                        safeOneLine200(r1.text));

                if (mainHasNutrition) {
                    ObjectNode effective = normalizeToEffective(parsed1);

                    if (GeminiNutritionQualityGate.isAcceptablePhoto(effective)) {
                        return okAndReturn(modelId, entity, t0, tok, false, parsed1, effective);
                    }

                    log.warn("nutrition_rejected_by_quality_gate foodLogId={} preview={}",
                            entity.getId(), safeOneLine200(r1.text));
                }

                // ✅ 只要不是明確 no-food，就仍然嘗試 packaged-food rescue
                if (shouldTryPackagedFoodRescueForPhoto(parsed1, r1.text)) {
                    log.info("packaged_food_rescue_try foodLogId={} mainHasNutrition={}",
                            entity.getId(), mainHasNutrition);

                    Optional<ProviderResult> packageResolved =
                            tryResolvePackagedFoodByNameSearch(entity, bytes, mime, modelId, t0);

                    if (packageResolved.isPresent()) {
                        return packageResolved.get();
                    }

                    log.info("packaged_food_rescue_miss foodLogId={} mainHasNutrition={} preview={}",
                            entity.getId(), mainHasNutrition, safeOneLine200(r1.text));
                }

                if (noFoodDetected) {
                    ObjectNode fb = fallbackNoFoodDetected();
                    ObjectNode effective = normalizeToEffective(fb);
                    return okAndReturn(modelId, entity, t0, tok, false, fb, effective);
                }

            } else {
                if (hasAnyNutrientValue(parsed1)) {
                    if (isLabelIncomplete(parsed1)) {
                        log.warn("label_incomplete_main_force_repair foodLogId={} preview={}",
                                entity.getId(), safeOneLine200(r1.text));
                    } else {
                        ObjectNode effective = normalizeToEffective(parsed1);
                        return okAndReturn(modelId, entity, t0, tok, true, parsed1, effective);
                    }
                } else {
                    log.warn("label_unusable_main_will_repair foodLogId={} preview={}",
                            entity.getId(), safeOneLine200(r1.text));
                }

                if (parsed1 == null) {
                    ObjectNode fromBrokenJson = tryExtractFromBrokenJsonLikeText(r1.text);
                    if (fromBrokenJson != null) {
                        ObjectNode effective = normalizeToEffective(fromBrokenJson);
                        return okAndReturn(modelId, entity, t0, tok, true, fromBrokenJson, effective);
                    }

                    ObjectNode fromText = tryExtractLabelFromPlainText(r1.text);
                    if (fromText != null) {
                        ObjectNode effective = normalizeToEffective(fromText);
                        return okAndReturn(modelId, entity, t0, tok, true, fromText, effective);
                    }
                }

                if (isNoLabelLikely(r1.text)) {
                    ObjectNode fb = fallbackNoLabelDetected();
                    ObjectNode effective = normalizeToEffective(fb);
                    return okAndReturn(modelId, entity, t0, tok, true, fb, effective);
                }
            }

            final String baseRepairInput =
                    (parsed1 != null) ? parsed1.toString()
                            : (r1.text != null ? r1.text : "");

            final boolean allowTextRepair =
                    (!isLabel) ||
                            (!baseRepairInput.isBlank() && (parsed1 == null || isLabelIncomplete(parsed1) || !hasAnyNutrientValue(parsed1)));

            final List<String> repairModelIds = new ArrayList<>();

            if (allowTextRepair) {
                if (isLabel) {
                    repairModelIds.add(resolveModelIdTextByTier(ModelTier.MODEL_TIER_LOW));
                } else {
                    repairModelIds.add(resolveModelIdText(entity));
                }
            }

            String lastText = baseRepairInput;

            for (String modelIdText : repairModelIds) {
                String repairPromptText = promptFactory.buildTextOnlyRepairPrompt(isLabel, lastText);
                CallResult rn = callAndExtractTextOnly(repairPromptText, modelIdText, entity.getId());

                tok = tok.plus(rn.tok);
                lastText = rn.text;

                JsonNode parsedN = tryParseJson(rn.text);
                parsedN = unwrapRootObjectOrNull(parsedN);

                if (isLabel) {
                    best = best.consider(parsedN);
                }

                if (isLabel && parsedN != null && hasWarning(parsedN, FoodLogWarning.NO_LABEL_DETECTED.name())) {
                    ObjectNode effective = normalizeToEffective(parsedN);
                    return okAndReturn(modelId, entity, t0, tok, true, parsedN, effective);
                }

                if (hasAnyNutrientValue(parsedN)) {
                    if (isLabel && isLabelIncomplete(parsedN)) {
                        log.warn("label_incomplete_after_text_repair foodLogId={} modelIdText={} preview={}",
                                entity.getId(), modelIdText, safeOneLine200(rn.text));
                    } else {
                        ObjectNode effective = normalizeToEffective(parsedN);
                        return okAndReturn(modelIdText, entity, t0, tok, isLabel, parsedN, effective);
                    }
                }

                if (isLabel) {
                    ObjectNode fromText = tryExtractLabelFromPlainText(rn.text);
                    if (fromText != null) {
                        ObjectNode effective = normalizeToEffective(fromText);
                        return okAndReturn(modelId, entity, t0, tok, true, fromText, effective);
                    }
                    if (isNoLabelLikely(rn.text)) {
                        ObjectNode fb = fallbackNoLabelDetected();
                        ObjectNode effective = normalizeToEffective(fb);
                        return okAndReturn(modelId, entity, t0, tok, true, fb, effective);
                    }
                } else {
                    if (isNoFoodDetected(parsedN, rn.text)) {
                        ObjectNode fb = fallbackNoFoodDetected();
                        ObjectNode effective = normalizeToEffective(fb);
                        return okAndReturn(modelId, entity, t0, tok, false, fb, effective);
                    }
                }
            }

            if (isLabel) {
                if (best.raw != null) {
                    if (!best.complete) {
                        addWarningIfMissing(best.raw, FoodLogWarning.LABEL_PARTIAL);
                        addWarningIfMissing(best.raw, FoodLogWarning.LOW_CONFIDENCE);
                        if (best.raw.path("confidence").isNull()) {
                            best.raw.put("confidence", 0.25);
                        }
                    }

                    ObjectNode effective = normalizeToEffective(best.raw);
                    return okAndReturn(modelId, entity, t0, tok, true, best.raw, effective);
                }

                if (looksLikeStartedJsonLabel(lastText) || (isJsonTruncated(lastText) && lastText.trim().startsWith("{"))) {
                    ObjectNode partial = fallbackLabelPartialDetected(lastText);
                    ObjectNode effective = normalizeToEffective(partial);
                    return okAndReturn(modelId, entity, t0, tok, true, partial, effective);
                }

                ObjectNode fb = fallbackNoLabelDetected();
                ObjectNode effective = normalizeToEffective(fb);
                return okAndReturn(modelId, entity, t0, tok, true, fb, effective);
            }

            if (shouldTryPackagedPartialSalvage(parsed1, lastText)) {
                log.info("photo_partial_packaged_salvage_try foodLogId={}", entity.getId());

                String salvageModelId = resolveModelIdText(entity);
                String salvagePrompt = promptFactory.buildPackagedFoodPartialSalvagePrompt(lastText);

                CallResult rnSalvage = callAndExtractTextOnly(salvagePrompt, salvageModelId, entity.getId());
                tok = tok.plus(rnSalvage.tok);

                JsonNode parsedSalvage = tryParseJson(rnSalvage.text);
                parsedSalvage = unwrapRootObjectOrNull(parsedSalvage);

                if (parsedSalvage != null && hasAnyNutrientValue(parsedSalvage)) {
                    ObjectNode effective = normalizeToEffective(parsedSalvage);
                    return okAndReturn(salvageModelId, entity, t0, tok, false, parsedSalvage, effective);
                }

                log.warn("photo_partial_packaged_salvage_failed foodLogId={} preview={}",
                        entity.getId(), safeOneLine200(rnSalvage.text));
            }

            // =========================
            // ✅ 非 LABEL：新增 foodName-only fallback
            // =========================
            String foodNameHint = extractFoodNameHint(parsed1, baseRepairInput, lastText);

            if (foodNameHint != null && !foodNameHint.isBlank()) {
                log.info("photo_name_only_estimate_try foodLogId={} foodNameHint={}",
                        entity.getId(), foodNameHint);

                String modelIdText = resolveModelIdText(entity);
                String prompt = promptFactory.buildFoodNameOnlyEstimatePrompt(foodNameHint, lastText);

                CallResult rnName = callAndExtractTextOnly(prompt, modelIdText, entity.getId());
                tok = tok.plus(rnName.tok);

                JsonNode parsedByName = tryParseJson(rnName.text);
                parsedByName = unwrapRootObjectOrNull(parsedByName);

                ObjectNode fixedByName = ensureFoodNameIfMissing(parsedByName, foodNameHint, om);

                if (fixedByName != null && hasAnyNutrientValue(fixedByName)) {
                    ObjectNode effective = normalizeToEffective(fixedByName);
                    effective.withArray("warnings").add("ESTIMATED_FROM_PRODUCT_NAME");
                    return okAndReturn(modelIdText, entity, t0, tok, false, fixedByName, effective);
                }

                log.warn("photo_name_only_estimate_failed foodLogId={} foodNameHint={} preview={}",
                        entity.getId(), foodNameHint, safeOneLine200(rnName.text));
            }

            log.warn("photo_final_fallback_unknown foodLogId={} preview={}",
                    entity.getId(), safeOneLine200(lastText));

            ObjectNode fbUnknown = fallbackUnknownFoodFromBrokenText(lastText);
            ObjectNode effective = normalizeToEffective(fbUnknown);
            return okAndReturn(modelId, entity, t0, tok, false, fbUnknown, effective);

        } catch (Exception e) {
            ProviderErrorMapper.Mapped mapped = ProviderErrorMapper.map(e);
            telemetry.fail("GEMINI", modelId, entity.getId(), msSince(t0), mapped.code(), mapped.retryAfterSec());
            throw e;
        }
    }

    private boolean looksLikeStartedJsonLabel(String text) {
        return labelFallbackSupport.looksLikeStartedJsonLabel(text);
    }

    private ObjectNode fallbackLabelPartialDetected(String rawText) {
        return labelFallbackSupport.fallbackLabelPartialDetected(rawText);
    }

    private boolean isLabelEmptyArgs(JsonNode root) {
        return labelFallbackSupport.isLabelEmptyArgs(root);
    }

    private CallResult callAndExtract(
            byte[] imageBytes,
            String mimeType,
            String userPrompt,
            String modelId,
            boolean isLabel,
            String foodLogIdForLog
    ) {
        GeminiTransportSupport.CallResult r =
                transportSupport.callAndExtract(
                        imageBytes,
                        mimeType,
                        userPrompt,
                        modelId,
                        isLabel,
                        foodLogIdForLog
                );

        return new CallResult(
                new Tok(
                        r.tok().promptTok(),
                        r.tok().candTok(),
                        r.tok().totalTok()
                ),
                r.text(),
                r.functionArgs()
        );
    }

    private String resolveModelIdText(FoodLogEntity entity) {
        ModelTier tier = resolveTierFromDegradeLevel(entity.getDegradeLevel());
        AiModelRouter.Resolved r = modelRouter.resolveOrThrow(tier, ModelMode.TEXT);
        return r.modelId();
    }

    private String resolveModelIdTextByTier(ModelTier tier) {
        AiModelRouter.Resolved r = modelRouter.resolveOrThrow(tier, ModelMode.TEXT);
        return r.modelId();
    }

    private CallResult callAndExtractTextOnly(String userPrompt, String modelId, String foodLogIdForLog) {
        String sys = "You are a JSON repair engine. Return ONLY ONE minified JSON object. No markdown. No extra text.";
        return callAndExtractTextOnly(userPrompt, modelId, foodLogIdForLog, sys);
    }

    private CallResult callAndExtractTextOnly(
            String userPrompt,
            String modelId,
            String foodLogIdForLog,
            String systemInstruction
    ) {
        GeminiTransportSupport.CallResult r =
                transportSupport.callAndExtractTextOnly(
                        userPrompt,
                        modelId,
                        foodLogIdForLog,
                        systemInstruction
                );

        return new CallResult(
                new Tok(
                        r.tok().promptTok(),
                        r.tok().candTok(),
                        r.tok().totalTok()
                ),
                r.text(),
                r.functionArgs()
        );
    }

    private Optional<ProviderResult> tryResolvePackagedFoodByLocalBarcode(
            FoodLogEntity entity,
            byte[] imageBytes,
            long t0
    ) {
        return packagedFoodResolver.tryResolvePackagedFoodByLocalBarcode(entity, imageBytes, t0);
    }

    private Optional<ProviderResult> tryResolvePackagedFoodByNameSearch(
            FoodLogEntity entity,
            byte[] imageBytes,
            String mimeType,
            String modelId,
            long t0
    ) {
        return packagedFoodResolver.tryResolvePackagedFoodByNameSearch(entity, imageBytes, mimeType, modelId, t0);
    }

    private static ModelTier resolveTierFromDegradeLevel(String degradeLevel) {
        if (degradeLevel == null) {
            return ModelTier.MODEL_TIER_LOW;
        }
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

    private JsonNode tryParseJson(String rawText) {
        return jsonParsingSupport.tryParseJson(rawText);
    }

    private ObjectNode normalizeToEffective(JsonNode raw) {
        return GeminiEffectiveJsonSupport.normalizeToEffective(raw);
    }

    private static boolean hasAnyNutrientValue(JsonNode root) {
        return GeminiEffectiveJsonSupport.hasAnyNutrientValue(root);
    }

    private static boolean hasWarning(JsonNode root, String code) {
        return GeminiEffectiveJsonSupport.hasWarning(root, code);
    }

    private static JsonNode unwrapRootObjectOrNull(JsonNode raw) {
        return GeminiEffectiveJsonSupport.unwrapRootObjectOrNull(raw);
    }

    private static boolean isNoFoodDetected(JsonNode parsed, String rawText) {
        if (parsed != null && hasWarning(parsed, FoodLogWarning.NO_FOOD_DETECTED.name())) {
            return true;
        }
        return isNoFoodTextSignal(rawText);
    }

    static boolean shouldTryPackagedFoodRescueForPhoto(JsonNode parsed, String rawText) {
        return !isNoFoodDetected(parsed, rawText);
    }

    private static boolean isNoFoodTextSignal(String rawText) {
        if (rawText == null) {
            return false;
        }
        String lower = rawText.toLowerCase(Locale.ROOT);
        return lower.contains("no food detected")
               || lower.contains("no food present")
               || lower.contains("not a food")
               || lower.contains("not food")
               || lower.contains("no meal");
    }

    private static String safeOneLine200(String s) {
        if (s == null) {
            return null;
        }
        String t = s.replace("\r", " ").replace("\n", " ").trim();
        return (t.length() > PREVIEW_LEN) ? t.substring(0, PREVIEW_LEN) : t;
    }

    private static long msSince(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    private ProviderResult okAndReturn(
            String modelIdForTelemetry,
            FoodLogEntity entity,
            long t0,
            Tok tok,
            boolean isLabel,
            JsonNode rawForFinalize,
            ObjectNode effective
    ) {
        finalizeEffective(isLabel, rawForFinalize, effective);

        telemetry.ok(
                "GEMINI",
                modelIdForTelemetry,
                entity.getId(),
                msSince(t0),
                tok.promptTok,
                tok.candTok,
                tok.totalTok
        );
        return new ProviderResult(effective, "GEMINI");
    }

    private static void finalizeEffective(boolean isLabel, JsonNode rawForFinalize, ObjectNode effective) {
        GeminiEffectiveJsonSupport.finalizeEffective(isLabel, rawForFinalize, effective);
    }

    private boolean isJsonTruncated(String text) {
        return jsonParsingSupport.isJsonTruncated(text);
    }

    private ObjectNode tryFixTruncatedJson(String rawText) {
        return labelFallbackSupport.tryFixTruncatedJson(rawText);
    }

    private ObjectNode tryExtractFromBrokenJsonLikeText(String raw) {
        return labelFallbackSupport.tryExtractFromBrokenJsonLikeText(raw);
    }

    private ObjectNode tryExtractLabelFromPlainText(String raw) {
        return labelFallbackSupport.tryExtractLabelFromPlainText(raw);
    }

    private boolean isLabelIncomplete(JsonNode root) {
        return labelFallbackSupport.isLabelIncomplete(root);
    }

    private boolean isNoLabelLikely(String rawText) {
        return labelFallbackSupport.isNoLabelLikely(rawText);
    }

    private ObjectNode fallbackNoLabelDetected() {
        return labelFallbackSupport.fallbackNoLabelDetected();
    }

    private ObjectNode fallbackNoFoodDetected() {
        ObjectNode root = om.createObjectNode();
        root.putNull("foodName");

        ObjectNode q = root.putObject("quantity");
        q.put("value", 1d);
        q.put("unit", "SERVING");

        ObjectNode n = root.putObject("nutrients");
        n.putNull("kcal");
        n.putNull("protein");
        n.putNull("fat");
        n.putNull("carbs");
        n.putNull("fiber");
        n.putNull("sugar");
        n.putNull("sodium");

        root.put("confidence", 0.1);
        root.putArray("warnings").add(FoodLogWarning.NO_FOOD_DETECTED.name());
        return root;
    }

    private ObjectNode fallbackUnknownFoodFromBrokenText(String rawText) {
        ObjectNode root = om.createObjectNode();
        root.putNull("foodName");

        ObjectNode q = root.putObject("quantity");
        q.put("value", 1d);
        q.put("unit", "SERVING");

        ObjectNode n = root.putObject("nutrients");
        n.putNull("kcal");
        n.putNull("protein");
        n.putNull("fat");
        n.putNull("carbs");
        n.putNull("fiber");
        n.putNull("sugar");
        n.putNull("sodium");

        root.put("confidence", 0.2);
        root.putArray("warnings")
                .add(FoodLogWarning.UNKNOWN_FOOD.name())
                .add(FoodLogWarning.LOW_CONFIDENCE.name());

        return root;
    }

    private final class BestLabel {
        private final ObjectNode raw;
        private final int score;
        private final boolean complete;

        private BestLabel(ObjectNode raw, int score, boolean complete) {
            this.raw = raw;
            this.score = score;
            this.complete = complete;
        }

        BestLabel consider(JsonNode cand) {
            if (cand == null || !cand.isObject()) {
                return this;
            }

            ObjectNode obj = (ObjectNode) cand;
            ensureLabelRequiredKeys(obj);

            int sc = labelNutrientScore(obj);
            boolean comp = !isLabelIncomplete(obj);

            if (sc > this.score) {
                return new BestLabel(obj.deepCopy(), sc, comp);
            }
            return this;
        }
    }

    private BestLabel emptyBestLabel() {
        return new BestLabel(null, -1, false);
    }

    private int labelNutrientScore(JsonNode root) {
        return labelFallbackSupport.labelNutrientScore(root);
    }

    private void ensureLabelRequiredKeys(ObjectNode obj) {
        labelFallbackSupport.ensureLabelRequiredKeys(obj);
    }

    private static void addWarningIfMissing(ObjectNode raw, FoodLogWarning w) {
        GeminiLabelFallbackSupport.addWarningIfMissing(raw, w);
    }

    private record CallResult(Tok tok, String text, JsonNode functionArgs) {}

    private record Tok(Integer promptTok, Integer candTok, Integer totalTok) {

        Tok plus(Tok other) {
            if (other == null) {
                return this;
            }
            return new Tok(
                    add(this.promptTok, other.promptTok),
                    add(this.candTok, other.candTok),
                    add(this.totalTok, other.totalTok)
            );
        }

        private static Integer add(Integer a, Integer b) {
            if (a == null) return b;
            if (b == null) return a;
            return a + b;
        }
    }

    private static final Pattern P_FOOD_NAME_JSON =
            Pattern.compile("\"foodName\"\\s*:\\s*\"([^\"]{1,160})\"");

    private static String extractFoodNameHint(JsonNode parsed, String... rawTexts) {
        if (parsed != null && parsed.isObject()) {
            JsonNode fn = parsed.get("foodName");
            if (fn != null && !fn.isNull()) {
                String s = fn.asText(null);
                if (s != null && !s.isBlank()) {
                    return s.trim();
                }
            }
        }

        if (rawTexts != null) {
            for (String raw : rawTexts) {
                String found = extractFoodNameFromJsonLikeText(raw);
                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

    private static String extractFoodNameFromJsonLikeText(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        Matcher m = P_FOOD_NAME_JSON.matcher(raw);
        if (!m.find()) {
            return null;
        }

        String s = m.group(1);
        if (s == null) {
            return null;
        }

        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    private static ObjectNode ensureFoodNameIfMissing(JsonNode parsed, String foodName, ObjectMapper om) {
        if (parsed == null || !parsed.isObject()) {
            return null;
        }

        ObjectNode obj = ((ObjectNode) parsed).deepCopy();
        JsonNode fn = obj.get("foodName");
        if ((fn == null || fn.isNull() || fn.asText("").isBlank()) && foodName != null && !foodName.isBlank()) {
            obj.put("foodName", foodName);
        }
        return obj;
    }

    private static boolean shouldTryPackagedPartialSalvage(JsonNode parsed, String rawText) {
        String foodNameHint = extractFoodNameHint(parsed, rawText);
        if (foodNameHint == null || foodNameHint.isBlank()) {
            return false;
        }

        if (parsed != null && parsed.isObject()) {
            JsonNode quantity = parsed.get("quantity");
            JsonNode labelMeta = parsed.get("labelMeta");

            boolean hasQuantityObj = quantity != null && quantity.isObject();
            boolean hasLabelMetaObj = labelMeta != null && labelMeta.isObject();

            if (hasQuantityObj || hasLabelMetaObj) {
                return true;
            }
        }

        String lower = rawText == null ? "" : rawText.toLowerCase(Locale.ROOT);
        return lower.contains("\"labelmeta\"")
                || lower.contains("\"quantity\"")
                || lower.contains("servingspercontainer")
                || lower.contains("\"basis\"");
    }
}
