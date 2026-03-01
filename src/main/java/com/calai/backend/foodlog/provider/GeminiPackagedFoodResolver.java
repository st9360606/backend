package com.calai.backend.foodlog.provider;

import com.calai.backend.foodlog.barcode.BarcodeLookupService;
import com.calai.backend.foodlog.barcode.mapper.OpenFoodFactsMapper.OffResult;
import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.mapper.OffCategoryResolver;
import com.calai.backend.foodlog.mapper.OffEffectiveBuilder;
import com.calai.backend.foodlog.model.ProviderRefuseReason;
import com.calai.backend.foodlog.packagedfood.ImageBarcodeDetector;
import com.calai.backend.foodlog.packagedfood.OpenFoodFactsSearchService;
import com.calai.backend.foodlog.packagedfood.PackageProductCandidate;
import com.calai.backend.foodlog.provider.config.GeminiProperties;
import com.calai.backend.foodlog.task.FoodLogWarning;
import com.calai.backend.foodlog.task.ProviderClient;
import com.calai.backend.foodlog.task.ProviderTelemetry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.Optional;

@Slf4j
public class GeminiPackagedFoodResolver {

    private static final int PREVIEW_LEN = 200;

    private final RestClient http;
    private final GeminiProperties props;
    private final ObjectMapper om;
    private final ProviderTelemetry telemetry;
    private final ImageBarcodeDetector imageBarcodeDetector;
    private final BarcodeLookupService barcodeLookupService;
    private final OpenFoodFactsSearchService offSearchService;

    public GeminiPackagedFoodResolver(
            RestClient http,
            GeminiProperties props,
            ObjectMapper om,
            ProviderTelemetry telemetry,
            ImageBarcodeDetector imageBarcodeDetector,
            BarcodeLookupService barcodeLookupService,
            OpenFoodFactsSearchService offSearchService
    ) {
        this.http = http;
        this.props = props;
        this.om = om;
        this.telemetry = telemetry;
        this.imageBarcodeDetector = imageBarcodeDetector;
        this.barcodeLookupService = barcodeLookupService;
        this.offSearchService = offSearchService;
    }

    public Optional<ProviderClient.ProviderResult> tryResolvePackagedFoodByLocalBarcode(
            FoodLogEntity entity,
            byte[] imageBytes,
            long t0
    ) {
        try {
            Optional<String> bcOpt = imageBarcodeDetector.detect(imageBytes);
            if (bcOpt.isEmpty()) return Optional.empty();

            String barcode = bcOpt.get();

            BarcodeLookupService.LookupResult lookup = barcodeLookupService.lookupOff(barcode, "en");
            if (lookup == null || !lookup.found() || lookup.off() == null) {
                return Optional.empty();
            }

            if (!OffEffectiveBuilder.hasUsableNutrition(lookup.off())) {
                log.info("package_fastpath_skip_no_nutrition foodLogId={} barcode={}", entity.getId(), barcode);
                return Optional.empty();
            }

            ObjectNode effective = buildEffectiveFromOff(
                    lookup.off(),
                    "AUTO_BARCODE",
                    null,
                    0.99,
                    barcode,
                    lookup.fromCache()
            );

            GeminiEffectiveJsonSupport.roundNutrients1dp(effective);
            telemetry.ok("OPENFOODFACTS", "AUTO_BARCODE", entity.getId(), msSince(t0), null, null, null);
            log.info("package_fastpath_resolved foodLogId={} source=AUTO_BARCODE barcode={}", entity.getId(), barcode);

            return Optional.of(new ProviderClient.ProviderResult(effective, "OPENFOODFACTS"));

        } catch (Exception ex) {
            log.debug("package_fastpath_local_barcode_failed foodLogId={} msg={}", entity.getId(), ex.getMessage());
            return Optional.empty();
        }
    }

