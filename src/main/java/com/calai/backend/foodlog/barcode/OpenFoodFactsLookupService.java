package com.calai.backend.foodlog.barcode;

import com.calai.backend.foodlog.barcode.OffHttpException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

@Service
public class OpenFoodFactsLookupService {

    private final OpenFoodFactsClient client;

    public OpenFoodFactsLookupService(OpenFoodFactsClient client) {
        this.client = client;
    }

    private static JsonNode notFoundSentinel() {
        // ✅ 每次 new，避免 ObjectNode 被外部誤改造成 cache 汙染
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        n.put("status", 0);
        return n;
    }

    public static String normalizeLangKey(String raw) {
        String tag = OpenFoodFactsLang.firstLangTagOrNull(raw);
        if (tag == null || tag.isBlank()) return "";
        String norm = tag.trim().replace('_', '-').toLowerCase(Locale.ROOT);
        return "*".equals(norm) ? "" : norm;
    }

    static List<String> fieldsFor(String preferredLangTag) {
        LinkedHashSet<String> set = new LinkedHashSet<>();

        set.add("product_name");
        set.add("product_name_en");
        set.add("nutriments");
        set.add("product_quantity");
        set.add("product_quantity_unit");
        set.add("quantity");

        for (String lang : OpenFoodFactsLang.langCandidates(preferredLangTag)) {
            set.add("product_name_" + lang);
            set.add("product_name_" + lang.replace('-', '_'));
        }
        return new ArrayList<>(set);
    }

    @Cacheable(
            cacheNames = "offProduct",
            cacheManager = "offCacheManager",
            key = "#barcode + '|' + T(com.calai.backend.foodlog.barcode.OpenFoodFactsLookupService).normalizeLangKey(#preferredLangTag)"
    )
    public JsonNode getProduct(String barcode, String preferredLangTag) {
        try {
            return client.getProduct(barcode, fieldsFor(preferredLangTag));
        } catch (OffHttpException ex) {
            // ✅ 404 -> 回 sentinel，讓 @Cacheable 可以快取「查不到」
            if (ex.getStatus() == 404) return notFoundSentinel();
            throw ex;
        }
    }
}