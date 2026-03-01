package com.calai.backend.foodlog.provider;

import com.calai.backend.foodlog.mapper.ProviderErrorMapper;
import com.calai.backend.foodlog.model.ProviderRefuseReason;
import com.calai.backend.foodlog.provider.config.GeminiProperties;
import com.calai.backend.foodlog.web.ModelRefusedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 專責：
 * 1. 呼叫 Gemini API
 * 2. 處理 provider refusal / HTTP refusal
 * 3. 解析 functionCall.args / text / usageMetadata
 */
@Slf4j
final class GeminiTransportSupport {

    private static final int PREVIEW_LEN = 200;
    private static final String FN_EMIT_NUTRITION = "emitNutrition";

    private final RestClient http;
    private final GeminiProperties props;
    private final GeminiRequestBuilder requestBuilder;

    GeminiTransportSupport(
            RestClient http,
            GeminiProperties props,
            GeminiRequestBuilder requestBuilder
    ) {
        this.http = http;
        this.props = props;
        this.requestBuilder = requestBuilder;
    }

    CallResult callAndExtract(
            byte[] imageBytes,
            String mimeType,
            String userPrompt,
            String modelId,
            boolean isLabel,
            String foodLogIdForLog
    ) {
        JsonNode resp;
        try {
            resp = callGenerateContent(imageBytes, mimeType, userPrompt, modelId, isLabel);
        } catch (RestClientResponseException re) {
            ProviderErrorMapper.Mapped mapped = ProviderErrorMapper.map(re);
            ProviderRefuseReason reason = ProviderRefuseReason.fromErrorCodeOrNull(mapped.code());
            if (reason != null) {
                log.warn("gemini_refused_http foodLogId={} modelId={} reason={} code={}",
                        foodLogIdForLog, modelId, reason, mapped.code());
                throw new ModelRefusedException(reason, mapped.code());
            }
            throw re;
        }

        ProviderRefuseReason reason = GeminiRefusalDetector.detectOrNull(resp);
        if (reason != null) {
            String code = "PROVIDER_REFUSED_" + reason.name();
            log.warn("gemini_refused foodLogId={} modelId={} reason={} code={}",
                    foodLogIdForLog, modelId, reason, code);
            throw new ModelRefusedException(reason, code);
        }

        Tok tok = extractUsage(resp);
        JsonNode fnArgs = extractFunctionArgsOrNull(resp);

        String text = extractJoinedTextOrNull(resp);
        if (text == null) {
            text = "";
        }

        if (fnArgs != null) {
            log.debug("geminiFnArgs foodLogId={} modelId={} keys={}",
                    foodLogIdForLog, modelId, fnArgs.fieldNames().hasNext() ? "HAS_FIELDS" : "EMPTY");
        } else {
            log.debug("geminiTextPreview foodLogId={} modelId={} preview={}",
                    foodLogIdForLog, modelId, safeOneLine200(text));
        }

        return new CallResult(tok, text, fnArgs);
    }

    CallResult callAndExtractTextOnly(
            String userPrompt,
            String modelId,
            String foodLogIdForLog
    ) {
        String sys = "You are a JSON repair engine. Return ONLY ONE minified JSON object. No markdown. No extra text.";
        return callAndExtractTextOnly(userPrompt, modelId, foodLogIdForLog, sys);
    }

    CallResult callAndExtractTextOnly(
            String userPrompt,
            String modelId,
            String foodLogIdForLog,
            String systemInstruction
    ) {
        JsonNode resp;
        try {
            resp = callGenerateContentTextOnly(systemInstruction, userPrompt, modelId);
        } catch (RestClientResponseException re) {
            ProviderErrorMapper.Mapped mapped = ProviderErrorMapper.map(re);
            ProviderRefuseReason reason = ProviderRefuseReason.fromErrorCodeOrNull(mapped.code());
            if (reason != null) {
                log.warn("gemini_refused_http(textOnly) foodLogId={} modelId={} reason={} code={}",
                        foodLogIdForLog, modelId, reason, mapped.code());
                throw new ModelRefusedException(reason, mapped.code());
            }
            throw re;
        }

        ProviderRefuseReason reason = GeminiRefusalDetector.detectOrNull(resp);
        if (reason != null) {
            String code = "PROVIDER_REFUSED_" + reason.name();
            log.warn("gemini_refused(textOnly) foodLogId={} modelId={} reason={} code={}",
                    foodLogIdForLog, modelId, reason, code);
            throw new ModelRefusedException(reason, code);
        }

        Tok tok = extractUsage(resp);

        String text = extractJoinedTextOrNull(resp);
        if (text == null) {
            text = "";
        }

        log.debug("geminiTextOnlyPreview foodLogId={} modelId={} preview={}",
                foodLogIdForLog, modelId, safeOneLine200(text));

        return new CallResult(tok, text, null);
    }

