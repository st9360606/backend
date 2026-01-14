package com.calai.backend.foodlog.provider;

import com.calai.backend.foodlog.entity.FoodLogEntity;
import com.calai.backend.foodlog.service.LogMealTokenService;
import com.calai.backend.foodlog.storage.StorageService;
import com.calai.backend.foodlog.task.ProviderClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.io.InputStream;

public class LogMealProviderClient implements ProviderClient {

    private final RestClient http;
    private final LogMealProperties props;
    private final LogMealTokenService tokenService;
    private final ObjectMapper om = new ObjectMapper();

    public LogMealProviderClient(RestClient http, LogMealProperties props, LogMealTokenService tokenService) {
        this.http = http;
        this.props = props;
        this.tokenService = tokenService;
    }

    @Override
    public ProviderResult process(FoodLogEntity log, StorageService storage) throws Exception {
        byte[] bytes;
        try (InputStream in = storage.open(log.getImageObjectKey()).inputStream()) {
            bytes = in.readAllBytes();
        }
        if (bytes.length == 0) throw new IllegalStateException("EMPTY_IMAGE");

        // ✅ per-user token
        String apiUserToken = tokenService.requireApiUserToken(log.getUserId());
        String authHeader = buildAuthHeader(apiUserToken);

        String imageId = callSegmentationComplete(bytes, authHeader);
        JsonNode intake = callGetIntake(imageId, authHeader);

        ObjectNode effective = mapToEffective(intake);
        effective.putPOJO("confidence", guessConfidence(intake));

        return new ProviderResult(effective, "LOGMEAL");
    }

    private String buildAuthHeader(String token) {
        // 讓你可配置 prefix（有些服務要 Bearer，有些不用）
        String prefix = props.authorizationPrefixOrDefault(); // 你在 LogMealProperties 加這個 getter
        if (prefix == null || prefix.isBlank()) return token;
        return prefix.trim() + " " + token;
    }

    private String callSegmentationComplete(byte[] bytes, String authHeader) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();

        ByteArrayResource file = new ByteArrayResource(bytes) {
            @Override public String getFilename() { return "upload.jpg"; }
        };
        body.add("image", file);

        JsonNode resp = http.post()
                .uri("/v2/image/segmentation/complete")
                .header(HttpHeaders.AUTHORIZATION, authHeader)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        String imageId = text(resp, "imageId");
        if (imageId == null) imageId = text(resp, "id");
        if (imageId == null) throw new IllegalStateException("LOGMEAL_IMAGE_ID_MISSING");
        return imageId;
    }

    private JsonNode callGetIntake(String imageId, String authHeader) {
        JsonNode resp = http.get()
                .uri("/v2/intake/{imageId}", imageId)
                .header(HttpHeaders.AUTHORIZATION, authHeader)
                .retrieve()
                .body(JsonNode.class);
        if (resp == null || resp.isNull()) throw new IllegalStateException("LOGMEAL_INTAKE_EMPTY");
        return resp;
    }

    private ObjectNode mapToEffective(JsonNode intake) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();

        String name = null;
        JsonNode dishes0 = intake.path("dishes").path(0);
        if (!dishes0.isMissingNode()) name = dishes0.path("name").asText(null);
        if (name == null) name = intake.path("foodName").asText(null);
        if (name == null) name = intake.path("title").asText(null);
        root.put("foodName", name);

        ObjectNode q = root.putObject("quantity");
        q.put("value", 1d);
        q.put("unit", "SERVING");

        ObjectNode n = root.putObject("nutrients");
        n.putPOJO("kcal", firstNumber(intake, "nutrients.kcal", "nutrition.kcal", "calories"));
        n.putPOJO("protein", firstNumber(intake, "nutrients.protein", "nutrition.protein"));
        n.putPOJO("fat", firstNumber(intake, "nutrients.fat", "nutrition.fat"));
        n.putPOJO("carbs", firstNumber(intake, "nutrients.carbs", "nutrition.carbs"));

        return root;
    }

    private Double firstNumber(JsonNode root, String... paths) {
        for (String p : paths) {
            JsonNode v = at(root, p);
            if (v != null && v.isNumber()) return v.asDouble();
        }
        return null;
    }

    private JsonNode at(JsonNode root, String dottedPath) {
        JsonNode cur = root;
        for (String part : dottedPath.split("\\.")) {
            if (cur == null) return null;
            cur = cur.path(part);
            if (cur.isMissingNode()) return null;
        }
        return cur;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText(null);
    }

    private Double guessConfidence(JsonNode intake) {
        JsonNode v = at(intake, "confidence");
        if (v != null && v.isNumber()) return v.asDouble();
        v = at(intake, "score");
        if (v != null && v.isNumber()) return v.asDouble();
        return null;
    }
}
