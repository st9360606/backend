package com.calai.backend.foodlog.provider.image;

import com.calai.backend.foodlog.provider.gemini.support.GeminiEffectiveJsonSupport;
import com.calai.backend.foodlog.provider.gemini.support.GeminiJsonParsingSupport;
import com.calai.backend.foodlog.provider.gemini.GeminiModeProcessor;
import com.calai.backend.foodlog.provider.gemini.routing.GeminiVisionRoutePolicy;
import com.calai.backend.foodlog.provider.routing.AiModelTierRouter;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.provider.support.ProviderErrorMapper;
import com.calai.backend.foodlog.model.ModelMode;
import com.calai.backend.foodlog.provider.config.GeminiEnabledComponent;
import com.calai.backend.foodlog.provider.prompt.GeminiPromptFactory;
import com.calai.backend.foodlog.provider.transport.GeminiTransportSupport;
import com.calai.backend.foodlog.provider.util.ImageMimeResolver;
import com.calai.backend.foodlog.quota.model.ModelTier;
import com.calai.backend.foodlog.storage.StorageService;
import com.calai.backend.foodlog.provider.spi.ProviderClient;
import com.calai.backend.foodlog.provider.support.ProviderTelemetry;
import com.calai.backend.foodlog.unit.FoodLogWarning;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.Locale;

/**
 * PHOTO / ALBUM 單次 Gemini 流程。
 * 原則：
 * 1. 只打一次 Gemini
 * 2. 不做 text repair
 * 3. 不做 image retry
 * 4. 不做 OFF fallback
 * 5. 只做 normalize + 輕量品質保護
 */

@Slf4j
@GeminiEnabledComponent
public class GeminiPhotoAlbumProcessor implements GeminiModeProcessor {

    @Override
    public boolean supports(String method) {
        return GeminiVisionRoutePolicy.isPhotoOrAlbum(method);
    }

    private final GeminiTransportSupport transportSupport;
    private final GeminiJsonParsingSupport jsonParsingSupport;
    private final GeminiPromptFactory promptFactory;
    private final ObjectMapper om;
    private final ProviderTelemetry telemetry;
    private final AiModelTierRouter modelRouter;

    public GeminiPhotoAlbumProcessor(
            GeminiTransportSupport transportSupport,
            GeminiJsonParsingSupport jsonParsingSupport,
            GeminiPromptFactory promptFactory,
            ObjectMapper om,
            ProviderTelemetry telemetry,
            AiModelTierRouter modelRouter
    ) {
        this.transportSupport = transportSupport;
        this.jsonParsingSupport = jsonParsingSupport;
        this.promptFactory = promptFactory;
        this.om = om;
        this.telemetry = telemetry;
        this.modelRouter = modelRouter;
    }

    public ProviderClient.ProviderResult process(FoodLogEntity entity, StorageService storage) throws Exception {
        long t0 = System.nanoTime();
        String modelId = resolveModelIdVision(entity);

        try {
            byte[] bytes;
            try (InputStream in = storage.open(entity.getImageObjectKey()).inputStream()) {
                bytes = in.readAllBytes();
            }

            if (bytes.length == 0) {
                throw new IllegalStateException("EMPTY_IMAGE");
            }

            String mime = ImageMimeResolver.resolveOrDefault(entity.getImageContentType(), bytes);
            String prompt = promptFactory.mainPrompt(false);

            GeminiTransportSupport.CallResult result = transportSupport.callAndExtract(
                    bytes,
                    mime,
                    prompt,
                    modelId,
                    false,
                    entity.getId()
            );

            JsonNode parsed = (result.functionArgs() != null)
                    ? result.functionArgs()
                    : jsonParsingSupport.tryParseJson(result.text());

            parsed = GeminiEffectiveJsonSupport.unwrapRootObjectOrNull(parsed);

            if (parsed == null || !parsed.isObject()) {
                return okAndReturn(modelId, entity, t0, result.tok(), fallbackUnknownFood());
            }

            ObjectNode effective;
            try {
                effective = GeminiPhotoAlbumJsonSupport.normalizeToEffective(parsed);
                GeminiPhotoAlbumJsonSupport.finalizeEffective(effective);
            } catch (Exception ex) {
                log.warn("photo_album_normalize_failed foodLogId={} msg={}", entity.getId(), ex.getMessage());
                return okAndReturn(modelId, entity, t0, result.tok(), fallbackUnknownFood());
            }

            if (GeminiEffectiveJsonSupport.hasWarning(parsed, FoodLogWarning.NO_FOOD_DETECTED.name())) {
                return okAndReturn(modelId, entity, t0, result.tok(), effective);
            }

            String foodName = textOrNull(effective, "foodName");
            Double confidence = GeminiEffectiveJsonSupport.parseNumberNodeOrText(effective.get("confidence"));
            boolean lowConfidence = (confidence == null || confidence < 0.5d);

            if (foodName == null) {
                return okAndReturn(modelId, entity, t0, result.tok(), fallbackUnknownFood());
            }

            if (GeminiPhotoAlbumJsonSupport.isWholeContainerLike(effective)
                && GeminiPhotoAlbumJsonSupport.allCoreQuartetZero(effective)
                && lowConfidence) {
                return okAndReturn(
                        modelId,
                        entity,
                        t0,
                        result.tok(),
                        fallbackMissingNutritionFacts(foodName, effective)
                );
            }

            return okAndReturn(modelId, entity, t0, result.tok(), effective);

        } catch (Exception e) {
            ProviderErrorMapper.Mapped mapped = ProviderErrorMapper.map(e);
            telemetry.fail("GEMINI", modelId, entity.getId(), msSince(t0), mapped.code(), mapped.retryAfterSec());
            throw e;
        }
    }