    private JsonNode callGenerateContent(
            byte[] imageBytes,
            String mimeType,
            String userPrompt,
            String modelId,
            boolean isLabel
    ) {
        ObjectNode req = requestBuilder.buildRequest(
                imageBytes,
                mimeType,
                userPrompt,
                isLabel,
                FN_EMIT_NUTRITION
        );

        return http.post()
                .uri("/v1beta/models/{model}:generateContent", modelId)
                .header("x-goog-api-key", requireApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(req)
                .retrieve()
                .body(JsonNode.class);
    }

    private JsonNode callGenerateContentTextOnly(
            String systemInstruction,
            String userPrompt,
            String modelId
    ) {
        ObjectNode req = requestBuilder.buildTextOnlyRequest(systemInstruction, userPrompt);

        return http.post()
                .uri("/v1beta/models/{model}:generateContent", modelId)
                .header("x-goog-api-key", requireApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(req)
                .retrieve()
                .body(JsonNode.class);
    }

    private String requireApiKey() {
        String k = props.getApiKey();
        if (k == null || k.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY_MISSING");
        }
        return k.trim();
    }

    private static JsonNode extractFunctionArgsOrNull(JsonNode resp) {
        if (resp == null || resp.isNull()) {
            return null;
        }

        JsonNode parts = resp.path("candidates").path(0).path("content").path("parts");
        if (!parts.isArray()) {
            return null;
        }

        for (JsonNode p : parts) {
            JsonNode fc = p.get("functionCall");
            if (fc == null || !fc.isObject()) {
                continue;
            }

            String name = fc.path("name").asText(null);
            if (name == null || name.isBlank()) {
                continue;
            }

            JsonNode args = fc.get("args");
            if (args != null && args.isObject()) {
                return args;
            }
        }
        return null;
    }

    private static String extractJoinedTextOrNull(JsonNode resp) {
        if (resp == null || resp.isNull()) {
            return null;
        }

        JsonNode cand0 = resp.path("candidates").path(0);
        JsonNode parts = cand0.path("content").path("parts");
        if (!parts.isArray()) {
            return null;
        }

        StringBuilder sb = new StringBuilder(256);
        for (JsonNode p : parts) {
            String t = p.path("text").asText(null);
            if (t != null) {
                sb.append(t);
            }
        }

        String joined = sb.toString().trim();
        return joined.isEmpty() ? null : joined;
    }

    private Tok extractUsage(JsonNode resp) {
        Integer p = null;
        Integer c = null;
        Integer t = null;

        JsonNode usage = (resp == null) ? null : resp.path("usageMetadata");
        if (usage != null && !usage.isMissingNode() && !usage.isNull()) {
            p = usage.path("promptTokenCount").isInt() ? usage.path("promptTokenCount").asInt() : null;
            c = usage.path("candidatesTokenCount").isInt() ? usage.path("candidatesTokenCount").asInt() : null;
            t = usage.path("totalTokenCount").isInt() ? usage.path("totalTokenCount").asInt() : null;
        }

        return new Tok(p, c, t);
    }

    private static String safeOneLine200(String s) {
        if (s == null) {
            return null;
        }
        String t = s.replace("\r", " ").replace("\n", " ").trim();
        return (t.length() > PREVIEW_LEN) ? t.substring(0, PREVIEW_LEN) : t;
    }

    record CallResult(Tok tok, String text, JsonNode functionArgs) {}

    record Tok(Integer promptTok, Integer candTok, Integer totalTok) {}
}
