package com.calai.backend.foodlog.packagedfood;

import com.calai.backend.foodlog.barcode.mapper.OpenFoodFactsMapper;
import com.calai.backend.foodlog.barcode.mapper.OpenFoodFactsMapper.OffResult;
import com.calai.backend.foodlog.barcode.off.OpenFoodFactsEffectiveBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Slf4j
@Service
public class OpenFoodFactsSearchService {

    private final RestClient offRestClient;
    private final OpenFoodFactsSearchPolicy searchPolicy;

    @Value("${app.openfoodfacts.name-search.page-size:8}")
    private int pageSize;

    public OpenFoodFactsSearchService(
            @Qualifier("offRestClient") RestClient offRestClient,
            OpenFoodFactsSearchPolicy searchPolicy
    ) {
        this.offRestClient = offRestClient;
        this.searchPolicy = searchPolicy;
    }

    public Optional<SearchHit> searchBest(PackageProductCandidate candidate, String langKey) {
        if (candidate == null || !candidate.packagedFood()) {
            return Optional.empty();
        }
        if (!candidate.hasUsefulName()) {
            return Optional.empty();
        }

        SearchHit best = null;

        List<String> queries = candidate.searchQueries().stream()
                .filter(q -> q != null && !q.isBlank())
                .distinct()
                .toList();

        for (String query : queries) {
            List<SearchHit> hits = search(query, candidate, langKey);

            if (!hits.isEmpty()) {
                SearchHit top = hits.getFirst();
                log.info("off_name_search_top query={} code={} score={} productName={}",
                        query, top.code(), top.score(), top.productName());
            }

            for (SearchHit h : hits) {
                if (best == null || h.score() > best.score()) {
                    best = h;
                }
            }
        }

        if (best == null) {
            log.info("off_name_search_empty candidate={}", candidate.debugLabel());
            return Optional.empty();
        }

        if (!searchPolicy.isAcceptableScore(best.score())) {
            log.info("off_name_search_below_threshold candidate={} bestQuery={} bestCode={} bestScore={}",
                    candidate.debugLabel(), best.query(), best.code(), best.score());
            return Optional.empty();
        }

        log.info("off_name_search_queries candidate={} queries={}", candidate.debugLabel(), queries);

        log.info("off_name_search_best candidate={} bestQuery={} bestCode={} bestScore={}",
                candidate.debugLabel(), best.query(), best.code(), best.score());

        return Optional.of(best);
    }

    /**
     * 給 provider 判斷是否要補 LOW_CONFIDENCE warning。
     */
    public boolean isLowConfidenceScore(double score) {
        return searchPolicy.isLowConfidenceScore(score);
    }

