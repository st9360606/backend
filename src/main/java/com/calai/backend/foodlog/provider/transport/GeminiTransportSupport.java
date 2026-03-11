package com.calai.backend.foodlog.provider.transport;

import com.calai.backend.foodlog.mapper.ProviderErrorMapper;
import com.calai.backend.foodlog.model.ProviderRefuseReason;
import com.calai.backend.foodlog.provider.config.GeminiEnabledComponent;
import com.calai.backend.foodlog.provider.config.GeminiProperties;
import com.calai.backend.foodlog.web.ModelRefusedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
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
@GeminiEnabledComponent
public final class GeminiTransportSupport {

    private static final int PREVIEW_LEN = 200;
    private static final String FN_EMIT_NUTRITION = "emitNutrition";

    private final RestClient http;
    private final GeminiProperties props;
    private final GeminiRequestBuilder requestBuilder;

    public GeminiTransportSupport(
            @Qualifier("geminiRestClient") RestClient http,
            GeminiProperties props,
            GeminiRequestBuilder requestBuilder
    ) {
        this.http = http;
        this.props = props;
        this.requestBuilder = requestBuilder;
    }

    public CallResult callAndExtract(
            byte[] imageBytes,
            String mimeType,
            String userPrompt,
            String modelId,
            boolean isLabel,
            String foodLogIdForLog,
            boolean requireCoreNutrition
    ) {
        JsonNode resp;
        try {
            resp = callGenerateContent(imageBytes, mimeType, userPrompt, modelId, isLabel, requireCoreNutrition);
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

        log.debug("gemini_response_summary foodLogId={} modelId={} {}",
                foodLogIdForLog, modelId, responseDebugSummary(resp));

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

    /**
     * ✅ 保留原本 image request 路徑
     * PHOTO / ALBUM / LABEL 主流程都靠這個方法送圖給 Gemini。
     */
    private JsonNode callGenerateContent(
            byte[] imageBytes,
            String mimeType,
            String userPrompt,
            String modelId,
            boolean isLabel,
            boolean requireCoreNutrition
    ) {
        ObjectNode req = requestBuilder.buildRequest(
                imageBytes,
                mimeType,
                userPrompt,
                isLabel,
                FN_EMIT_NUTRITION,
                requireCoreNutrition
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

        JsonNode candidates = resp.path("candidates");
        if (!candidates.isArray()) {
            return null;
        }

        for (JsonNode cand : candidates) {
            JsonNode parts = cand.path("content").path("parts");
            if (!parts.isArray()) {
                continue;
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
        }
        return null;
    }

    private static String extractJoinedTextOrNull(JsonNode resp) {
        if (resp == null || resp.isNull()) {
            return null;
        }
        JsonNode candidates = resp.path("candidates");
        if (!candidates.isArray()) {
            return null;
        }
        for (JsonNode cand : candidates) {
            JsonNode parts = cand.path("content").path("parts");
            if (!parts.isArray()) {
                continue;
            }
            StringBuilder sb = new StringBuilder(256);
            for (JsonNode p : parts) {
                String t = p.path("text").asText(null);
                if (t != null) {
                    sb.append(t);
                }
            }
            String joined = sb.toString().trim();
            if (!joined.isEmpty()) {
                return joined;
            }
        }
        return null;
    }

    private static String responseDebugSummary(JsonNode resp) {
        if (resp == null || resp.isNull()) {
            return "resp=null";
        }
        JsonNode promptFeedback = resp.path("promptFeedback");
        String blockReason = promptFeedback.path("blockReason").asText("");

        JsonNode candidates = resp.path("candidates");
        int candidateCount = candidates.isArray() ? candidates.size() : 0;

        StringBuilder sb = new StringBuilder();
        sb.append("candidateCount=").append(candidateCount);

        if (!blockReason.isBlank()) {
            sb.append(" blockReason=").append(blockReason);
        }
        if (candidates.isArray()) {
            for (int i = 0; i < candidates.size(); i++) {
                JsonNode cand = candidates.get(i);
                String finishReason = cand.path("finishReason").asText("");
                if (!finishReason.isBlank()) {
                    sb.append(" c").append(i).append(".finishReason=").append(finishReason);
                }
            }
        }
        return sb.toString();
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

    public record CallResult(Tok tok, String text, JsonNode functionArgs) {}

    public record Tok(Integer promptTok, Integer candTok, Integer totalTok) {}
}
