package com.calai.backend.foodlog.barcode.openfoodfacts;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class OpenFoodFactsFields {
    private OpenFoodFactsFields() {}

    public static List<String> fieldsFor(String preferredLangTag) {
        LinkedHashSet<String> set = new LinkedHashSet<>();

        // identity
        set.add("product_name");
        set.add("product_name_en");

        // ✅ US/EU 判斷需要
        set.add("countries");
        set.add("countries_tags");

        // nutrition
        set.add("nutriments");

        // package size
        set.add("product_quantity");
        set.add("product_quantity_unit");
        set.add("quantity");

        for (String lang : OpenFoodFactsLang.langCandidates(preferredLangTag)) {
            set.add("product_name_" + lang);
            set.add("product_name_" + lang.replace('-', '_'));
        }

        return new ArrayList<>(set);
    }
}
