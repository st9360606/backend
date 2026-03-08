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
import java.util.List;
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
            long t0,
            String foodNameHint
    ) {
        try {
            PackageProductCandidate extracted = null;

            // ✅ 先嘗試只用前面主流程已拿到的 foodNameHint 建 candidate，
            // 這樣 OFF fallback 不會再額外打第 3 次 Gemini。
            PackageProductCandidate candidate = buildSearchCandidate(null, foodNameHint);

            if (candidate == null || !candidate.packagedFood() || !candidate.hasUsefulName()) {
                extracted = extractPackageCandidate(imageBytes, mimeType, modelId, entity.getId());
                candidate = buildSearchCandidate(extracted, foodNameHint);
            }

            if (candidate == null) {
                log.info("package_candidate_unusable foodLogId={} foodNameHint={}",
                        entity.getId(), safe(foodNameHint));
                return Optional.empty();
            }

            if (!candidate.packagedFood()) {
                log.info("package_candidate_not_packaged foodLogId={} candidate={}",
                        entity.getId(), candidate.debugLabel());
                return Optional.empty();
            }

            if (!candidate.hasUsefulName()) {
                log.info("package_candidate_missing_name foodLogId={} candidate={}",
                        entity.getId(), candidate.debugLabel());
                return Optional.empty();
            }

            log.info("package_candidate_ok foodLogId={} fromHintOnly={} candidate={}",
                    entity.getId(),
                    extracted == null,
                    candidate.debugLabel());

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
                log.info("package_name_search_no_hit foodLogId={} candidate={}",
                        entity.getId(), candidate.debugLabel());
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

    private static PackageProductCandidate buildSearchCandidate(
            PackageProductCandidate extracted,
            String foodNameHint
    ) {
        String hint = cleanHint(foodNameHint);

        String rawBrand = extracted == null ? null : cleanHint(extracted.brand());
        String rawProductName = firstNonBlank(
                extracted == null ? null : extracted.productName(),
                hint
        );
        String rawVariant = extracted == null ? null : cleanHint(extracted.variant());
        String rawSizeText = extracted == null ? null : cleanHint(extracted.sizeText());
        String rawVisibleBarcode = extracted == null ? null : cleanHint(extracted.visibleBarcode());

        boolean packaged = (extracted != null && extracted.packagedFood()) || rawProductName != null;
        if (!packaged || rawProductName == null) {
            return null;
        }

        NameSplit split = splitBrandAndProduct(rawBrand, rawProductName);

        List<String> aliases = mergeAliases(
                extracted == null ? null : extracted.searchAliases(),
                rawProductName,
                hint,
                split.productName()
        );

        return new PackageProductCandidate(
                true,
                split.brand(),
                split.productName(),
                rawVariant,
                rawSizeText,
                rawVisibleBarcode,
                aliases
        );
    }

    private static NameSplit splitBrandAndProduct(String brand, String mergedName) {
        String b = cleanHint(brand);
        String name = cleanHint(mergedName);

        if (name == null) {
            return new NameSplit(b, null);
        }

        // 已有 brand：直接用
        if (b != null) {
            return new NameSplit(b, name);
        }

        String[] tokens = name.split("\\s+");

        // 保守：只在 2~4 個 token 時做 first-token brand split
        if (tokens.length < 2 || tokens.length > 4) {
            return new NameSplit(null, name);
        }

        String first = cleanHint(tokens[0]);
        String rest = joinTokens(tokens, 1, tokens.length);

        if (!looksLikeBrandToken(first) || rest == null) {
            return new NameSplit(null, name);
        }

        return new NameSplit(first, rest);
    }

    private static boolean looksLikeBrandToken(String token) {
        if (token == null || token.isBlank()) return false;
        if (token.length() < 4) return false;

        boolean hasLetter = token.chars().anyMatch(Character::isLetter);
        if (!hasLetter) return false;

        String lower = token.toLowerCase(java.util.Locale.ROOT);

        // generic stop words，不是 descriptor list，只是避免把 "the/of/with" 這種切成 brand
        return !lower.equals("the")
               && !lower.equals("of")
               && !lower.equals("with")
               && !lower.equals("and");
    }

    private static String joinTokens(String[] tokens, int startInclusive, int endExclusive) {
        StringBuilder sb = new StringBuilder();
        for (int i = startInclusive; i < endExclusive; i++) {
            String t = cleanHint(tokens[i]);
            if (t == null) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(t);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private record NameSplit(String brand, String productName) {
    }

    private static String cleanHint(String s) {
        if (s == null) return null;
        String t = s.trim().replaceAll("\\s+", " ");
        return t.isBlank() ? null : t;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    private static String safe(String s) {
        return (s == null || s.isBlank()) ? null : s;
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
                textOrNull(parsed, "visibleBarcode"),
                textList(parsed, "searchAliases")
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
                Decide whether the MAIN subject is a packaged food product.
                
                Important:
                - The image may be a direct photo of the product package.
                - Or it may be a screenshot / a photo of a screen / a viewer window showing the product package.
                - Even if there are UI overlays, app toolbars, screen borders, glare, hands, fingers, background people, or shelves,
                  treat those as NON-PRODUCT context.
                
                The visible package text may be in ANY language or script, including:
                English, Japanese, Korean, Traditional Chinese, Simplified Chinese,
                Arabic, Cyrillic, Thai, Vietnamese, Malay, French, German, Spanish, Latin, Hebrew, Devanagari, etc.
                
                Return JSON with exactly these keys:
                - packagedFood: boolean
                - brand: string|null
                - productName: string|null
                - variant: string|null
                - sizeText: string|null
                - visibleBarcode: string|null
                - searchAliases: string[]
                
                Rules:
                - packagedFood=true if the main visible subject is clearly a packaged food / snack / drink / retail food product,
                  even when shown on a screen.
                - brand: most likely brand name from visible package text.
                - productName: most likely primary sellable product name.
                - searchAliases: include other visible product-name strings / scripts exactly as printed on the package front.
                  Examples:
                  - native script product name
                  - romanized / Latin banner text
                  - alternate visible product-name wording
                - Keep aliases exactly as seen. Do NOT translate. Do NOT invent.
                - Exclude pure brand duplicates from searchAliases.
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
        gen.put("maxOutputTokens", 320);
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

        ObjectNode searchAliases = props.putObject("searchAliases");
        searchAliases.put("type", "array");
        searchAliases.putObject("items").put("type", "string");

        schema.putArray("required")
                .add("packagedFood")
                .add("brand")
                .add("productName")
                .add("variant")
                .add("sizeText")
                .add("visibleBarcode")
                .add("searchAliases");

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

        // ✅ 關鍵修正：
        // 只有「條碼直接命中」才允許 whole-package scale
        // NAME_SEARCH 不允許，避免同名不同包裝被放大成整包
        boolean allowWholePackageScale =
                "AUTO_BARCODE".equals(source)
                || "VISIBLE_BARCODE_OCR".equals(source);

        String basis = OffEffectiveBuilder.applyPortion(eff, off, allowWholePackageScale);
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

        // ✅ NAME_SEARCH 命中的 package size 不可信，補 warning 方便前端/trace 觀察
        if ("NAME_SEARCH".equals(source)) {
            eff.withArray("warnings").add("PACKAGE_SIZE_UNVERIFIED");
        }

        OffCategoryResolver.Resolved resolved = OffCategoryResolver.resolve(off);

        ObjectNode aiMeta = eff.putObject("aiMeta");
        aiMeta.put("source", source);
        aiMeta.put("packageResolved", true);
        aiMeta.put("offFromCache", offFromCache);
        aiMeta.put("basis", basis);
        aiMeta.put("allowWholePackageScale", allowWholePackageScale);
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

    private static List<String> mergeAliases(List<String> existing, String... extras) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();

        if (existing != null) {
            for (String s : existing) {
                addAlias(out, s);
            }
        }

        if (extras != null) {
            for (String s : extras) {
                addAlias(out, s);
            }
        }

        return new java.util.ArrayList<>(out);
    }

    private static void addAlias(java.util.Set<String> out, String raw) {
        String s = cleanHint(raw);
        if (s == null) return;
        out.add(s);
    }

    private static List<String> textList(JsonNode node, String field) {
        if (node == null || node.isNull()) return List.of();

        JsonNode arr = node.get(field);
        if (arr == null || !arr.isArray()) return List.of();

        List<String> out = new java.util.ArrayList<>();
        for (JsonNode it : arr) {
            if (it == null || it.isNull()) continue;
            String s = it.asText(null);
            if (s != null && !s.isBlank()) {
                out.add(s.trim());
            }
        }
        return out;
    }
}
