package com.calai.backend.foodlog.packagedfood;

import com.calai.backend.foodlog.barcode.mapper.OpenFoodFactsMapper;
import com.calai.backend.foodlog.barcode.mapper.OpenFoodFactsMapper.OffResult;
import com.calai.backend.foodlog.mapper.OffEffectiveBuilder;
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
        if (candidate == null || !candidate.packagedFood()) return Optional.empty();

        SearchHit best = null;

        List<String> queries = candidate.searchQueries().stream()
                .filter(q -> q != null && !q.isBlank())
                .distinct()
                .toList();

        for (String query : queries) {
            List<SearchHit> hits = search(query, candidate, langKey);
            for (SearchHit h : hits) {
                if (best == null || h.score() > best.score()) {
                    best = h;
                }
            }
        }

        if (best == null || !searchPolicy.isAcceptableScore(best.score())) {
            return Optional.empty();
        }
        return Optional.of(best);
    }

    /**
     * 給 provider 端判斷是否要補 LOW_CONFIDENCE warning。
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
            if (products == null || !products.isArray()) return List.of();

            List<SearchHit> out = new ArrayList<>();

            for (JsonNode p : products) {
                OffResult off = OpenFoodFactsMapper.mapProduct(p, langKey);
                if (off == null) continue;
                if (!OffEffectiveBuilder.hasCoreNutrition(off)) continue;

                String productName = firstNonBlank(
                        text(p, "product_name_" + safeLang),
                        text(p, "product_name"),
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

        String offName = norm(firstNonBlank(
                text(productNode, "product_name_" + safeLang(langKey)),
                text(productNode, "product_name"),
                off.productName()
        ));
        String offBrands = norm(text(productNode, "brands"));
        String offQuantity = text(productNode, "quantity");
        String normQuery = norm(query);

        double score = 0.05;

        if (offName != null && normQuery != null && offName.contains(normQuery)) {
            score += 0.35;
        }

        score += tokenCoverageScore(candName, offName) * 0.35;
        score += tokenCoverageScore(candVariant, offName) * 0.12;
        score += tokenCoverageScore(candBrand, offBrands) * 0.20;

        // ✅ P1-5：先 normalize unit 再比較 size
        score += searchPolicy.sizeSimilarityScore(candSize, offQuantity) * 0.08;

        if (OffEffectiveBuilder.hasCoreNutrition(off)) score += 0.15;
        if (hasServingOrPackageInfo(off)) score += 0.05;

        return Math.min(score, 0.99);
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