    private List<SearchHit> search(String query, PackageProductCandidate candidate, String langKey) {
        try {
            String safeLang = safeLang(langKey);

            JsonNode root = offRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/cgi/search.pl")
                            .queryParam("search_terms", query)
                            .queryParam("search_simple", 1)
                            .queryParam("action", "process")
                            .queryParam("json", 1)
                            .queryParam("page_size", pageSize)
                            .queryParam(
                                    "fields",
                                    String.join(",",
                                            "code",
                                            "product_name",
                                            "product_name_" + safeLang,
                                            "product_name_en",
                                            "product_name_ja",
                                            "product_name_ko",
                                            "product_name_zh",
                                            "generic_name",
                                            "generic_name_en",
                                            "generic_name_ja",
                                            "brands",
                                            "quantity",
                                            "product_quantity",
                                            "product_quantity_unit",
                                            "countries",
                                            "countries_tags",
                                            "nutriments",
                                            "categories",
                                            "categories_tags",
                                            "categories_hierarchy"
                                    )
                            )
                            .build())
                    .retrieve()
                    .body(JsonNode.class);

            JsonNode products = (root == null) ? null : root.get("products");
            if (products == null || !products.isArray()) {
                return List.of();
            }

            List<SearchHit> out = new ArrayList<>();

            for (JsonNode p : products) {
                OffResult off = OpenFoodFactsMapper.mapProduct(p, langKey);
                if (off == null) continue;
                if (!OpenFoodFactsEffectiveBuilder.hasCoreNutrition(off)) continue;

                String productName = firstNonBlank(
                        text(p, "product_name_" + safeLang),
                        text(p, "product_name_en"),
                        text(p, "product_name"),
                        text(p, "product_name_ja"),
                        text(p, "generic_name"),
                        text(p, "generic_name_en"),
                        off.productName()
                );

                double score = score(candidate, p, off, query, langKey);

                out.add(new SearchHit(
                        query,
                        text(p, "code"),
                        productName,
                        score,
                        off
                ));
            }

            out.sort(Comparator.comparingDouble(SearchHit::score).reversed());
            return out;

        } catch (Exception ex) {
            log.debug("off name search failed. query={}, msg={}", query, ex.getMessage());
            return List.of();
        }
    }

    private double score(
            PackageProductCandidate candidate,
            JsonNode productNode,
            OffResult off,
            String query,
            String langKey
    ) {
        String candBrand = norm(candidate.brand());
        String candName = norm(candidate.productName());
        String candVariant = norm(candidate.variant());
        String candSize = candidate.sizeText();
        String normQuery = norm(query);

        String offBrands = norm(text(productNode, "brands"));
        String offQuantity = text(productNode, "quantity");

        String offNameCorpus = buildNameCorpus(productNode, off, langKey);
        String offSearchCorpus = norm(joinNonBlank(offNameCorpus, offBrands));

        double score = 0.05;

        // 1) query 整句命中
        if (offSearchCorpus != null && normQuery != null && offSearchCorpus.contains(normQuery)) {
            score += 0.25;
        }

        // 2) product name 對整體語料庫
        score += tokenCoverageScore(candName, offSearchCorpus) * 0.30;

        // 3) variant
        score += tokenCoverageScore(candVariant, offSearchCorpus) * 0.10;

        // 4) brand
        score += tokenCoverageScore(candBrand, offSearchCorpus) * 0.20;

        // 5) query token coverage：讓 "Bourbon + Chocoliere" 可分別命中
        score += tokenCoverageScore(normQuery, offSearchCorpus) * 0.15;

        // 6) brand + productName 同時命中時補分
        if (candBrand != null
            && candName != null
            && offSearchCorpus != null
            && offSearchCorpus.contains(candBrand)
            && offSearchCorpus.contains(candName)) {
            score += 0.08;
        }

        // 7) size
        score += searchPolicy.sizeSimilarityScore(candSize, offQuantity) * 0.05;

        // 8) nutrition / package bonus
        if (OpenFoodFactsEffectiveBuilder.hasCoreNutrition(off)) score += 0.10;
        if (hasServingOrPackageInfo(off)) score += 0.05;

        return Math.min(score, 0.99);
    }

    private static String buildNameCorpus(JsonNode productNode, OffResult off, String langKey) {
        return norm(joinNonBlank(
                text(productNode, "product_name_" + safeLang(langKey)),
                text(productNode, "product_name_en"),
                text(productNode, "product_name"),
                text(productNode, "product_name_ja"),
                text(productNode, "product_name_ko"),
                text(productNode, "product_name_zh"),
                text(productNode, "generic_name"),
                text(productNode, "generic_name_en"),
                text(productNode, "generic_name_ja"),
                off == null ? null : off.productName()
        ));
    }

    private static double tokenCoverageScore(String left, String right) {
        if (left == null || right == null) return 0.0;

        String[] tokens = left.split("\\s+");
        int all = 0;
        int hit = 0;

        for (String t : tokens) {
            String x = t.trim();
            if (x.length() < 2) continue;
            all++;
            if (right.contains(x)) hit++;
        }

        if (all == 0) return 0.0;
        return (double) hit / all;
    }

    private static boolean hasServingOrPackageInfo(OffResult off) {
        return off != null && (
                (off.packageSizeValue() != null && off.packageSizeValue() > 0)
                || off.kcalPerServing() != null
                || off.proteinPerServing() != null
                || off.fatPerServing() != null
                || off.carbsPerServing() != null
                || off.fiberPerServing() != null
                || off.sugarPerServing() != null
                || off.sodiumMgPerServing() != null
        );
    }

    private static String joinNonBlank(String... values) {
        if (values == null) return null;

        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (v == null || v.isBlank()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(v.trim());
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isNull()) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText(null);
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String safeLang(String langKey) {
        if (langKey == null || langKey.isBlank()) return "en";
        return langKey.replace('-', '_').toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String norm(String s) {
        if (s == null) return null;
        String t = s.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{Nd}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return t.isBlank() ? null : t;
    }

    public record SearchHit(
            String query,
            String code,
            String productName,
            double score,
            OffResult off
    ) {}
}