    private ProviderClient.ProviderResult okAndReturn(
            String modelId,
            FoodLogEntity entity,
            long t0,
            GeminiTransportSupport.Tok tok,
            ObjectNode effective
    ) {
        telemetry.ok(
                "GEMINI",
                modelId,
                entity.getId(),
                msSince(t0),
                tok.promptTok(),
                tok.candTok(),
                tok.totalTok()
        );
        return new ProviderClient.ProviderResult(effective, "GEMINI");
    }

    private ObjectNode fallbackMissingNutritionFacts(String foodName, JsonNode hintRoot) {
        ObjectNode root = om.createObjectNode();

        root.put("foodName", foodName);

        ObjectNode q = root.putObject("quantity");
        q.put("value", 1.0);
        q.put("unit", inferWholeContainerFallbackUnit(hintRoot));

        ObjectNode n = root.putObject("nutrients");
        n.put("kcal", 0.0);
        n.put("protein", 0.0);
        n.put("fat", 0.0);
        n.put("carbs", 0.0);
        n.put("fiber", 0.0);
        n.put("sugar", 0.0);
        n.put("sodium", 0.0);

        root.putNull("confidence");

        root.putArray("warnings")
                .add(FoodLogWarning.MISSING_NUTRITION_FACTS.name())
                .add(FoodLogWarning.LOW_CONFIDENCE.name());

        ObjectNode lm = root.putObject("labelMeta");
        lm.putNull("servingsPerContainer");
        lm.put("basis", "ESTIMATED_PORTION");

        return root;
    }

    private ObjectNode fallbackUnknownFood() {
        ObjectNode root = om.createObjectNode();

        root.putNull("foodName");

        ObjectNode q = root.putObject("quantity");
        q.put("value", 1.0);
        q.put("unit", "SERVING");

        ObjectNode n = root.putObject("nutrients");
        n.put("kcal", 0.0);
        n.put("protein", 0.0);
        n.put("fat", 0.0);
        n.put("carbs", 0.0);
        n.put("fiber", 0.0);
        n.put("sugar", 0.0);
        n.put("sodium", 0.0);

        root.putNull("confidence");

        root.putArray("warnings")
                .add(FoodLogWarning.UNKNOWN_FOOD.name())
                .add(FoodLogWarning.LOW_CONFIDENCE.name());

        ObjectNode lm = root.putObject("labelMeta");
        lm.putNull("servingsPerContainer");
        lm.putNull("basis");

        return root;
    }

    private String inferWholeContainerFallbackUnit(JsonNode hintRoot) {
        if (hintRoot == null || !hintRoot.isObject()) {
            return "PACK";
        }
        String rawUnit = hintRoot.path("quantity").path("unit").asText("");
        String unit = rawUnit.trim().toUpperCase(Locale.ROOT);
        if ("CAN".equals(unit)) {
            return "CAN";
        }
        if ("BOTTLE".equals(unit)) {
            return "BOTTLE";
        }
        return "PACK";
    }

    private String resolveModelIdVision(FoodLogEntity entity) {
        ModelTier tier = resolveTierFromDegradeLevel(entity.getDegradeLevel());
        AiModelTierRouter.Resolved resolved = modelRouter.resolveOrThrow(tier, ModelMode.VISION);
        return resolved.modelId();
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

    private static long msSince(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || node.isNull()) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText(null);
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