    public Optional<ProviderClient.ProviderResult> tryResolvePackagedFoodByNameSearch(
            FoodLogEntity entity,
            byte[] imageBytes,
            String mimeType,
            String modelId,
            long t0
    ) {
        try {
            PackageProductCandidate candidate = extractPackageCandidate(imageBytes, mimeType, modelId, entity.getId());
            if (candidate == null || !candidate.packagedFood()) {
                return Optional.empty();
            }

            String visibleBarcode = candidate.normalizedVisibleBarcode();
            if (visibleBarcode != null) {
                try {
                    BarcodeLookupService.LookupResult lookup = barcodeLookupService.lookupOff(visibleBarcode, "en");
                    if (lookup != null && lookup.found() && lookup.off() != null) {

                        if (!OffEffectiveBuilder.hasUsableNutrition(lookup.off())) {
                            log.info("package_visible_barcode_skip_no_nutrition foodLogId={} barcode={}",
                                    entity.getId(), visibleBarcode);
                        } else {
                            ObjectNode effective = buildEffectiveFromOff(
                                    lookup.off(),
                                    "VISIBLE_BARCODE_OCR",
                                    null,
                                    0.98,
                                    visibleBarcode,
                                    lookup.fromCache()
                            );
                            GeminiEffectiveJsonSupport.roundNutrients1dp(effective);
                            telemetry.ok("OPENFOODFACTS", "VISIBLE_BARCODE_OCR", entity.getId(), msSince(t0), null, null, null);
                            log.info("package_rescue_resolved foodLogId={} source=VISIBLE_BARCODE_OCR barcode={}",
                                    entity.getId(), visibleBarcode);
                            return Optional.of(new ProviderClient.ProviderResult(effective, "OPENFOODFACTS"));
                        }
                    }
                } catch (Exception ex) {
                    log.debug("package_visible_barcode_lookup_failed foodLogId={} msg={}", entity.getId(), ex.getMessage());
                }
            }

            Optional<OpenFoodFactsSearchService.SearchHit> bestOpt = offSearchService.searchBest(candidate, "en");
            if (bestOpt.isEmpty()) {
                log.info("package_name_search_no_hit foodLogId={} candidate={}", entity.getId(), candidate.debugLabel());
                return Optional.empty();
            }

            OpenFoodFactsSearchService.SearchHit best = bestOpt.get();

            if (best.off() == null) {
                log.info("package_name_search_hit_without_off foodLogId={} query={} code={} score={}",
                        entity.getId(), best.query(), best.code(), best.score());
                return Optional.empty();
            }

            if (!OffEffectiveBuilder.hasUsableNutrition(best.off())) {
                log.info("package_name_search_skip_no_nutrition foodLogId={} query={} code={} score={}",
                        entity.getId(), best.query(), best.code(), best.score());
                return Optional.empty();
            }

            ObjectNode effective = buildEffectiveFromOff(
                    best.off(),
                    "NAME_SEARCH",
                    best.query(),
                    best.score(),
                    best.code(),
                    false
            );

            if (offSearchService.isLowConfidenceScore(best.score())) {
                effective.withArray("warnings").add(FoodLogWarning.LOW_CONFIDENCE.name());
            }

            GeminiEffectiveJsonSupport.roundNutrients1dp(effective);
            telemetry.ok("OPENFOODFACTS", "NAME_SEARCH", entity.getId(), msSince(t0), null, null, null);

            log.info("package_name_search_hit foodLogId={} query={} code={} score={}",
                    entity.getId(), best.query(), best.code(), best.score());

            return Optional.of(new ProviderClient.ProviderResult(effective, "OPENFOODFACTS"));

        } catch (Exception ex) {
            log.debug("package_name_search_rescue_failed foodLogId={} msg={}", entity.getId(), ex.getMessage());
            return Optional.empty();
        }
    }

    private PackageProductCandidate extractPackageCandidate(
            byte[] imageBytes,
            String mimeType,
            String modelId,
            String foodLogIdForLog
    ) {
        JsonNode resp;
        try {
            resp = http.post()
                    .uri("/v1beta/models/{model}:generateContent", modelId)
                    .header("x-goog-api-key", requireApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(buildPackageCandidateRequest(imageBytes, mimeType))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception ex) {
            log.debug("package_candidate_call_failed foodLogId={} msg={}", foodLogIdForLog, ex.getMessage());
            return null;
        }

        ProviderRefuseReason reason = GeminiRefusalDetector.detectOrNull(resp);
        if (reason != null) {
            log.debug("package_candidate_refused foodLogId={} reason={}", foodLogIdForLog, reason);
            return null;
        }

        String text = extractJoinedTextOrNull(resp);
        JsonNode parsed = tryParseJsonLoose(text);
        parsed = GeminiEffectiveJsonSupport.unwrapRootObjectOrNull(parsed);

        if (parsed == null || !parsed.isObject()) {
            log.debug("package_candidate_parse_failed foodLogId={} preview={}",
                    foodLogIdForLog, safeOneLine200(text));
            return null;
        }

        return new PackageProductCandidate(
                parsed.path("packagedFood").asBoolean(false),
                textOrNull(parsed, "brand"),
                textOrNull(parsed, "productName"),
                textOrNull(parsed, "variant"),
                textOrNull(parsed, "sizeText"),
                textOrNull(parsed, "visibleBarcode")
        );
    }

    private ObjectNode buildPackageCandidateRequest(byte[] imageBytes, String mimeType) {
        String b64 = Base64.getEncoder().encodeToString(imageBytes);

        ObjectNode root = om.createObjectNode();

        ObjectNode sys = root.putObject("systemInstruction");
        sys.putArray("parts").addObject().put("text",
                "Return ONLY ONE minified JSON object. No markdown. No extra text.");

        var contents = root.putArray("contents");
        ObjectNode c0 = contents.addObject();
        c0.put("role", "user");

        var parts = c0.putArray("parts");
        parts.addObject().put("text", """
                Decide whether this image is primarily a packaged food product.

                Return JSON with exactly these keys:
                - packagedFood: boolean
                - brand: string|null
                - productName: string|null
                - variant: string|null
                - sizeText: string|null
                - visibleBarcode: string|null

                Rules:
                - packagedFood=true only if this is clearly a packaged food / snack / drink / retail food product.
                - Extract the most likely brand and product name from the package front.
                - If barcode digits are visible, return digits only in visibleBarcode.
                - If not visible / unsure, visibleBarcode=null.
                - Do not output nutrition values here.
                """);

        ObjectNode imgPart = parts.addObject();
        ObjectNode inline = imgPart.putObject("inlineData");
        inline.put("mimeType", mimeType);
        inline.put("data", b64);

        ObjectNode gen = root.putObject("generationConfig");
        gen.put("responseMimeType", "application/json");
        gen.set("responseJsonSchema", packageCandidateJsonSchema());
        gen.put("maxOutputTokens", 256);
        gen.put("temperature", 0.0);

        return root;
    }

    private ObjectNode packageCandidateJsonSchema() {
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);

        ObjectNode props = schema.putObject("properties");

        props.putObject("packagedFood").put("type", "boolean");
        props.putObject("brand").putArray("type").add("string").add("null");
        props.putObject("productName").putArray("type").add("string").add("null");
        props.putObject("variant").putArray("type").add("string").add("null");
        props.putObject("sizeText").putArray("type").add("string").add("null");
        props.putObject("visibleBarcode").putArray("type").add("string").add("null");

        schema.putArray("required")
                .add("packagedFood")
                .add("brand")
                .add("productName")
                .add("variant")
                .add("sizeText")
                .add("visibleBarcode");

        return schema;
    }

