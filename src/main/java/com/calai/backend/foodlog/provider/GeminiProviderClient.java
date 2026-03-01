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

            String mime = (entity.getImageContentType() == null || entity.getImageContentType().isBlank())
                    ? "image/jpeg"
                    : entity.getImageContentType();

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

            if (!isLabel) {
                if (hasAnyNutrientValue(parsed1)) {
                    ObjectNode effective = normalizeToEffective(parsed1);

                    if (GeminiNutritionQualityGate.isAcceptablePhoto(effective)) {
                        return okAndReturn(modelId, entity, t0, tok, false, parsed1, effective);
                    }

                    log.warn("nutrition_implausible_will_repair foodLogId={} preview={}",
                            entity.getId(), safeOneLine200(r1.text));
                }

                if (!hasAnyNutrientValue(parsed1) && !isNoFoodDetected(parsed1, r1.text)) {
                    Optional<ProviderResult> packageResolved =
                            tryResolvePackagedFoodByNameSearch(entity, bytes, mime, modelId, t0);

                    if (packageResolved.isPresent()) {
                        return packageResolved.get();
                    }
                }

                if (isNoFoodDetected(parsed1, r1.text)) {
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
}
