package com.calai.backend.foodlog.barcode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
public class OpenFoodFactsClient {

    private final RestClient http; // 你已有 RestClient patterns
    private final ObjectMapper om;

    public JsonNode getProduct(String barcode) {
        String url = "https://world.openfoodfacts.org/api/v2/product/" + barcode + ".json";
        String body = http.get().uri(url).retrieve().body(String.class);
        try {
            return om.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("OFF_JSON_PARSE_FAILED");
        }
    }
}