    private ObjectNode buildEffectiveFromOff(
            OffResult off,
            String source,
            String searchQuery,
            Double matchScore,
            String codeOrBarcode,
            boolean offFromCache
    ) {
        ObjectNode eff = om.createObjectNode();

        String name = (off.productName() == null || off.productName().isBlank())
                ? "Unknown product"
                : off.productName();
        eff.put("foodName", name);

        String basis = OffEffectiveBuilder.applyPortion(eff, off);
        boolean hasCoreNutrition = OffEffectiveBuilder.hasCoreNutrition(off);

        double confidence;
        if ("AUTO_BARCODE".equals(source)) {
            confidence = hasCoreNutrition ? 0.99 : 0.93;
        } else if ("VISIBLE_BARCODE_OCR".equals(source)) {
            confidence = hasCoreNutrition ? 0.98 : 0.92;
        } else {
            confidence = hasCoreNutrition ? 0.94 : 0.90;
        }

        eff.put("confidence", confidence);
        eff.putArray("warnings");

        if (!hasCoreNutrition) {
            eff.withArray("warnings").add(FoodLogWarning.LOW_CONFIDENCE.name());
        }

        OffCategoryResolver.Resolved resolved = OffCategoryResolver.resolve(off);

        ObjectNode aiMeta = eff.putObject("aiMeta");
        aiMeta.put("source", source);
        aiMeta.put("packageResolved", true);
        aiMeta.put("offFromCache", offFromCache);
        aiMeta.put("basis", basis);
        aiMeta.put("hasCoreNutrition", hasCoreNutrition);
        aiMeta.put("foodCategory", resolved.category().name());
        aiMeta.put("foodSubCategory", resolved.subCategory().name());

        if (searchQuery != null && !searchQuery.isBlank()) {
            aiMeta.put("searchQuery", searchQuery);
        }
        if (matchScore != null) {
            aiMeta.put("matchScore", matchScore);
        }
        if (codeOrBarcode != null && !codeOrBarcode.isBlank()) {
            aiMeta.put("productCode", codeOrBarcode);
        }

        return eff;
    }

    private String requireApiKey() {
        String k = props.getApiKey();
        if (k == null || k.isBlank()) throw new IllegalStateException("GEMINI_API_KEY_MISSING");
        return k.trim();
    }

    private JsonNode tryParseJsonLoose(String rawText) {
        if (rawText == null || rawText.isBlank()) return null;

        String s = rawText.trim();
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            if (firstNl > 0) s = s.substring(firstNl + 1);
            int lastFence = s.lastIndexOf("```");
            if (lastFence >= 0) s = s.substring(0, lastFence).trim();
        }

        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            s = s.substring(start, end + 1);
        }

        try {
            return om.readTree(s);
        } catch (Exception ignore) {
            return null;
        }
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

    private static String safeOneLine200(String s) {
        if (s == null) return null;
        String t = s.replace("\r", " ").replace("\n", " ").trim();
        return (t.length() > PREVIEW_LEN) ? t.substring(0, PREVIEW_LEN) : t;
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
