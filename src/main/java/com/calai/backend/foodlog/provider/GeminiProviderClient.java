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
    private static final Pattern P_FOOD_NAME_JSON_CLOSED =
            Pattern.compile("\"foodName\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\]){1,160})\"");
    private static final Pattern P_FOOD_NAME_JSON_OPEN =
            Pattern.compile("\"foodName\"\\s*:\\s*\"([^\\r\\n]{1,200})", Pattern.DOTALL);
    private static final Pattern P_PRODUCT_NAME_JSON_CLOSED =
            Pattern.compile("\"productName\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\]){1,160})\"");
    private static final Pattern P_PRODUCT_NAME_JSON_OPEN =
            Pattern.compile("\"productName\"\\s*:\\s*\"([^\\r\\n]{1,200})", Pattern.DOTALL);

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

            final String method = entity.getMethod();
            final boolean isLabel = GeminiVisionRoutePolicy.isLabel(method);
            final boolean isPhotoOrAlbum = GeminiVisionRoutePolicy.isPhotoOrAlbum(method);

            log.info(
                    "vision_route_policy foodLogId={} method={} geminiOnly={} maxGeminiCalls={} allowOffLocalFastPath={} allowOffNameSearchFallback={}",
                    entity.getId(),
                    method,
                    isPhotoOrAlbum,
                    GeminiVisionRoutePolicy.maxGeminiCalls(method),
                    GeminiVisionRoutePolicy.allowOffLocalFastPath(method),
                    GeminiVisionRoutePolicy.allowOffNameSearchFallback(method)
            );

            modelId = resolveModelIdVision(entity);
            final String promptMain = promptFactory.mainPrompt(isLabel);

            if (GeminiVisionRoutePolicy.allowOffLocalFastPath(method)) {
                Optional<ProviderResult> autoBarcodeResolved =
                        tryResolvePackagedFoodByLocalBarcode(entity, bytes, t0);

                if (autoBarcodeResolved.isPresent()) {
                    return autoBarcodeResolved.get();
                }
            }

            log.info("gemini_call_1_main_start foodLogId={} method={} modelId={} isLabel={}",
                    entity.getId(), entity.getMethod(), modelId, isLabel);

            CallResult r1 = callAndExtract(bytes, mime, promptMain, modelId, isLabel, entity.getId());
            Tok tok = r1.tok;

            JsonNode parsed1 = (r1.functionArgs != null) ? r1.functionArgs : tryParseJson(r1.text);
            parsed1 = unwrapRootObjectOrNull(parsed1);

            if (!isLabel && shouldDiscardTruncatedPartialPhotoResult(parsed1, r1.text)) {
                log.warn("photo_main_truncated_partial_json_discarded foodLogId={} preview={}",
                        entity.getId(), safeOneLine200(r1.text));
                parsed1 = null;
            }

            log.info(
                    "gemini_call_1_main_end foodLogId={} hasFunctionArgs={} parsedNull={} nutrientCount={} confidence={} textPreview={}",
                    entity.getId(),
                    r1.functionArgs != null,
                    parsed1 == null,
                    nutrientCountSafe(parsed1),
                    confidenceOrNull(parsed1),
                    previewOrEmpty(r1.text)
            );

            String foodNameHint = extractFoodNameHint(parsed1, r1.text);
            ObjectNode bestPackagedProvisional = null;

            BestLabel best = emptyBestLabel();
            if (isLabel) {
                best = best.consider(parsed1);
            }

            if (isLabel) {
                // LABEL 流程保持你原本邏輯
                if (parsed1 == null) {
                    log.warn("label_parse_failed foodLogId={} preview={}",
                            entity.getId(), safeOneLine200(r1.text));
                }

                if (parsed1 == null) {
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

                if (parsed1 == null && isJsonTruncated(r1.text)) {
                    log.info("label_json_truncated_try_fix foodLogId={}", entity.getId());
                    ObjectNode fromTruncated = tryFixTruncatedJson(r1.text);
                    if (fromTruncated != null && hasAnyNutrientValue(fromTruncated)) {
                        ObjectNode effective = normalizeToEffective(fromTruncated);
                        return okAndReturn(modelId, entity, t0, tok, true, fromTruncated, effective);
                    }
                }

                if (parsed1 != null && hasWarning(parsed1, FoodLogWarning.NO_LABEL_DETECTED.name())) {
                    ObjectNode effective = normalizeToEffective(parsed1);
                    return okAndReturn(modelId, entity, t0, tok, true, parsed1, effective);
                }

                if (parsed1 != null && isLabelEmptyArgs(parsed1)) {
                    ObjectNode partial = fallbackLabelPartialDetected(r1.text);
                    ObjectNode effective = normalizeToEffective(partial);
                    return okAndReturn(modelId, entity, t0, tok, true, partial, effective);
                }

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

                final String baseRepairInput =
                        (parsed1 != null) ? parsed1.toString()
                                : (r1.text != null ? r1.text : "");

                final boolean allowTextRepair =
                        !baseRepairInput.isBlank()
                        && (parsed1 == null || isLabelIncomplete(parsed1) || !hasAnyNutrientValue(parsed1));

                final List<String> repairModelIds = new ArrayList<>();
                if (allowTextRepair) {
                    repairModelIds.add(resolveModelIdTextByTier(ModelTier.MODEL_TIER_LOW));
                }

                String lastText = baseRepairInput;

                for (String modelIdText : repairModelIds) {
                    String repairPromptText = promptFactory.buildTextOnlyRepairPrompt(true, lastText);
                    CallResult rn = callAndExtractTextOnly(
                            repairPromptText,
                            modelIdText,
                            entity.getId(),
                            false
                    );

                    tok = tok.plus(rn.tok);
                    lastText = rn.text;

                    JsonNode parsedN = tryParseJson(rn.text);
                    parsedN = unwrapRootObjectOrNull(parsedN);

                    best = best.consider(parsedN);

                    if (parsedN != null && hasWarning(parsedN, FoodLogWarning.NO_LABEL_DETECTED.name())) {
                        ObjectNode effective = normalizeToEffective(parsedN);
                        return okAndReturn(modelId, entity, t0, tok, true, parsedN, effective);
                    }

                    if (hasAnyNutrientValue(parsedN)) {
                        if (isLabelIncomplete(parsedN)) {
                            log.warn("label_incomplete_after_text_repair foodLogId={} modelIdText={} preview={}",
                                    entity.getId(), modelIdText, safeOneLine200(rn.text));
                        } else {
                            ObjectNode effective = normalizeToEffective(parsedN);
                            return okAndReturn(modelIdText, entity, t0, tok, true, parsedN, effective);
                        }
                    }

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
                }

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

            boolean noFoodDetected = isNoFoodDetected(parsed1, r1.text);
            boolean mainHasNutrition = hasAnyNutrientValue(parsed1);

            log.info("photo_main_result foodLogId={} mainHasNutrition={} noFoodDetected={} preview={}",
                    entity.getId(),
                    mainHasNutrition,
                    noFoodDetected,
                    safeOneLine200(r1.text));

            if (mainHasNutrition) {
                ObjectNode effective = normalizeToEffective(parsed1);
                ObjectNode gateView = buildGateView(parsed1, effective);

                if (GeminiNutritionQualityGate.isAcceptablePhoto(gateView)) {
                    return okAndReturn(modelId, entity, t0, tok, false, parsed1, effective);
                }

                log.warn("nutrition_rejected_by_quality_gate foodLogId={} preview={}",
                        entity.getId(), safeOneLine200(r1.text));
            }

            if (noFoodDetected) {
                ObjectNode fb = fallbackNoFoodDetected();
                ObjectNode effective = normalizeToEffective(fb);
                return okAndReturn(modelId, entity, t0, tok, false, fb, effective);
            }

            String repairInput = buildRepairInputForSecondPass(parsed1, r1.text);
            String modelIdText = resolveModelIdText(entity);

            boolean packagedIdentityStrong =
                    foodNameHint != null
                    && !foodNameHint.isBlank()
                    && looksLikePackagedPartialFromMain(parsed1, r1.text);

// ✅ 第一輪完全空掉 / 沒 parse / 沒 text 時，第二輪不要 text repair，直接帶圖重試
            boolean shouldDoImageRetry =
                    parsed1 == null
                    || (!mainHasNutrition && (r1.text == null || r1.text.isBlank()));

            String secondPrompt;
            CallResult rn;
            String secondPassModelId;

            boolean wholePackageHint = hasWholePackageHint(parsed1, r1.text);

            if (packagedIdentityStrong) {
                String packagedContext = buildWholePackageKnowledgeContext(parsed1, r1.text, foodNameHint);
                secondPrompt = promptFactory.buildPackagedProductFinalizationPrompt(foodNameHint, packagedContext);
                secondPassModelId = modelId;

                log.info(
                        "gemini_call_2_packaged_finalize_start foodLogId={} modelId={} foodNameHint={} mode=IMAGE_PACKAGED_FINALIZATION wholePackageHint={} contextPreview={}",
                        entity.getId(),
                        secondPassModelId,
                        foodNameHint,
                        wholePackageHint,
                        safeOneLine200(packagedContext)
                );

                rn = callAndExtract(
                        bytes,
                        mime,
                        secondPrompt,
                        secondPassModelId,
                        false,
                        entity.getId(),
                        true
                );

            } else if (shouldDoImageRetry) {
                secondPrompt = promptFactory.buildPhotoRetryPrompt(foodNameHint);
                secondPassModelId = modelId;

                log.info(
                        "gemini_call_2_image_retry_start foodLogId={} modelId={} foodNameHint={} mode=IMAGE_RETRY",
                        entity.getId(),
                        secondPassModelId,
                        foodNameHint
                );

                rn = callAndExtract(
                        bytes,
                        mime,
                        secondPrompt,
                        secondPassModelId,
                        false,
                        entity.getId(),
                        false
                );

            } else {
                secondPrompt = promptFactory.buildTextOnlyRepairPrompt(false, repairInput);
                secondPassModelId = modelIdText;

                log.info("gemini_call_2_text_repair_start foodLogId={} modelId={} foodNameHint={} mode=TEXT_ONLY",
                        entity.getId(), secondPassModelId, foodNameHint);

                rn = callAndExtractTextOnly(
                        secondPrompt,
                        secondPassModelId,
                        entity.getId(),
                        true
                );
            }

            tok = tok.plus(rn.tok);

            JsonNode parsedRepair = (rn.functionArgs != null) ? rn.functionArgs : tryParseJson(rn.text);
            parsedRepair = unwrapRootObjectOrNull(parsedRepair);

            if (packagedIdentityStrong && shouldDiscardTruncatedPartialPhotoResult(parsedRepair, rn.text)) {
                log.warn("photo_second_pass_truncated_partial_json_discarded foodLogId={} preview={}",
                        entity.getId(), safeOneLine200(rn.text));
                parsedRepair = null;
            }

            if (packagedIdentityStrong) {
                log.info(
                        "gemini_call_2_packaged_finalize_end foodLogId={} parsedNull={} nutrientCount={} confidence={} textPreview={}",
                        entity.getId(),
                        parsedRepair == null,
                        nutrientCountSafe(parsedRepair),
                        confidenceOrNull(parsedRepair),
                        previewOrEmpty(rn.text)
                );
            } else {
                log.info(
                        "gemini_call_2_text_repair_end foodLogId={} parsedNull={} nutrientCount={} confidence={} textPreview={}",
                        entity.getId(),
                        parsedRepair == null,
                        nutrientCountSafe(parsedRepair),
                        confidenceOrNull(parsedRepair),
                        previewOrEmpty(rn.text)
                );
            }

            log.info("photo_second_pass_parsed foodLogId={} parsedNull={} nutrientCount={} confidence={}",
                    entity.getId(),
                    parsedRepair == null,
                    nutrientCountSafe(parsedRepair),
                    confidenceOrNull(parsedRepair));

            ObjectNode fixedRepair = ensureFoodNameIfMissing(parsedRepair, foodNameHint, om);
            String combinedRepairText = buildCombinedRepairText(r1.text, rn.text);

            if (packagedIdentityStrong && !hasCompleteCoreQuartet(fixedRepair)) {
                ObjectNode recoveredPackaged =
                        tryExtractPackagedPhotoFromBrokenJsonLikeText(combinedRepairText, foodNameHint);

                if (recoveredPackaged != null && isBetterPackagedResult(recoveredPackaged, fixedRepair)) {
                    fixedRepair = recoveredPackaged;

                    log.info("photo_second_pass_recovered_from_broken_text foodLogId={} nutrientCount={} confidence={}",
                            entity.getId(),
                            nutrientCountSafe(fixedRepair),
                            confidenceOrNull(fixedRepair));
                }
            }

            if (!packagedIdentityStrong && !hasCompleteCoreQuartet(fixedRepair)) {
                ObjectNode fromBrokenRepair = tryExtractFromBrokenJsonLikeText(rn.text);
                ObjectNode fixedBrokenRepair = ensureFoodNameIfMissing(fromBrokenRepair, foodNameHint, om);

                if (fixedBrokenRepair != null && isBetterPackagedResult(fixedBrokenRepair, fixedRepair)) {
                    fixedRepair = fixedBrokenRepair;

                    log.info("photo_second_pass_generic_recovered_from_broken_text foodLogId={} nutrientCount={} confidence={}",
                            entity.getId(),
                            nutrientCountSafe(fixedRepair),
                            confidenceOrNull(fixedRepair));
                }
            }

            if (fixedRepair != null && hasCompleteCoreQuartet(fixedRepair)) {
                ObjectNode effective = normalizeToEffective(fixedRepair);
                ObjectNode gateView = buildGateView(fixedRepair, effective);

                if (GeminiNutritionQualityGate.isAcceptablePhoto(gateView)) {
                    return okAndReturn(secondPassModelId, entity, t0, tok, false, fixedRepair, effective);
                }

                bestPackagedProvisional = fixedRepair.deepCopy();
                log.warn("photo_single_repair_not_accepted_by_gate_but_keep_provisional foodLogId={} nutrientCount={} confidence={}",
                        entity.getId(),
                        nutrientCount(bestPackagedProvisional),
                        confidenceOrNull(bestPackagedProvisional));
            } else {
                log.warn("photo_single_repair_failed foodLogId={} parsedNutrientCount={} preview={}",
                        entity.getId(),
                        nutrientCountSafe(fixedRepair),
                        safeOneLine200(rn.text));
            }

            if (isPhotoOrAlbum && !GeminiVisionRoutePolicy.allowOffNameSearchFallback(method)) {

                log.info("photo_album_gemini_budget foodLogId={} method={} geminiCallsUsed=2 offUsed=false foodNameHint={}",
                        entity.getId(), method, foodNameHint);

                if (bestPackagedProvisional != null && hasCompleteCoreQuartet(bestPackagedProvisional)) {
                    ObjectNode effective = normalizeToEffective(bestPackagedProvisional);
                    ObjectNode gateView = buildGateView(bestPackagedProvisional, effective);

                    if (GeminiNutritionQualityGate.isAcceptablePhoto(gateView)) {
                        return okAndReturn(modelId, entity, t0, tok, false, bestPackagedProvisional, effective);
                    }

                    log.warn("photo_album_provisional_exists_but_rejected foodLogId={} nutrientCount={} confidence={}",
                            entity.getId(),
                            nutrientCountSafe(bestPackagedProvisional),
                            confidenceOrNull(bestPackagedProvisional));
                }

                log.warn("photo_album_final_fallback_unknown foodLogId={} preview={}",
                        entity.getId(), safeOneLine200(repairInput));

                ObjectNode fbUnknown = fallbackUnknownFoodFromBrokenText(repairInput);
                ObjectNode effective = normalizeToEffective(fbUnknown);
                return okAndReturn(modelId, entity, t0, tok, false, fbUnknown, effective);
            }

            log.info("off_fallback_start foodLogId={} mainHasNutrition={} foodNameHint={}",
                    entity.getId(), mainHasNutrition, foodNameHint);

            Optional<ProviderResult> packageResolved =
                    tryResolvePackagedFoodByNameSearch(entity, bytes, mime, modelId, t0, foodNameHint);

            if (packageResolved.isPresent()) {
                log.info("off_fallback_hit foodLogId={} foodNameHint={}",
                        entity.getId(), foodNameHint);
                return packageResolved.get();
            }

            log.info("off_fallback_miss foodLogId={} mainHasNutrition={} preview={}",
                    entity.getId(), mainHasNutrition, safeOneLine200(r1.text));

            if (bestPackagedProvisional != null && hasCompleteCoreQuartet(bestPackagedProvisional)) {
                ObjectNode effective = normalizeToEffective(bestPackagedProvisional);
                ObjectNode gateView = buildGateView(bestPackagedProvisional, effective);

                if (GeminiNutritionQualityGate.isAcceptablePhoto(gateView)) {
                    return okAndReturn(modelIdText, entity, t0, tok, false, bestPackagedProvisional, effective);
                }

                log.warn("photo_provisional_exists_but_still_rejected foodLogId={} nutrientCount={} confidence={}",
                        entity.getId(),
                        nutrientCountSafe(bestPackagedProvisional),
                        confidenceOrNull(bestPackagedProvisional));
            }

            log.warn("photo_final_fallback_unknown foodLogId={} preview={}",
                    entity.getId(), safeOneLine200(repairInput));

            ObjectNode fbUnknown = fallbackUnknownFoodFromBrokenText(repairInput);
            ObjectNode effective = normalizeToEffective(fbUnknown);
            return okAndReturn(modelId, entity, t0, tok, false, fbUnknown, effective);

        } catch (Exception e) {
            ProviderErrorMapper.Mapped mapped = ProviderErrorMapper.map(e);
            telemetry.fail("GEMINI", modelId, entity.getId(), msSince(t0), mapped.code(), mapped.retryAfterSec());
            throw e;
        }
    }

    private ObjectNode tryExtractPackagedPhotoFromBrokenJsonLikeText(String raw, String foodNameHint) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String foodName = firstNonBlank(
                extractFoodNameFromJsonLikeText(raw),
                foodNameHint
        );

        Double kcal = extractJsonNumber(raw, "kcal");
        Double protein = extractJsonNumber(raw, "protein");
        Double fat = extractJsonNumber(raw, "fat");
        Double carbs = extractJsonNumber(raw, "carbs");
        Double fiber = extractJsonNumber(raw, "fiber");
        Double sugar = extractJsonNumber(raw, "sugar");
        Double sodium = extractJsonNumber(raw, "sodium");
        Double confidence = extractJsonNumber(raw, "confidence");
        Double servingsPerContainer = extractJsonNumber(raw, "servingsPerContainer");

        String basis = normalizeBasisOrNull(extractJsonString(raw, "basis"));
        if (basis == null && hasWholePackageHint(null, raw)) {
            basis = "WHOLE_PACKAGE";
        }

        if (foodName == null || kcal == null || protein == null || fat == null || carbs == null) {
            return null;
        }

        ObjectNode root = om.createObjectNode();
        root.put("foodName", foodName);

        ObjectNode quantity = root.putObject("quantity");
        quantity.put("value", 1d);
        quantity.put("unit", "SERVING");

        ObjectNode nutrients = root.putObject("nutrients");
        putNumberOrNull(nutrients, "kcal", kcal);
        putNumberOrNull(nutrients, "protein", protein);
        putNumberOrNull(nutrients, "fat", fat);
        putNumberOrNull(nutrients, "carbs", carbs);
        putNumberOrNull(nutrients, "fiber", fiber);
        putNumberOrNull(nutrients, "sugar", sugar);
        putNumberOrNull(nutrients, "sodium", sodium);

        ObjectNode labelMeta = root.putObject("labelMeta");
        putNumberOrNull(labelMeta, "servingsPerContainer", servingsPerContainer);
        if (basis == null) {
            labelMeta.putNull("basis");
        } else {
            labelMeta.put("basis", basis);
        }

        if ("WHOLE_PACKAGE".equalsIgnoreCase(basis)) {
            quantity.put("value", 1d);
            quantity.put("unit", "SERVING");
        }

        if (confidence == null) {
            confidence = hasCompleteCoreQuartet(root) ? 0.85 : 0.35;
        }
        root.put("confidence", confidence);

        root.putArray("warnings");
        if (confidence != null && confidence < 0.55) {
            root.withArray("warnings").add(FoodLogWarning.LOW_CONFIDENCE.name());
        }

        return root;
    }

    private static void putNumberOrNull(ObjectNode node, String field, Double value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static Double extractJsonNumber(String raw, String field) {
        if (raw == null || raw.isBlank() || field == null || field.isBlank()) {
            return null;
        }

        Pattern p = Pattern.compile(
                "\"" + Pattern.quote(field) + "\"\\s*:\\s*(?:\"([^\"]{1,40})\"|(-?\\d+(?:\\.\\d+)?))",
                Pattern.CASE_INSENSITIVE
        );

        Matcher m = p.matcher(raw);
        Double last = null;

        while (m.find()) {
            String g1 = m.group(1); // quoted
            String g2 = m.group(2); // plain number

            Double parsed = parseLooseNumber(g2 != null ? g2 : g1);
            if (parsed != null) {
                last = parsed;
            }
        }
        return last;
    }

    private static Double parseLooseNumber(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher m = Pattern.compile("(-?\\d+(?:\\.\\d+)?)").matcher(raw);
        if (!m.find()) {
            return null;
        }
        try {
            return Double.parseDouble(m.group(1));
        } catch (Exception ignore) {
            return null;
        }
    }

    private static String extractJsonString(String raw, String field) {
        if (raw == null || raw.isBlank() || field == null || field.isBlank()) {
            return null;
        }
        Pattern p = Pattern.compile(
                "\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]{1,80})\"",
                Pattern.CASE_INSENSITIVE
        );
        Matcher m = p.matcher(raw);

        String last = null;
        while (m.find()) {
            String s = m.group(1);
            if (s != null && !s.isBlank()) {
                last = s.trim();
            }
        }
        return last;
    }

    private static String normalizeBasisOrNull(String basis) {
        if (basis == null || basis.isBlank()) {
            return null;
        }

        String v = basis.trim().toUpperCase(Locale.ROOT);
        if (v.contains("WHOLE_PACKAGE")) {
            return "WHOLE_PACKAGE";
        }
        if (v.contains("PER_SERVING")) {
            return "PER_SERVING";
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }

        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private static String buildWholePackageKnowledgeContext(
            JsonNode parsed,
            String rawText,
            String foodNameHint
    ) {
        String foodName = firstNonBlank(
                extractFoodNameHint(parsed, rawText),
                foodNameHint
        );

        Double servingsPerContainer = null;
        String basis = null;

        JsonNode root = unwrapRootObjectOrNull(parsed);
        if (root != null && root.isObject()) {
            JsonNode lm = root.get("labelMeta");
            if (lm != null && lm.isObject()) {
                servingsPerContainer = parseNumberOrNull(lm.get("servingsPerContainer"));
                basis = normalizeBasisOrNull(
                        lm.get("basis") == null ? null : lm.get("basis").asText(null)
                );
            }
        }

        if (servingsPerContainer == null) {
            servingsPerContainer = extractJsonNumber(rawText, "servingsPerContainer");
        }
        if (basis == null) {
            basis = normalizeBasisOrNull(extractJsonString(rawText, "basis"));
        }
        if (basis == null && hasWholePackageHint(parsed, rawText)) {
            basis = "WHOLE_PACKAGE";
        }

        StringBuilder sb = new StringBuilder(256);
        sb.append("PACKAGED_PRODUCT_CONTEXT").append('\n');

        if (foodName != null) {
            sb.append("foodName=").append(foodName).append('\n');
        }
        if (servingsPerContainer != null) {
            sb.append("servingsPerContainer=").append(servingsPerContainer).append('\n');
        }
        if (basis != null) {
            sb.append("basis=").append(basis).append('\n');
        }

        sb.append("target=WHOLE_PACKAGE_TOTALS").append('\n');
        sb.append("quantity.value=1").append('\n');
        sb.append("quantity.unit=SERVING").append('\n');
        sb.append("ignore_previous_partial_nutrients=true");

        return sb.toString();
    }

    private static String buildRepairInputForSecondPass(JsonNode parsed1, String rawText) {
        JsonNode root = unwrapRootObjectOrNull(parsed1);
        // 只有在第一輪已經拿到完整 core quartet 時，才值得用 structured JSON 當 repair input
        if (root != null && root.isObject() && hasCompleteCoreQuartet(root)) {
            return root.toString();
        }
        // 其他情況都保留原始 raw text，避免 partial parse 把資訊吃掉
        return rawText == null ? "" : rawText;
    }

    private static String buildCombinedRepairText(String firstRawText, String secondRawText) {
        String first = (firstRawText == null) ? "" : firstRawText;
        String second = (secondRawText == null) ? "" : secondRawText;

        if (first.isBlank()) {
            return second;
        }
        if (second.isBlank()) {
            return first;
        }
        return first + "\n" + second;
    }

    private static boolean hasWholePackageHint(JsonNode parsed, String rawText) {
        JsonNode root = unwrapRootObjectOrNull(parsed);
        if (root != null && root.isObject()) {
            JsonNode lm = root.get("labelMeta");
            if (lm != null && lm.isObject()) {
                String basis = lm.path("basis").asText("");
                Double servings = parseNumberOrNull(lm.get("servingsPerContainer"));
                if ("WHOLE_PACKAGE".equalsIgnoreCase(basis)) {
                    return true;
                }
                if ("PER_SERVING".equalsIgnoreCase(basis)
                    && servings != null
                    && servings > 1.0) {
                    return true;
                }
            }
        }
        String lower = rawText == null ? "" : rawText.toLowerCase(Locale.ROOT);
        return lower.contains("\"basis\":\"whole_package\"")
               || (lower.contains("\"basis\":\"per_serving\"")
                   && lower.contains("\"servingspercontainer\":"));
    }

    private static Double parseNumberOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (node.isNumber()) return node.asDouble();
        if (node.isTextual()) {
            try {
                return Double.parseDouble(node.asText().trim());
            } catch (Exception ignore) {
                return null;
            }
        }
        return null;
    }

    private static boolean hasAnyCoreNutrientValue(JsonNode root) {
        root = unwrapRootObjectOrNull(root);
        if (root == null || !root.isObject()) return false;

        JsonNode n = root.get("nutrients");
        if (n == null || !n.isObject()) return false;

        return hasValue(n.get("kcal"))
               || hasValue(n.get("protein"))
               || hasValue(n.get("fat"))
               || hasValue(n.get("carbs"));
    }

    private static boolean hasValue(JsonNode node) {
        return node != null && !node.isNull();
    }

    static int nutrientCount(JsonNode root) {
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

    static Double confidenceOrNull(JsonNode root) {
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

    static boolean isBetterPackagedResult(JsonNode candidate, JsonNode baseline) {
        if (candidate == null) return false;
        if (!hasAnyCoreNutrientValue(candidate)) return false;
        if (baseline == null) return true;

        int cCount = nutrientCount(candidate);
        int bCount = nutrientCount(baseline);

        if (cCount != bCount) {
            return cCount > bCount;
        }

        Double cConf = confidenceOrNull(candidate);
        Double bConf = confidenceOrNull(baseline);

        double c = (cConf == null) ? 0.0 : cConf;
        double b = (bConf == null) ? 0.0 : bConf;

        return c > b;
    }

    private static boolean looksLikePackagedPartialFromMain(JsonNode parsed, String rawText) {
        if (shouldTryPackagedPartialSalvage(parsed, rawText)) {
            return true;
        }

        if (rawText == null || rawText.isBlank()) {
            return false;
        }

        String lower = rawText.toLowerCase(Locale.ROOT);
        return lower.contains("\"foodname\"")
               || lower.contains("\"productname\"")
               || lower.contains("\"labelmeta\"")
               || lower.contains("servingspercontainer")
               || lower.contains("\"basis\"");
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
        return callAndExtract(imageBytes, mimeType, userPrompt, modelId, isLabel, foodLogIdForLog, false);
    }

    private CallResult callAndExtract(
            byte[] imageBytes,
            String mimeType,
            String userPrompt,
            String modelId,
            boolean isLabel,
            String foodLogIdForLog,
            boolean requireCoreNutrition
    ) {
        GeminiTransportSupport.CallResult r =
                transportSupport.callAndExtract(
                        imageBytes,
                        mimeType,
                        userPrompt,
                        modelId,
                        isLabel,
                        foodLogIdForLog,
                        requireCoreNutrition
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

    private CallResult callAndExtractTextOnly(
            String userPrompt,
            String modelId,
            String foodLogIdForLog,
            boolean requireCoreNutrition
    ) {
        String sys = "You are a JSON repair engine. Return ONLY ONE minified JSON object. No markdown. No extra text.";
        return callAndExtractTextOnly(
                userPrompt,
                modelId,
                foodLogIdForLog,
                sys,
                requireCoreNutrition,
                true
        );
    }

    private CallResult callAndExtractTextOnly(
            String userPrompt,
            String modelId,
            String foodLogIdForLog,
            String systemInstruction,
            boolean requireCoreNutrition,
            boolean useStrictJsonSchema
    ) {
        GeminiTransportSupport.CallResult r =
                transportSupport.callAndExtractTextOnly(
                        userPrompt,
                        modelId,
                        foodLogIdForLog,
                        systemInstruction,
                        requireCoreNutrition,
                        useStrictJsonSchema
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
            long t0,
            String foodNameHint
    ) {
        return packagedFoodResolver.tryResolvePackagedFoodByNameSearch(
                entity, imageBytes, mimeType, modelId, t0, foodNameHint
        );
    }

    private static ObjectNode buildGateView(JsonNode rawForFinalize, ObjectNode normalized) {
        ObjectNode copy = normalized.deepCopy();
        GeminiEffectiveJsonSupport.finalizeEffective(false, rawForFinalize, copy);
        normalizeWholePackageQuantity(copy);
        return copy;
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
        normalizeWholePackageQuantity(effective);

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

    private static boolean hasCompleteCoreQuartet(JsonNode root) {
        root = unwrapRootObjectOrNull(root);
        if (root == null || !root.isObject()) return false;

        JsonNode n = root.get("nutrients");
        if (n == null || !n.isObject()) return false;

        return hasValue(n.get("kcal"))
               && hasValue(n.get("protein"))
               && hasValue(n.get("fat"))
               && hasValue(n.get("carbs"));
    }

    private static int nutrientCountSafe(JsonNode root) {
        try {
            return nutrientCount(root);
        } catch (Exception ignore) {
            return -1;
        }
    }

    private static String previewOrEmpty(String s) {
        String v = safeOneLine200(s);
        return v == null ? "" : v;
    }

    private static void normalizeWholePackageQuantity(ObjectNode effective) {
        if (effective == null) {
            return;
        }
        JsonNode labelMeta = effective.get("labelMeta");
        if (labelMeta == null || !labelMeta.isObject()) {
            return;
        }
        String basis = labelMeta.path("basis").asText("");
        if (!"WHOLE_PACKAGE".equalsIgnoreCase(basis)) {
            return;
        }
        ObjectNode quantity = effective.with("quantity");
        quantity.put("value", 1.0);
        quantity.put("unit", "SERVING");
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

        ObjectNode lm = root.putObject("labelMeta");
        lm.putNull("servingsPerContainer");
        lm.putNull("basis");

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

        ObjectNode lm = root.putObject("labelMeta");
        lm.putNull("servingsPerContainer");
        lm.putNull("basis");

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

    private record CallResult(Tok tok, String text, JsonNode functionArgs) {
    }

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

    private static String extractFoodNameHint(JsonNode parsed, String... rawTexts) {
        if (parsed != null && parsed.isObject()) {
            JsonNode fn = parsed.get("foodName");
            if (fn != null && !fn.isNull()) {
                String s = fn.asText(null);
                if (s != null && !s.isBlank()) {
                    return s.trim();
                }
            }
            // ✅ 新增：有些 salvage / candidate JSON 不一定叫 foodName，也可能只有 productName
            JsonNode pn = parsed.get("productName");
            if (pn != null && !pn.isNull()) {
                String s = pn.asText(null);
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
        String s = firstExtractedValue(raw, P_FOOD_NAME_JSON_CLOSED, true);
        if (s != null) return s;

        s = firstExtractedValue(raw, P_FOOD_NAME_JSON_OPEN, false);
        if (s != null) return s;

        s = firstExtractedValue(raw, P_PRODUCT_NAME_JSON_CLOSED, true);
        if (s != null) return s;

        s = firstExtractedValue(raw, P_PRODUCT_NAME_JSON_OPEN, false);
        if (s != null) return s;

        return null;
    }

    private boolean shouldDiscardTruncatedPartialPhotoResult(JsonNode parsed, String rawText) {
        if (parsed == null || !parsed.isObject()) {
            return false;
        }
        if (!isJsonTruncated(rawText)) {
            return false;
        }
        String foodNameHint = extractFoodNameHint(parsed, rawText);
        if (foodNameHint == null || foodNameHint.isBlank()) {
            return false;
        }
        return !hasCompleteCoreQuartet(parsed);
    }

    private static String firstExtractedValue(String raw, Pattern pattern, boolean closedQuoted) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        Matcher m = pattern.matcher(raw);
        if (!m.find()) {
            return null;
        }

        String value = m.group(1);
        return sanitizeJsonLikeStringValue(value, closedQuoted);
    }

    private static String sanitizeJsonLikeStringValue(String raw, boolean closedQuoted) {
        if (raw == null) {
            return null;
        }

        String s = raw
                .replace("\\n", " ")
                .replace("\\r", " ")
                .replace("\\t", " ");

        // ✅ 若是「沒關閉引號」的截斷字串，盡量在常見 JSON 尾巴前截斷
        if (!closedQuoted) {
            int cut = s.length();

            String[] stops = new String[]{
                    "\",",
                    "\"}",
                    "\"]",
                    "\n",
                    "\r"
            };

            for (String stop : stops) {
                int idx = s.indexOf(stop);
                if (idx >= 0 && idx < cut) {
                    cut = idx;
                }
            }

            s = s.substring(0, cut);
        }

        s = s.replace("\\\"", "\"")
                .replace("\\/", "/")
                .replaceAll("[\\u0000-\\u001F]+", " ")
                .replaceAll("\\s+", " ")
                .trim();

        if (s.length() > 160) {
            s = s.substring(0, 160).trim();
        }

        return s.isBlank() ? null : s;
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
