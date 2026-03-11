package com.calai.backend.foodlog.provider.label;

import com.calai.backend.foodlog.config.AiModelRouter;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.mapper.ProviderErrorMapper;
import com.calai.backend.foodlog.model.ModelMode;
import com.calai.backend.foodlog.provider.GeminiEffectiveJsonSupport;
import com.calai.backend.foodlog.provider.GeminiJsonParsingSupport;
import com.calai.backend.foodlog.provider.GeminiModeProcessor;
import com.calai.backend.foodlog.provider.GeminiVisionRoutePolicy;
import com.calai.backend.foodlog.provider.ImageMimeResolver;
import com.calai.backend.foodlog.provider.config.GeminiEnabledComponent;
import com.calai.backend.foodlog.provider.prompt.GeminiPromptFactory;
import com.calai.backend.foodlog.provider.transport.GeminiTransportSupport;
import com.calai.backend.foodlog.quota.model.ModelTier;
import com.calai.backend.foodlog.storage.StorageService;
import com.calai.backend.foodlog.task.ProviderClient;
import com.calai.backend.foodlog.task.ProviderTelemetry;
import com.calai.backend.foodlog.unit.FoodLogWarning;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.Locale;

@Slf4j
@GeminiEnabledComponent
public class GeminiLabelProcessor implements GeminiModeProcessor {

    private static final int PREVIEW_LEN = 200;

    private final ObjectMapper om;
    private final ProviderTelemetry telemetry;
    private final AiModelRouter modelRouter;
    private final GeminiJsonParsingSupport jsonParsingSupport;
    private final GeminiLabelFallbackSupport labelFallbackSupport;
    private final GeminiTransportSupport transportSupport;
    private final GeminiPromptFactory promptFactory;

    public GeminiLabelProcessor(
            ObjectMapper om,
            ProviderTelemetry telemetry,
            AiModelRouter modelRouter,
            GeminiJsonParsingSupport jsonParsingSupport,
            GeminiTransportSupport transportSupport,
            GeminiPromptFactory promptFactory
    ) {
        this.om = om;
        this.telemetry = telemetry;
        this.modelRouter = modelRouter;
        this.jsonParsingSupport = jsonParsingSupport;
        this.transportSupport = transportSupport;
        this.promptFactory = promptFactory;
        this.labelFallbackSupport = new GeminiLabelFallbackSupport(om);
    }

    @Override
    public boolean supports(String method) {
        return GeminiVisionRoutePolicy.isLabel(method);
    }

