package com.calai.backend.foodlog.barcode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class OpenFoodFactsClient {

    private static final int MAX_ERROR_SNIPPET_BYTES = 1024;

    private final RestClient http;
    private final ObjectMapper om;

    public OpenFoodFactsClient(
            @Qualifier("offRestClient") RestClient http,
            ObjectMapper om
    ) {
        this.http = http;
        this.om = om;
    }

    /** 舊方法保留：不破壞既有呼叫點 */
    public JsonNode getProduct(String barcode) {
        return getProduct(barcode, null);
    }

    /** ✅ 新方法：可帶 fields 精簡 payload */
    public JsonNode getProduct(String barcode, List<String> fields) {

        String body = http.get()
                .uri(uriBuilder -> {
                    var b = uriBuilder.path("/api/v2/product/{barcode}.json");
                    if (fields != null && !fields.isEmpty()) {
                        b = b.queryParam("fields", String.join(",", fields));
                    }
                    return b.build(barcode);
                })
                .retrieve()
                // ✅ 關鍵：把 4xx/5xx 拉出來，不要混成 JSON parse fail
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    int status = res.getStatusCode().value();
                    String snippet = readBodySnippetQuietly(res, MAX_ERROR_SNIPPET_BYTES);

                    // 你想更好查也可以帶 URI：req.getURI()
                    throw new OffHttpException(
                            status,
                            "OFF_HTTP_" + status,
                            snippet
                    );
                })
                .body(String.class);

        // ✅ 2xx 但 body 空：直接丟更明確的 parse exception
        if (body == null || body.isBlank()) {
            throw new OffParseException(
                    "OFF_EMPTY_BODY",
                    "OFF returned empty body (2xx) for barcode=" + safe(barcode),
                    null,
                    null
            );
        }

        try {
            return om.readTree(body);
        } catch (Exception e) {
            String snippet = shrink(body, 300);
            throw new OffParseException(
                    "OFF_JSON_PARSE_FAILED",
                    "OFF JSON parse failed (2xx). barcode=" + safe(barcode) + ", snippet=" + snippet,
                    snippet,
                    e
            );
        }
    }

    private static String readBodySnippetQuietly(ClientHttpResponse res, int maxBytes) {
        try (InputStream in = res.getBody()) {
            if (in == null) return null;
            byte[] bytes = in.readNBytes(Math.max(0, maxBytes));
            if (bytes.length == 0) return "";
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception ignore) {
            return null;
        }
    }

    private static String shrink(String s, int maxChars) {
        if (s == null) return null;
        String t = s.replaceAll("\\s+", " ").trim();
        if (t.length() <= maxChars) return t;
        return t.substring(0, maxChars) + "...";
    }

    private static String safe(String s) {
        if (s == null) return "null";
        String t = s.trim();
        return t.isEmpty() ? "blank" : t;
    }
}