    @Override
    public ProviderClient.ProviderResult process(FoodLogEntity entity, StorageService storage) throws Exception {
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

            log.info(
                    "label_route_policy foodLogId={} method={} maxGeminiCalls={}",
                    entity.getId(),
                    entity.getMethod(),
                    GeminiVisionRoutePolicy.maxGeminiCalls(entity.getMethod())
            );

            modelId = resolveModelIdVision(entity);
            String promptMain = promptFactory.mainPrompt(true);

            log.info("gemini_call_1_main_start foodLogId={} method={} modelId={} isLabel={}",
                    entity.getId(), entity.getMethod(), modelId, true);

            CallResult r1 = callAndExtract(bytes, mime, promptMain, modelId, true, entity.getId());
            Tok tok = r1.tok();

            JsonNode parsed = (r1.functionArgs() != null) ? r1.functionArgs() : tryParseJson(r1.text());
            parsed = unwrapRootObjectOrNull(parsed);

            log.info(
                    "gemini_call_1_main_end foodLogId={} hasFunctionArgs={} parsedNull={} nutrientCount={} confidence={} textPreview={}",
                    entity.getId(),
                    r1.functionArgs() != null,
                    parsed == null,
                    nutrientCountSafe(parsed),
                    confidenceOrNull(parsed),
                    previewOrEmpty(r1.text())
            );

            // 只允許本地 repair / extract，不再做第二次 Gemini
            if (parsed == null) {
                ObjectNode repaired = GeminiLabelJsonRepairUtil.repairOrExtract(om, r1.text(), true);
                if (repaired != null) {
                    parsed = repaired;
                }
            }

            if (parsed == null && isJsonTruncated(r1.text())) {
                ObjectNode fixed = tryFixTruncatedJson(r1.text());
                if (fixed != null) {
                    parsed = fixed;
                }
            }

            if (parsed == null) {
                ObjectNode fromBrokenJson = tryExtractFromBrokenJsonLikeText(r1.text());
                if (fromBrokenJson != null) {
                    parsed = fromBrokenJson;
                }
            }

            if (parsed == null) {
                ObjectNode fromText = tryExtractLabelFromPlainText(r1.text());
                if (fromText != null) {
                    parsed = fromText;
                }
            }

            ObjectNode raw;

            if (parsed != null && parsed.isObject()) {
                raw = ((ObjectNode) parsed).deepCopy();
                ensureLabelRequiredKeys(raw);

                // provider 明確回 no-label
                if (hasWarning(raw, FoodLogWarning.NO_LABEL_DETECTED.name())) {
                    ObjectNode effective = normalizeToEffective(raw);
                    return okAndReturn(modelId, entity, t0, tok, true, raw, effective);
                }

                // 空殼 / 幾乎無內容
                if (isLabelEmptyArgs(raw)) {
                    raw = fallbackLabelPartialDetected(r1.text());
                    ObjectNode effective = normalizeToEffective(raw);
                    return okAndReturn(modelId, entity, t0, tok, true, raw, effective);
                }

                if (isLabelIncomplete(raw)) {
                    addWarningIfMissing(raw, FoodLogWarning.LABEL_PARTIAL);
                    addWarningIfMissing(raw, FoodLogWarning.LOW_CONFIDENCE);

                    // ✅ 不再強行覆蓋 confidence，保留 Gemini 原值；若沒有就維持 null
                    ObjectNode effective = normalizeToEffective(raw);
                    return okAndReturn(modelId, entity, t0, tok, true, raw, effective);
                }

                ObjectNode effective = normalizeToEffective(raw);
                return okAndReturn(modelId, entity, t0, tok, true, raw, effective);
            }

            // 完全 parse 不出來時的 deterministic fallback
            if (isNoLabelLikely(r1.text())) {
                raw = fallbackNoLabelDetected();
            } else if (looksLikeStartedJsonLabel(r1.text()) || isJsonTruncated(r1.text())) {
                raw = fallbackLabelPartialDetected(r1.text());
            } else {
                raw = fallbackNoLabelDetected();
            }

            ObjectNode effective = normalizeToEffective(raw);
            return okAndReturn(modelId, entity, t0, tok, true, raw, effective);

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

    private boolean isLabelIncomplete(JsonNode root) {
        return labelFallbackSupport.isLabelIncomplete(root);
    }

    private boolean isNoLabelLikely(String rawText) {
        return labelFallbackSupport.isNoLabelLikely(rawText);
    }

    private ObjectNode fallbackNoLabelDetected() {
        return labelFallbackSupport.fallbackNoLabelDetected();
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

    private void ensureLabelRequiredKeys(ObjectNode obj) {
        labelFallbackSupport.ensureLabelRequiredKeys(obj);
    }

    private static void addWarningIfMissing(ObjectNode raw, FoodLogWarning w) {
        GeminiLabelFallbackSupport.addWarningIfMissing(raw, w);
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
                        foodLogIdForLog,
                        false
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

    private String resolveModelIdVision(FoodLogEntity entity) {
        ModelTier tier = resolveTierFromDegradeLevel(entity.getDegradeLevel());
        AiModelRouter.Resolved r = modelRouter.resolveOrThrow(tier, ModelMode.VISION);
        return r.modelId();
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

    private JsonNode tryParseJson(String rawText) {
        return jsonParsingSupport.tryParseJson(rawText);
    }

    private ObjectNode normalizeToEffective(JsonNode raw) {
        return GeminiEffectiveJsonSupport.normalizeToEffective(raw);
    }

    private static boolean hasWarning(JsonNode root, String code) {
        return GeminiEffectiveJsonSupport.hasWarning(root, code);
    }

    private static JsonNode unwrapRootObjectOrNull(JsonNode raw) {
        return GeminiEffectiveJsonSupport.unwrapRootObjectOrNull(raw);
    }

    private ProviderClient.ProviderResult okAndReturn(
            String modelIdForTelemetry,
            FoodLogEntity entity,
            long t0,
            Tok tok,
            boolean isLabel,
            JsonNode rawForFinalize,
            ObjectNode effective
    ) {
        finalizeEffective(isLabel, rawForFinalize, effective);
        normalizeWholePackageQuantity(effective);

        telemetry.ok(
                "GEMINI",
                modelIdForTelemetry,
                entity.getId(),
                msSince(t0),
                tok.promptTok(),
                tok.candTok(),
                tok.totalTok()
        );
        return new ProviderClient.ProviderResult(effective, "GEMINI");
    }

    private static void finalizeEffective(boolean isLabel, JsonNode rawForFinalize, ObjectNode effective) {
        GeminiEffectiveJsonSupport.finalizeEffective(isLabel, rawForFinalize, effective);
    }

    /**
     * 只做最終 quantity 補正，不再把 whole-package 改成 SERVING。
     */
    private static void normalizeWholePackageQuantity(ObjectNode effective) {
        if (effective == null) {
            return;
        }

        ObjectNode quantity = effective.with("quantity");

        Double value = GeminiEffectiveJsonSupport.parseNumberNodeOrText(quantity.get("value"));
        if (value == null || value <= 0) {
            quantity.put("value", 1.0);
        } else {
            quantity.put("value", value);
        }

        String unit = GeminiEffectiveJsonSupport.normalizeUnit(quantity.path("unit").asText(null));
        quantity.put("unit", unit);
    }

    private static int nutrientCount(JsonNode root) {
        root = unwrapRootObjectOrNull(root);
        if (root == null || !root.isObject()) return 0;

        JsonNode n = root.get("nutrients");
        if (n == null || !n.isObject()) return 0;

        int count = 0;
        for (String k : new String[]{"kcal", "protein", "fat", "carbs", "fiber", "sugar", "sodium"}) {
            JsonNode v = n.get(k);
            if (v != null && !v.isNull()) {
                count++;
            }
        }
        return count;
    }

    private static int nutrientCountSafe(JsonNode root) {
        try {
            return nutrientCount(root);
        } catch (Exception ignore) {
            return -1;
        }
    }

    private static Double confidenceOrNull(JsonNode root) {
        root = unwrapRootObjectOrNull(root);
        if (root == null || !root.isObject()) return null;

        JsonNode c = root.get("confidence");
        if (c == null || c.isNull()) return null;
        if (c.isNumber()) return c.asDouble();

        if (c.isTextual()) {
            try {
                return Double.parseDouble(c.asText().trim());
            } catch (Exception ignore) {
                return null;
            }
        }
        return null;
    }

    private boolean isJsonTruncated(String text) {
        return jsonParsingSupport.isJsonTruncated(text);
    }

    private static String previewOrEmpty(String s) {
        String v = safeOneLine200(s);
        return v == null ? "" : v;
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

    private record CallResult(Tok tok, String text, JsonNode functionArgs) {
    }

    private record Tok(Integer promptTok, Integer candTok, Integer totalTok) {
    }
}
